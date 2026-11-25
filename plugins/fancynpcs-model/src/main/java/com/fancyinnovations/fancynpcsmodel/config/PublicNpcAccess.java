package com.fancyinnovations.fancynpcsmodel.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.ScalarNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class PublicNpcAccess implements AutoCloseable {
    private static final Pattern UUID_MARKER = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern TEXT_MARKER = Pattern.compile("text-[0-9a-f]{64}");
    private final Path directory;
    private final Logger logger;
    private final Set<String> publicMarkers = ConcurrentHashMap.newKeySet();
    private final Set<String> removedDuringUpgrade = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean upgradeQueued = new AtomicBoolean();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), Thread.ofPlatform().daemon().name("FancyNpcsModel-PublicNPC").factory());
    private volatile boolean closed;
    private volatile boolean upgradeReady;

    public PublicNpcAccess(Path dataFolder, Logger logger) {
        this.directory = dataFolder.resolve("public-npcs").toAbsolutePath().normalize();
        this.logger = logger;
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                    .filter(name -> UUID_MARKER.matcher(name).matches() || TEXT_MARKER.matcher(name).matches())
                    .forEach(publicMarkers::add);
        } catch (IOException e) {
            publicMarkers.clear();
            logger.log(Level.WARNING, "Could not load public NPCs; access stays disabled", e);
        }
    }

    public boolean isPublic(String marker) {
        return !closed && publicMarkers.contains(marker);
    }

    public boolean isUpgradeReady() {
        return upgradeReady;
    }

    public void finishStartup(List<String> npcIds, Path npcFile, String version, Consumer<String> onVersionUpdated) {
        if (closed || !upgradeQueued.compareAndSet(false, true)) return;
        try {
            writer.execute(() -> {
                String lastVersion;
                synchronized (directory.toString().intern()) {
                    if (closed) return;
                    try {
                        Path versionFile = directory.resolveSibling("version.yml");
                        try {
                            lastVersion = Files.readString(versionFile).trim();
                        } catch (NoSuchFileException freshInstall) {
                            lastVersion = "N/A";
                        }
                        // After +24 a leftover legacy file cannot distinguish a missed migration from an OP revocation.
                        if ("1.1.0+23".equals(lastVersion)) migrateLegacy(npcIds, npcFile);
                        if (closed) return;
                        if (!version.equals(lastVersion)) Files.writeString(versionFile, version);
                        upgradeReady = true;
                        removedDuringUpgrade.clear();
                    } catch (IOException | RuntimeException e) {
                        logger.log(Level.WARNING, "Could not finish public NPC marker upgrade", e);
                        return;
                    }
                }
                if (!closed && !version.equals(lastVersion)) onVersionUpdated.accept(lastVersion);
            });
        } catch (RejectedExecutionException rejected) {
            if (!closed) logger.log(Level.WARNING, "Could not queue public NPC marker upgrade", rejected);
        }
    }

    private void migrateLegacy(List<String> npcIds, Path npcFile) throws IOException {
        Set<String> known = new HashSet<>(npcIds);
        // Compose keys only: skipped worlds still count, and Bukkit objects are not deserialized off-thread.
        try (var reader = Files.newBufferedReader(npcFile)) {
            if (new Yaml().compose(reader) instanceof MappingNode root) {
                for (var entry : root.getValue()) {
                    if (entry.getKeyNode() instanceof ScalarNode key && key.getValue().equals("npcs")
                            && entry.getValueNode() instanceof MappingNode npcs) {
                        for (var npc : npcs.getValue()) {
                            if (npc.getKeyNode() instanceof ScalarNode id) known.add(id.getValue());
                        }
                    }
                }
            }
        } catch (NoSuchFileException noSavedNpcs) {
            // A host with no saved NPCs can still have live registrations.
        }
        Map<String, List<String>> owners = new HashMap<>();
        for (String id : known) {
            String canonical = UUID_MARKER.matcher(id).matches() ? id : legacyUuidAlias(id);
            if (canonical != null && publicMarkers.contains(canonical)) {
                owners.computeIfAbsent(canonical, ignored -> new ArrayList<>(1)).add(id);
            }
        }
        for (var entry : owners.entrySet()) {
            if (closed) return;
            String oldKey = entry.getKey();
            List<String> matches = entry.getValue();
            if (matches.size() != 1) {
                logger.warning("Ambiguous legacy public NPC marker " + oldKey + "; leaving it unchanged");
                continue;
            }
            String id = matches.getFirst();
            if (oldKey.equals(id) || removedDuringUpgrade.contains(id)) continue;
            String newKey = markerKey(id);
            Path source = directory.resolve(oldKey);
            Path target = directory.resolve(newKey);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Public NPC marker is not a regular file: " + newKey);
                }
                Files.deleteIfExists(source);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
            publicMarkers.add(newKey);
            publicMarkers.remove(oldKey);
        }
    }

    public static String legacyUuidAlias(String id) {
        String lowercase = id.toLowerCase(Locale.ROOT);
        return !id.equals(lowercase) && UUID_MARKER.matcher(lowercase).matches() ? lowercase : null;
    }

    public static String markerKey(String id) {
        // Keep existing UUID files; every other ID is case-sensitive and never enters a path directly.
        if (UUID_MARKER.matcher(id).matches()) return id;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            return "text-" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public CompletableFuture<Void> set(String id, boolean enabled) {
        return write(markerKey(id), enabled);
    }

    private CompletableFuture<Void> write(String key, boolean enabled) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            writer.execute(() -> {
                try {
                    // A previous plugin generation may still be returning from its last disk operation.
                    synchronized (directory.toString().intern()) {
                        if (closed) throw new IOException("Public NPC storage is closed");
                        Path marker = directory.resolve(key);
                        if (enabled) {
                            Files.createDirectories(directory);
                            try {
                                Files.createFile(marker);
                            } catch (FileAlreadyExistsException existing) {
                                if (!Files.isRegularFile(marker)) throw existing;
                            }
                            publicMarkers.add(key);
                        } else {
                            Files.deleteIfExists(marker);
                            publicMarkers.remove(key);
                        }
                    }
                    result.complete(null);
                } catch (IOException | RuntimeException e) {
                    logger.log(Level.WARNING, "Could not save public NPC marker " + key, e);
                    result.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException busy) {
            result.completeExceptionally(busy);
        }
        return result;
    }

    public void forget(String id) {
        String key = markerKey(id);
        if (!upgradeReady && legacyUuidAlias(id) != null) removedDuringUpgrade.add(id);
        publicMarkers.remove(key);
        write(key, false);
    }

    @Override
    public void close() {
        closed = true;
        writer.shutdownNow();
    }
}
