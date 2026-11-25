package de.oliver.fancynpcs.skins;

import com.google.gson.Gson;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import de.oliver.fancyanalytics.logger.properties.ThrowableProperty;
import de.oliver.fancylib.UUIDFetcher;
import de.oliver.fancynpcs.FancyNpcs;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.skins.SkinData;
import de.oliver.fancynpcs.api.skins.SkinGeneratedEvent;
import de.oliver.fancynpcs.api.skins.SkinLoadException;
import de.oliver.fancynpcs.api.skins.SkinManager;
import de.oliver.fancynpcs.skins.mojang.MojangQueue;
import de.oliver.fancynpcs.skins.cache.SkinCache;
import de.oliver.fancynpcs.skins.cache.SkinCacheData;
import de.oliver.fancynpcs.skins.uuidcache.UUIDCache;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.property.InputDataResult;
import net.skinsrestorer.api.property.MojangSkinDataResult;
import net.skinsrestorer.api.property.SkinProperty;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.lushplugins.chatcolorhandler.paper.PaperColor;
import org.mineskin.data.Variant;
import org.mineskin.request.GenerateRequest;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class SkinManagerImpl implements SkinManager, Listener {

    private static final String ECLIPSE_API = "https://eclipse.skinsrestorer.net/mojang";

    public final static ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(
            5,
            new ThreadFactoryBuilder()
                    .setNameFormat("FancyNpcs-Skins-%d")
                    .build()
    );

    private final String SKINS_DIRECTORY = "plugins/FancyNpcs/skins/";

    private final UUIDCache uuidCache;
    private final SkinCache fileCache;
    private final SkinCache memCache;
    private final SkinGenerationQueue mojangQueue;
    private final SkinGenerationQueue mineSkinQueue;
    private final ThreadPoolExecutor lookupExecutor = new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32),
            new ThreadFactoryBuilder()
                    .setDaemon(true)
                    .setNameFormat("FancyNpcs-SkinLookup-%d")
                    .build(),
            new ThreadPoolExecutor.AbortPolicy()
    );
    private final ConcurrentMap<String, CompletableFuture<SkinData>> activeLookups = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(EXECUTOR)
            .build();

    public SkinManagerImpl(
            UUIDCache uuidCache,
            SkinCache fileCache,
            SkinCache memCache,
            SkinGenerationQueue mojangQueue,
            SkinGenerationQueue mineSkinQueue
    ) {
        this.uuidCache = uuidCache;
        this.fileCache = fileCache;
        this.memCache = memCache;
        this.mojangQueue = mojangQueue;
        this.mineSkinQueue = mineSkinQueue;

        File skinsDir = new File(SKINS_DIRECTORY);
        if (!skinsDir.exists()) {
            skinsDir.mkdirs();
        }
    }

    @Override
    public SkinData getByIdentifier(String identifier, SkinData.SkinVariant variant) throws SkinLoadException {
        if (SkinUtils.isUUID(identifier)) {
            return getByUUID(UUID.fromString(identifier), variant);
        }

        if (SkinUtils.isURL(identifier)) {
            return getByURL(identifier, variant);
        }

        if (SkinUtils.isFile(identifier)) {
            return getByFile(identifier, variant);
        }

        if (SkinUtils.isPlaceholder(identifier)) {
            String parsed = PaperColor.handler().translateRaw(identifier);

            if (parsed.isBlank() || parsed.equalsIgnoreCase("null") || SkinUtils.isPlaceholder(parsed)) {
                throw new SkinLoadException(SkinLoadException.Reason.INVALID_PLACEHOLDER, "(RAW = '" + identifier + "'; PARSED = '" + parsed + "')");
            }

            return getByIdentifier(parsed, variant);
        }

        return getByUsername(identifier, variant);
    }

    public CompletableFuture<SkinData> getByIdentifierAsync(String identifier, SkinData.SkinVariant variant) {
        String normalizedIdentifier = SkinUtils.isUsername(identifier) && !SkinUtils.isPlaceholder(identifier)
                ? identifier.toLowerCase(Locale.ROOT)
                : identifier;
        String lookupKey = normalizedIdentifier + '\u0000' + variant.name();
        CompletableFuture<SkinData> created = new CompletableFuture<>();
        CompletableFuture<SkinData> existing = activeLookups.putIfAbsent(lookupKey, created);
        if (existing != null) {
            return existing;
        }

        created.orTimeout(25, TimeUnit.SECONDS)
                .whenComplete((result, error) -> activeLookups.remove(lookupKey, created));

        try {
            lookupExecutor.execute(() -> {
                try {
                    created.complete(getByIdentifier(identifier, variant));
                } catch (Throwable throwable) {
                    created.completeExceptionally(throwable);
                }
            });
        } catch (RejectedExecutionException exception) {
            created.completeExceptionally(exception);
        }

        return created;
    }

    @Override
    public SkinData getByUUID(UUID uuid, SkinData.SkinVariant variant) {
        SkinData cached = tryToGetFromCache(uuid.toString(), variant);
        if (cached != null) {
            return cached;
        }

        mojangQueue.add(new SkinGenerationRequest(uuid.toString(), variant));

//        GenerateRequest genReq = GenerateRequest.user(uuid);
//        genReq.variant(Variant.valueOf(variant.name()));
//        mineSkinQueue.add(new MineSkinQueue.SkinRequest(uuid.toString(), genReq));
        return new SkinData(uuid.toString(), variant);
    }

    @Override
    public SkinData getByUsername(String username, SkinData.SkinVariant variant) throws SkinLoadException {
        SkinData cached = tryToGetFromCache(username, variant);
        if (cached != null) {
            return cached;
        }

        SkinData skinsRestorerSkin = getBySkinsRestorer(username, variant);
        if (skinsRestorerSkin != null) {
            return skinsRestorerSkin;
        }

        SkinData eclipseSkin = getByEclipse(username, variant);
        if (eclipseSkin != null) {
            return eclipseSkin;
        }

        SkinData serverProfile = getByServerProfile(username, variant);
        if (serverProfile != null) {
            return serverProfile;
        }

        UUID uuid = getCachedOnlineUUID(username);
        if (uuid == null) {
            uuid = UUIDFetcher.getUUID(username);
            if (uuid == null) {
                throw new SkinLoadException(SkinLoadException.Reason.INVALID_USERNAME, "(USERNAME = '" + username + "')");
            }
            uuidCache.cacheUUID(username, uuid);
        }

        SkinData dataByUUID = getByUUID(uuid, variant);

        return new SkinData(username, dataByUUID.getVariant(), dataByUUID.getTextureValue(), dataByUUID.getTextureSignature());
    }

    private SkinData getBySkinsRestorer(String username, SkinData.SkinVariant variant) {
        if (!Bukkit.getPluginManager().isPluginEnabled("SkinsRestorer")) {
            return null;
        }

        try {
            var api = SkinsRestorerProvider.get();
            var stored = api.getSkinStorage().findOrCreateSkinData("player:" + username);
            SkinsRestorerResult result;
            if (stored.isPresent()) {
                InputDataResult skin = stored.get();
                result = new SkinsRestorerResult(
                        skin.getIdentifier().getPlayerUniqueId(),
                        skin.getProperty(),
                        false
                );
            } else {
                var fresh = api.getMojangAPI().getSkin(username);
                if (fresh.isEmpty()) {
                    return null;
                }

                MojangSkinDataResult skin = fresh.get();
                result = new SkinsRestorerResult(skin.getUniqueId(), skin.getSkinProperty(), true);
            }

            SkinProperty property = result.property();
            if (property.getValue().isBlank() || property.getSignature().isBlank()) {
                FancyNpcs.getInstance().getFancyLogger().warn("SkinsRestorer returned an empty skin property for username: " + username);
                return null;
            }

            if (result.bypassedStorageCache()) {
                FancyNpcs.getInstance().getFancyLogger().info("Resolved skin through SkinsRestorer providers after storage cache miss: " + username);
            }
            uuidCache.cacheUUID(username, result.uuid());
            SkinData skin = new SkinData(username, variant, property.getValue(), property.getSignature());
            cacheSkin(skin);
            return skin;
        } catch (Exception e) {
            FancyNpcs.getInstance().getFancyLogger().warn(
                    "SkinsRestorer API failed for username " + username + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    ThrowableProperty.of(e)
            );
            return null;
        }
    }

    private record SkinsRestorerResult(UUID uuid, SkinProperty property, boolean bypassedStorageCache) {
    }

    private SkinData getByEclipse(String username, SkinData.SkinVariant variant) {
        try {
            String encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8).replace("+", "%20");
            HttpResponse<String> uuidResponse = sendRequest(ECLIPSE_API + "/uuid/" + encodedUsername);
            if (uuidResponse.statusCode() != 200) {
                throw new IOException("Eclipse UUID endpoint returned HTTP " + uuidResponse.statusCode());
            }

            EclipseUuidResponse uuidData = gson.fromJson(uuidResponse.body(), EclipseUuidResponse.class);
            if (uuidData == null || !uuidData.exists() || uuidData.uuid() == null || uuidData.uuid().isBlank()) {
                return null;
            }

            UUID uuid = UUID.fromString(uuidData.uuid());
            HttpResponse<String> skinResponse = sendRequest(ECLIPSE_API + "/skin/" + uuid);
            if (skinResponse.statusCode() != 200) {
                throw new IOException("Eclipse skin endpoint returned HTTP " + skinResponse.statusCode());
            }

            EclipseSkinResponse skinData = gson.fromJson(skinResponse.body(), EclipseSkinResponse.class);
            if (skinData == null || !skinData.exists() || skinData.skinProperty() == null) {
                return null;
            }

            EclipseSkinProperty property = skinData.skinProperty();
            if (property.value() == null || property.value().isBlank()
                    || property.signature() == null || property.signature().isBlank()) {
                return null;
            }

            uuidCache.cacheUUID(username, uuid);
            SkinData skin = new SkinData(username, variant, property.value(), property.signature());
            cacheSkin(skin);
            return skin;
        } catch (Exception e) {
            FancyNpcs.getInstance().getFancyLogger().warn(
                    "Eclipse skin lookup failed for username " + username + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    ThrowableProperty.of(e)
            );
            return null;
        }
    }

    private HttpResponse<String> sendRequest(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .header("User-Agent", "FancyNpcs/2.11.0")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private record EclipseUuidResponse(boolean exists, String uuid) {
    }

    private record EclipseSkinResponse(boolean exists, EclipseSkinProperty skinProperty) {
    }

    private record EclipseSkinProperty(String value, String signature) {
    }

    private SkinData getByServerProfile(String username, SkinData.SkinVariant variant) {
        try {
            PlayerProfile profile = Bukkit.createProfile(username);
            boolean completed = profile.complete(true, true);
            if (!completed) {
                FancyNpcs.getInstance().getFancyLogger().warn("Server profile could not resolve online username: " + username);
                return null;
            }

            if (profile.getId() == null) {
                FancyNpcs.getInstance().getFancyLogger().warn("Server profile returned no UUID for username: " + username);
                return null;
            }

            for (ProfileProperty property : profile.getProperties()) {
                if (property.getName().equals("textures") && !property.getValue().isBlank()) {
                    uuidCache.cacheUUID(username, profile.getId());
                    SkinData skin = new SkinData(username, variant, property.getValue(), property.getSignature());
                    cacheSkin(skin);
                    return skin;
                }
            }
            FancyNpcs.getInstance().getFancyLogger().warn("Server profile returned no texture for username: " + username);
        } catch (Exception e) {
            FancyNpcs.getInstance().getFancyLogger().warn(
                    "Failed to fetch server profile for username: " + username,
                    ThrowableProperty.of(e)
            );
        }
        return null;
    }

    private UUID getCachedOnlineUUID(String username) {
        UUID uuid = uuidCache.getUUID(username);
        if (uuid == null) {
            return null;
        }

        UUID offlineUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        return offlineUUID.equals(uuid) ? null : uuid;
    }

    @Override
    public SkinData getByURL(String url, SkinData.SkinVariant variant) throws SkinLoadException {
        SkinData cached = tryToGetFromCache(url, variant);
        if (cached != null) {
            return cached;
        }

        GenerateRequest genReq;
        try {
            genReq = GenerateRequest.url(url);
        } catch (final IllegalArgumentException | MalformedURLException e) {
            throw new SkinLoadException(SkinLoadException.Reason.INVALID_URL, "(URL = '" + url + "')");
        }
        genReq.variant(Variant.valueOf(variant.name()));
        mineSkinQueue.add(new SkinGenerationRequest(url, variant, genReq));
        return new SkinData(url, variant);
    }

    @Override
    public SkinData getByFile(String filePath, SkinData.SkinVariant variant) throws SkinLoadException {
        SkinData cached = tryToGetFromCache(filePath, variant);
        if (cached != null) {
            return cached;
        }

        File file = new File(SKINS_DIRECTORY + filePath);
        if (!file.exists()) {
            throw new SkinLoadException(SkinLoadException.Reason.INVALID_FILE, "(FILE = '" + filePath + "')");
        }

        GenerateRequest genReq = GenerateRequest.upload(file);
        genReq.variant(Variant.valueOf(variant.name()));
        mineSkinQueue.add(new SkinGenerationRequest(filePath, variant, genReq));
        return new SkinData(filePath, variant);
    }

    @EventHandler
    public void onSkinGenerated(SkinGeneratedEvent event) {
        if (event.getSkin() == null || !event.getSkin().hasTexture()) {
            FancyNpcs.getInstance().getFancyLogger().error("Generated skin has no texture!");
            return;
        }

        for (Npc npc : FancyNpcs.getInstance().getNpcManager().getAllNpcs()) {
            SkinData skin = npc.getData().getSkinData();
            if (skin == null)
                continue;

            String id = skin.getParsedIdentifier();
            if (SkinUtils.isUsername(id)) {
                UUID uuid = getCachedOnlineUUID(id);
                if (uuid == null) {
                    uuid = UUIDFetcher.getUUID(id);
                }

                if (uuid != null) {
                    uuidCache.cacheUUID(id, uuid);
                    id = uuid.toString();
                }
            }
            if (id.equals(event.getId())) {
                final SkinData updatedSkin = new SkinData(
                        skin.getIdentifier(),
                        event.getSkin().getVariant(),
                        event.getSkin().getTextureValue(),
                        event.getSkin().getTextureSignature()
                );
                npc.getData().setSkinData(updatedSkin);
                npc.removeForAll();
                npc.spawnForAll();
                FancyNpcs.getInstance().getFancyLogger().info("Updated skin for NPC: " + npc.getData().getName());
            }
        }

        cacheSkin(event.getSkin());
    }

    private SkinData tryToGetFromCache(String identifier, SkinData.SkinVariant variant) {
        FancyNpcs.getInstance().getFancyLogger().debug("Trying to get skin from mem cache: " + identifier);

        SkinCacheData data = memCache.getSkin(identifier);
        if (data != null) {
            if (data.skinData().getVariant() != variant) {
                FancyNpcs.getInstance().getFancyLogger().debug("Skin variant does not match: " + identifier);
                return null;
            }

            FancyNpcs.getInstance().getFancyLogger().debug("Found skin from mem cache: " + identifier);
            return data.skinData();
        }

        FancyNpcs.getInstance().getFancyLogger().debug("Trying to get skin from file cache: " + identifier);

        data = fileCache.getSkin(identifier);
        if (data != null) {
            if (data.skinData().getVariant() != variant) {
                FancyNpcs.getInstance().getFancyLogger().debug("Skin variant does not match: " + identifier);
                return null;
            }

            FancyNpcs.getInstance().getFancyLogger().debug("Found skin from file cache: " + identifier);
            memCache.addSkin(data.skinData());
            return data.skinData();
        }

        FancyNpcs.getInstance().getFancyLogger().debug("Skin not found in cache: " + identifier);
        return null;
    }

    public void cacheSkin(SkinData skinData) {
        memCache.addSkin(skinData);
        fileCache.addSkin(skinData);
    }

    public UUIDCache getUuidCache() {
        return uuidCache;
    }

    public void shutdown() {
        lookupExecutor.shutdownNow();
        activeLookups.values().forEach(future -> future.cancel(true));
        activeLookups.clear();
        httpClient.shutdown();
        if (mojangQueue instanceof MojangQueue queue) {
            queue.shutdown();
        }
        EXECUTOR.shutdownNow();
    }

    public SkinCache getFileCache() {
        return fileCache;
    }

    public SkinCache getMemCache() {
        return memCache;
    }
}
