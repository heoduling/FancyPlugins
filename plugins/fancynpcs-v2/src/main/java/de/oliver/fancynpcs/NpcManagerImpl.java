package de.oliver.fancynpcs;

import de.oliver.fancyanalytics.logger.ExtendedFancyLogger;
import de.oliver.fancyanalytics.logger.properties.ThrowableProperty;
import de.oliver.fancylib.serverSoftware.ServerSoftware;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcAttribute;
import de.oliver.fancynpcs.api.NpcData;
import de.oliver.fancynpcs.api.NpcManager;
import de.oliver.fancynpcs.api.actions.ActionTrigger;
import de.oliver.fancynpcs.api.actions.NpcAction;
import de.oliver.fancynpcs.api.actions.types.UnknownActionAction;
import de.oliver.fancynpcs.api.data.property.NpcVisibility;
import de.oliver.fancynpcs.api.events.NpcsLoadedEvent;
import de.oliver.fancynpcs.api.skins.SkinData;
import de.oliver.fancynpcs.api.skins.SkinLoadException;
import de.oliver.fancynpcs.api.utils.NpcEquipmentSlot;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.ApiStatus;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

public class NpcManagerImpl implements NpcManager, Listener {

    private final FancyNpcs plugin;
    private final ExtendedFancyLogger logger;
    private final Function<NpcData, Npc> npcAdapter;
    private final NpcStorage storage;
    // Protects membership and storage submission order, never held during file I/O.
    private final Object storageOrder = new Object();
    private final Map<String, Npc> npcs; // npc id -> npc
    private final Map<String, SavedNpcName> savedNames = new LinkedHashMap<>(); // guarded by storageOrder
    private final Map<CommandSender, Deque<QueuedCreate>> pendingCreates = new IdentityHashMap<>();
    private Boolean initialLoadResult; // guarded by storageOrder
    private volatile boolean isLoaded;
    private volatile boolean closing;
    private boolean loading;
    private long reloadGeneration;
    private final Map<String, WeakReference<World>> pendingWorldUnloads = new HashMap<>(); // global scheduler
    private final Set<String> deletedDuringLoad = new HashSet<>();
    private final Set<String> failedSnapshots = new HashSet<>(); // writer-owned

    public NpcManagerImpl(FancyNpcs plugin, Function<NpcData, Npc> npcAdapter) {
        this.plugin = plugin;
        this.logger = FancyNpcs.getInstance().getFancyLogger();
        this.npcAdapter = npcAdapter;
        npcs = new ConcurrentHashMap<>();
        storage = new NpcStorage(plugin.getDataFolder().toPath().resolve("npcs.yml"), plugin.getLogger());
    }

    public void registerNpc(Npc npc) {
        synchronized (storageOrder) {
            if (closing) {
                throw new IllegalStateException("NPC storage is closing");
            }
            var data = npc.getData();
            if (findReservedCreationName(data.getName(), data.getCreator()) != null) {
                throw new IllegalStateException("An NPC with this name already exists");
            }
            npcs.put(data.getId(), npc);
            rememberSavedName(npc);
        }
    }

    public Npc findCreationConflict(String name, UUID creator) {
        return FancyNpcs.PLAYER_NPCS_FEATURE_FLAG.isEnabled() ? getNpc(name, creator) : getNpc(name);
    }

    public String findReservedCreationName(String name, UUID creator) {
        synchronized (storageOrder) {
            var active = findCreationConflict(name, creator);
            if (active != null) return active.getData().getName();
            boolean perOwner = FancyNpcs.PLAYER_NPCS_FEATURE_FLAG.isEnabled();
            for (SavedNpcName saved : savedNames.values()) {
                if ((!perOwner || Objects.equals(creator, saved.creator())) && saved.name().equalsIgnoreCase(name)) {
                    return saved.name();
                }
            }
            return null;
        }
    }

    private record SavedNpcName(String name, UUID creator) {}

    private void rememberSavedName(Npc npc) {
        if (npc.isSaveToFile()) {
            var data = npc.getData();
            savedNames.put(data.getId(), new SavedNpcName(data.getName(), data.getCreator()));
        }
    }

    public boolean registerCreatedNpc(Npc npc) {
        synchronized (storageOrder) {
            var data = npc.getData();
            if (!isLoaded || closing || findReservedCreationName(data.getName(), data.getCreator()) != null) {
                return false;
            }
            if (npcs.putIfAbsent(data.getId(), npc) != null) {
                return false;
            }
            rememberSavedName(npc);
            // Queue before acknowledging creation so an immediate reload reads the new NPC.
            queueSave(false, List.of(npc));
            return true;
        }
    }

    public void runAfterInitialLoad(CommandSender sender, String name, String permission, Runnable command) {
        synchronized (storageOrder) {
            if (closing) {
                plugin.getTranslator().translate("command_unloading").withPrefix().send(sender);
                return;
            }
            if (initialLoadResult == null || Boolean.TRUE.equals(initialLoadResult) && pendingCreates.containsKey(sender)) {
                pendingCreates.computeIfAbsent(sender, ignored -> new ArrayDeque<>())
                        .addLast(new QueuedCreate(name, permission, command));
                // The waiting acknowledgement must precede any terminal result.
                plugin.getTranslator().translate("npc_create_waiting_for_data").withPrefix().send(sender);
                return;
            }
            if (!isLoaded) {
                plugin.getTranslator().translate("npc_create_data_unavailable").withPrefix().send(sender);
                return;
            }
        }
        command.run();
    }

    private void finishInitialLoad(boolean loaded) {
        List<CommandSender> senders;
        synchronized (storageOrder) {
            if (closing || initialLoadResult != null) return;
            initialLoadResult = loaded;
            senders = List.copyOf(pendingCreates.keySet());
        }
        for (CommandSender sender : senders) {
            runOnSender(sender, () -> drainCreates(sender, loaded), () -> discardCreates(sender));
        }
    }

    private void discardCreates(CommandSender sender) {
        synchronized (storageOrder) {
            pendingCreates.remove(sender);
        }
    }

    private void drainCreates(CommandSender sender, boolean loaded) {
        while (true) {
            QueuedCreate create;
            synchronized (storageOrder) {
                var queue = pendingCreates.get(sender);
                if (queue == null) return;
                create = queue.pollFirst();
                if (create == null) {
                    pendingCreates.remove(sender);
                    return;
                }
            }
            if (closing || !plugin.isEnabled()) {
                sendCreateCancelled(sender, create.name());
            } else if (!loaded) {
                plugin.getTranslator().translate("npc_create_data_load_failed").withPrefix().send(sender);
            } else if (!sender.hasPermission(create.permission())) {
                plugin.getTranslator().translate("command_missing_permissions").withPrefix().send(sender);
            } else {
                try {
                    create.command().run();
                } catch (RuntimeException error) {
                    logger.error("Could not run queued NPC creation", ThrowableProperty.of(error));
                    plugin.getTranslator().translate("npc_create_queued_failure").withPrefix().send(sender);
                }
            }
        }
    }

    private void sendCreateCancelled(CommandSender sender, String name) {
        if (Bukkit.isStopping()) return;
        var message = plugin.getTranslator().translate("npc_create_cancelled_unload")
                .withPrefix().replaceStripped("npc", name).buildComponent();
        // Paper's Adventure overload sends a system chat packet without touching region state.
        if (sender instanceof Player player) {
            if (player.isConnected()) player.sendMessage(message);
        } else if (sender instanceof ConsoleCommandSender) {
            sender.sendMessage(message);
        } else {
            plugin.getLogger().info(PlainTextComponentSerializer.plainText().serialize(message));
        }
    }

    private record QueuedCreate(String name, String permission, Runnable command) {}

    public void removeNpc(Npc npc) {
        removeNpcAsync(npc);
    }

    public CompletableFuture<Void> removeNpcAsync(Npc npc) {
        synchronized (storageOrder) {
            String id = npc.getData().getId();
            if (closing || !npcs.remove(id, npc)) {
                return null;
            }
            if (loading) {
                deletedDuringLoad.add(id);
            }
            return storage.submit(() -> {
                failedSnapshots.remove(id);
                storage.delete(id);
                storage.flush();
                synchronized (storageOrder) {
                    if (!npcs.containsKey(id)) savedNames.remove(id);
                }
                return null;
            });
        }
    }

    @ApiStatus.Internal
    @Override
    public Npc getNpc(int entityId) {
        for (Npc npc : getAllNpcs()) {
            if (npc.getEntityId() == entityId) {
                return npc;
            }
        }

        return null;
    }

    @Override
    public Npc getNpc(String name) {
        for (Npc npc : getAllNpcs()) {
            if (npc.getData().getName().equalsIgnoreCase(name)) {
                return npc;
            }
        }

        return null;
    }

    @Override
    public Npc getNpcById(String id) {
        return id == null ? null : npcs.get(id);
    }

    @Override
    public Npc getNpc(String name, UUID creator) {
        for (Npc npc : getAllNpcs()) {
            if (Objects.equals(npc.getData().getCreator(), creator) && npc.getData().getName().equalsIgnoreCase(name)) {
                return npc;
            }
        }

        return null;
    }

    public Collection<Npc> getAllNpcs() {
        return new ArrayList<>(npcs.values());
    }

    public void saveNpcs(boolean force) {
        saveNpcsAsync(force);
    }

    public CompletableFuture<Void> saveNpcsAsync(boolean force) {
        synchronized (storageOrder) {
            if (!isLoaded || loading || closing) {
                return null;
            }
            return queueSave(force);
        }
    }

    private CompletableFuture<Void> queueSave(boolean force) {
        // An older save can only update this membership, and a later delete runs after it.
        return queueSave(force, List.copyOf(npcs.values()));
    }

    private CompletableFuture<Void> queueSave(boolean force, List<Npc> members) {
        return storage.submit(() -> {
            RuntimeException snapshotFailure = null;
            for (Npc npc : members) {
                String id = npc.getData().getId();
                if (!npc.isSaveToFile()) {
                    failedSnapshots.remove(id);
                    continue;
                }
                if (!force && !npc.isDirty() && !failedSnapshots.contains(id)) {
                    continue;
                }
                // Clear before capturing, so concurrent setters can mark the next save dirty.
                npc.setDirty(false);
                try {
                    storage.update(id, snapshot(npc));
                    failedSnapshots.remove(id);
                } catch (RuntimeException e) {
                    npc.setDirty(true);
                    failedSnapshots.add(id);
                    if (snapshotFailure == null) {
                        snapshotFailure = e;
                    }
                }
            }
            storage.flush(force);
            if (snapshotFailure != null) {
                throw snapshotFailure;
            }
            return null;
        });
    }

    void closeStorage() {
        Map<CommandSender, Deque<QueuedCreate>> cancelled;
        synchronized (storageOrder) {
            if (closing) {
                return;
            }
            closing = true;
            cancelled = new IdentityHashMap<>(pendingCreates);
            pendingCreates.clear();
            if (isLoaded) {
                queueSave(true);
            } else {
                storage.submit(() -> {
                    storage.flush();
                    return null;
                });
            }
        }
        try {
            cancelled.forEach((sender, queue) -> queue.forEach(create -> sendCreateCancelled(sender, create.name())));
        } finally {
            storage.shutdown();
        }
    }

    public void replyWhenStored(CommandSender sender, CompletableFuture<Void> result, Runnable success, Runnable failure) {
        result.whenCompleteAsync((ignored, error) -> runOnSender(sender, () -> {
            if (error == null) success.run();
            else failure.run();
        }));
    }

    private void runOnSender(CommandSender sender, Runnable work) {
        runOnSender(sender, work, () -> {});
    }

    private void runOnSender(CommandSender sender, Runnable work, Runnable retired) {
        if (closing || !plugin.isEnabled()) return;
        Runnable guarded = () -> {
            if (closing || !plugin.isEnabled()) return;
            if (sender instanceof Player player && !player.isConnected()) retired.run();
            else work.run();
        };
        try {
            if (!ServerSoftware.isFolia()) {
                Bukkit.getScheduler().runTask(plugin, guarded);
            } else if (sender instanceof Player player) {
                if (player.getScheduler().run(plugin, task -> guarded.run(), retired) == null) retired.run();
            } else if (sender instanceof BlockCommandSender block) {
                Bukkit.getRegionScheduler().execute(plugin, block.getBlock().getLocation(), guarded);
            } else {
                Bukkit.getGlobalRegionScheduler().execute(plugin, guarded);
            }
        } catch (IllegalPluginAccessException ignoredDuringDisable) {
            // Disable can race scheduling a reply; persistence does not depend on delivery.
        }
    }

    private Consumer<YamlConfiguration> snapshot(Npc npc) {
        NpcData data = npc.getData();
        String prefix = "npcs." + data.getId() + ".";
        Map<String, Object> fields = new LinkedHashMap<>();

        Location location = data.getLocation().clone();
        fields.put(prefix + "name", data.getName());
        fields.put(prefix + "creator", data.getCreator().toString());
        fields.put(prefix + "displayName", data.getDisplayName());
        fields.put(prefix + "type", data.getType().name());
        fields.put(prefix + "location.world", location.getWorld().getName());
        fields.put(prefix + "location.x", location.getX());
        fields.put(prefix + "location.y", location.getY());
        fields.put(prefix + "location.z", location.getZ());
        fields.put(prefix + "location.yaw", location.getYaw());
        fields.put(prefix + "location.pitch", location.getPitch());
        fields.put(prefix + "showInTab", data.isShowInTab());
        fields.put(prefix + "spawnEntity", data.isSpawnEntity());
        fields.put(prefix + "collidable", data.isCollidable());
        fields.put(prefix + "glowing", data.isGlowing());
        fields.put(prefix + "glowingColor", data.getGlowingColor().toString());
        fields.put(prefix + "turnToPlayer", data.isTurnToPlayer());
        fields.put(prefix + "turnToPlayerDistance", data.getTurnToPlayerDistance());
        fields.put(prefix + "messages", null);
        fields.put(prefix + "playerCommands", null);
        fields.put(prefix + "serverCommands", null);
        fields.put(prefix + "sendMessagesRandomly", null);
        fields.put(prefix + "interactionCooldown", data.getInteractionCooldown());
        fields.put(prefix + "scale", data.getScale());
        fields.put(prefix + "visibility_distance", data.getVisibilityDistance());
        fields.put(prefix + "visibility", data.getVisibility().name());

        SkinData skin = data.getSkinData();
        if (skin != null) {
            fields.put(prefix + "skin.identifier", skin.getIdentifier());
            fields.put(prefix + "skin.variant", skin.getVariant().name());
        } else {
            fields.put(prefix + "skin.identifier", null);
        }
        fields.put(prefix + "skin.mirrorSkin", data.isMirrorSkin());

        if (data.getEquipment() != null) {
            for (Map.Entry<NpcEquipmentSlot, ItemStack> entry : data.getEquipment().entrySet()) {
                fields.put(prefix + "equipment." + entry.getKey().name(), entry.getValue().clone());
            }
        }

        fields.put(prefix + "attributes", null);
        data.getAttributes().forEach((attribute, value) ->
                fields.put(prefix + "attributes." + attribute.getName(), value));

        for (Map.Entry<ActionTrigger, List<NpcAction.NpcActionData>> entry : data.getActions().entrySet()) {
            for (NpcAction.NpcActionData actionData : new ArrayList<>(entry.getValue())) {
                if (actionData == null) {
                    continue;
                }

                if (actionData.action() instanceof UnknownActionAction unknownActionAction) {
                    fields.put(prefix + "actions." + entry.getKey().name() + "." + actionData.order() + ".action", unknownActionAction.getUnknownActionName());
                    fields.put(prefix + "actions." + entry.getKey().name() + "." + actionData.order() + ".value", unknownActionAction.getUnknownActionValue());
                } else {
                    fields.put(prefix + "actions." + entry.getKey().name() + "." + actionData.order() + ".action", actionData.action().getName());
                    fields.put(prefix + "actions." + entry.getKey().name() + "." + actionData.order() + ".value", actionData.value());
                }
            }
        }
        return config -> {
            String actions = prefix + "actions";
            ConfigurationSection existing = config.getConfigurationSection(actions);
            if (existing != null) {
                for (String trigger : existing.getKeys(false)) {
                    if (ActionTrigger.getByName(trigger) != null) {
                        existing.set(trigger, null);
                    }
                }
            }
            fields.forEach(config::set);
        };
    }

    public void loadNpcs() {
        reloadFromStorage();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        plugin.getScheduler().runTask(null, () -> {
            if (closing || event.isCancelled()) return;
            World world = event.getWorld();
            String name = world.getName();
            var previous = pendingWorldUnloads.get(name);
            if (previous != null && previous.get() == world) return;
            if (previous != null) detachClosedWorld(name, previous);
            var expected = new WeakReference<>(world);
            pendingWorldUnloads.put(name, expected);
            confirmWorldUnload(name, expected);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        plugin.getScheduler().runTask(null, () -> {
            var previous = pendingWorldUnloads.get(world.getName());
            if (previous != null) detachClosedWorld(world.getName(), previous);
            restoreWorld(world);
        });
    }

    private void confirmWorldUnload(String name, WeakReference<World> expected) {
        // Worlds fires the event before its async save and may unregister the world many ticks later.
        if (!detachClosedWorld(name, expected)) {
            plugin.getScheduler().runTaskLater(null, 10, () -> confirmWorldUnload(name, expected));
        }
    }

    private boolean detachClosedWorld(String name, WeakReference<World> expected) {
        if (closing || pendingWorldUnloads.get(name) != expected) return true;
        World world = expected.get();
        if (isCurrentWorld(world)) return false;
        pendingWorldUnloads.remove(name);
        if (world == null) return true;
        List<Npc> detached = new ArrayList<>();
        synchronized (storageOrder) {
            if (closing) return true;
            npcs.forEach((id, npc) -> {
                Location location = npc.getData().getLocation();
                if (location != null && location.getWorld() == world && npcs.remove(id, npc)) {
                    rememberSavedName(npc);
                    detached.add(npc);
                }
            });
            if (!detached.isEmpty()) queueSave(false, detached);
        }
        detached.forEach(Npc::removeForAll);
        return true;
    }

    private static boolean isCurrentWorld(World world) {
        return world != null && Bukkit.getWorld(world.getName()) == world;
    }

    private void restoreWorld(World world) {
        if (!isCurrentWorld(world)) return;
        final long generation;
        final CompletableFuture<YamlConfiguration> read;
        synchronized (storageOrder) {
            if (closing) return;
            if (loading) {
                plugin.getScheduler().runTaskLater(null, 1, () -> restoreWorld(world));
                return;
            }
            if (!isLoaded) return; // Initial load will read all active worlds.
            generation = reloadGeneration;
            read = storage.submit(() -> {
                storage.flush();
                return storage.read();
            });
        }

        // File and skin work do not keep a closed world alive or hold a tick thread.
        var target = new WeakReference<>(world);
        String worldName = world.getName();
        read.thenCompose(config -> resolveWorldSkins(config, worldName).thenAccept(skins -> {
            if (!closing && plugin.isEnabled()) {
                plugin.getScheduler().runTask(null, () -> installWorldNpcs(target.get(), generation, config, skins));
            }
        })).exceptionally(error -> {
            if (!closing) logger.error("Could not restore NPCs for world " + worldName, ThrowableProperty.of(error));
            return null;
        });
    }

    private void installWorldNpcs(World world, long generation, YamlConfiguration config, Map<String, SkinData> skins) {
        if (!isCurrentWorld(world)) return;
        synchronized (storageOrder) {
            if (closing) return;
            if (loading || generation != reloadGeneration) {
                restoreWorld(world);
                return;
            }
        }

        try {
            Map<String, Npc> restored = loadFromConfig(config, readSavedNames(config), world, skins);
            synchronized (storageOrder) {
                if (closing || !isCurrentWorld(world)) return;
                if (loading || generation != reloadGeneration) {
                    restoreWorld(world);
                    return;
                }
                for (Npc npc : restored.values()) {
                    NpcData data = npc.getData();
                    if (findCreationConflict(data.getName(), data.getCreator()) == null
                            && npcs.putIfAbsent(data.getId(), npc) == null) {
                        rememberSavedName(npc);
                    }
                }
            }
            // The visibility tracker will publish the normal per-viewer spawn event, including for addons.
        } catch (RuntimeException error) {
            logger.error("Could not install restored NPCs for world " + world.getName(), ThrowableProperty.of(error));
        }
    }

    private static boolean matchesSavedWorld(ConfigurationSection entry, String worldName) {
        if (entry == null) return false;
        if (entry.get("location") instanceof Location location) {
            return location.isWorldLoaded() && location.getWorld().getName().equals(worldName);
        }
        return worldName.equals(entry.getString("location.world"));
    }

    private CompletableFuture<Map<String, SkinData>> resolveWorldSkins(YamlConfiguration config, String worldName) {
        Map<String, CompletableFuture<SkinData>> pending = new HashMap<>();
        ConfigurationSection entries = config.getConfigurationSection("npcs");
        if (entries != null) {
            for (String id : entries.getKeys(false)) {
                ConfigurationSection entry = entries.getConfigurationSection(id);
                if (npcs.containsKey(id) || !matchesSavedWorld(entry, worldName)) continue;
                String identifier = entry.getString("skin.identifier", entry.getString("skin.uuid", ""));
                if (identifier.isEmpty()) continue;
                var variant = SkinData.SkinVariant.valueOf(entry.getString("skin.variant", "AUTO"));
                var saved = new SkinData(identifier, variant, entry.getString("skin.value"), entry.getString("skin.signature"));
                if (saved.hasTexture()) {
                    pending.put(id, CompletableFuture.completedFuture(saved));
                } else {
                    pending.put(id, plugin.getSkinManagerImpl().getByIdentifierAsync(identifier, variant)
                            .handle((resolved, error) -> error == null
                                    ? new SkinData(identifier, resolved.getVariant(), resolved.getTextureValue(), resolved.getTextureSignature())
                                    : saved));
                }
            }
        }
        return CompletableFuture.allOf(pending.values().toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Map<String, SkinData> skins = new HashMap<>();
            pending.forEach((id, future) -> skins.put(id, future.join()));
            return skins;
        });
    }

    public boolean reloadFromStorage() {
        final CompletableFuture<YamlConfiguration> read;
        final Map<String, Npc> previous;
        final Map<String, SavedNpcName> previousNames;
        synchronized (storageOrder) {
            if (closing || loading) {
                return false;
            }
            loading = true;
            reloadGeneration++;
            previous = new HashMap<>(npcs);
            previousNames = new HashMap<>(savedNames);
            read = storage.submit(() -> {
                storage.flush();
                if (!failedSnapshots.isEmpty()) {
                    throw new IOException("NPCs from an earlier save could not be captured; retry save before reload");
                }
                return storage.read();
            });
        }

        List<Npc> removed = new ArrayList<>();
        try {
            YamlConfiguration config = read.get(10, TimeUnit.SECONDS);
            Map<String, SavedNpcName> fromDisk = readSavedNames(config);
            Map<String, Npc> loaded = loadFromConfig(config, fromDisk, null, Map.of());
            synchronized (storageOrder) {
                if (closing) {
                    return false;
                }
                previous.forEach((id, npc) -> {
                    if (npcs.remove(id, npc)) {
                        removed.add(npc);
                    }
                });
                loaded.forEach((id, npc) -> {
                    if (!deletedDuringLoad.contains(id) && isCurrentWorld(npc.getData().getLocation().getWorld())) {
                        npcs.putIfAbsent(id, npc);
                    }
                });
                // A creation or successful deletion may have completed after the read was queued.
                fromDisk.keySet().removeIf(id -> previousNames.containsKey(id) && !savedNames.containsKey(id));
                savedNames.forEach((id, name) -> {
                    if (!name.equals(previousNames.get(id))) fromDisk.put(id, name);
                    else if (deletedDuringLoad.contains(id)) fromDisk.putIfAbsent(id, name);
                });
                savedNames.clear();
                savedNames.putAll(fromDisk);
                isLoaded = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            logger.error("Could not load NPCs; keeping the current NPCs", ThrowableProperty.of(e));
            return false;
        } finally {
            synchronized (storageOrder) {
                loading = false;
                deletedDuringLoad.clear();
            }
            if (!isLoaded) finishInitialLoad(false);
        }
        removed.forEach(Npc::removeForAll);
        new NpcsLoadedEvent().callEvent();
        finishInitialLoad(true);
        return true;
    }

    private static Map<String, SavedNpcName> readSavedNames(YamlConfiguration config) {
        Map<String, SavedNpcName> names = new LinkedHashMap<>();
        ConfigurationSection entries = config.getConfigurationSection("npcs");
        if (entries != null) {
            for (String id : entries.getKeys(false)) {
                String name = entries.getString(id + ".name", id);
                String owner = entries.getString(id + ".creator");
                names.put(id, new SavedNpcName(name, owner == null ? null : UUID.fromString(owner)));
            }
        }
        return names;
    }

    private Map<String, Npc> loadFromConfig(YamlConfiguration npcConfig, Map<String, SavedNpcName> persistedNames,
                                           World onlyWorld, Map<String, SkinData> resolvedSkins) {
        Map<String, Npc> loaded = new LinkedHashMap<>();
        Map<UUID, Set<String>> seenNames = new HashMap<>();
        boolean perOwner = FancyNpcs.PLAYER_NPCS_FEATURE_FLAG.isEnabled();
        for (var entry : persistedNames.entrySet()) {
            String id = entry.getKey();
            String name = entry.getValue().name();
            UUID creator = entry.getValue().creator();
            // Claim names before filtering out closed worlds so the first saved record always wins.
            if (!seenNames.computeIfAbsent(perOwner ? creator : null,
                    ignored -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(name)) {
                if (onlyWorld == null) logger.warn("Skipping duplicate saved NPC '" + name + "' (" + id + "); record kept.");
                continue;
            }
            if (onlyWorld != null && (npcs.containsKey(id)
                    || !matchesSavedWorld(npcConfig.getConfigurationSection("npcs." + id), onlyWorld.getName()))) {
                continue;
            }
            String displayName = npcConfig.getString("npcs." + id + ".displayName", "<empty>");
            EntityType type = EntityType.valueOf(npcConfig.getString("npcs." + id + ".type", "PLAYER").toUpperCase());

            Location location = null;

            try {
                location = npcConfig.getLocation("npcs." + id + ".location");
            } catch (Exception ignored) {
                logger.warn("Could not load location for npc '" + id + "'");
            }

            if (location == null) {
                String worldName = npcConfig.getString("npcs." + id + ".location.world");
                World world = onlyWorld != null ? onlyWorld : Bukkit.getWorld(worldName);

                if (world == null) {
                    File worldFolder = new File(worldName);
                    if (worldFolder.exists() && worldFolder.isDirectory()) {
                        world = (!ServerSoftware.isFolia()) ? new WorldCreator(worldName).createWorld() : null;
                    }
                }

                if (world == null) {
                    logger.info("Could not load npc '" + id + "', because the world '" + worldName + "' is not loaded");
                    continue;
                }

                double x = npcConfig.getDouble("npcs." + id + ".location.x");
                double y = npcConfig.getDouble("npcs." + id + ".location.y");
                double z = npcConfig.getDouble("npcs." + id + ".location.z");
                float yaw = (float) npcConfig.getDouble("npcs." + id + ".location.yaw");
                float pitch = (float) npcConfig.getDouble("npcs." + id + ".location.pitch");

                location = new Location(world, x, y, z, yaw, pitch);
            }

            if (onlyWorld != null && location.getWorld() != onlyWorld) continue;

            SkinData skin = resolvedSkins.get(id);
            String skinIdentifier = npcConfig.getString("npcs." + id + ".skin.identifier", npcConfig.getString("npcs." + id + ".skin.uuid", ""));
            String skinVariantStr = npcConfig.getString("npcs." + id + ".skin.variant", SkinData.SkinVariant.AUTO.name());
            SkinData.SkinVariant skinVariant = SkinData.SkinVariant.valueOf(skinVariantStr);
            if (onlyWorld == null && !skinIdentifier.isEmpty()) {
                try {
                    skin = FancyNpcs.getInstance().getSkinManagerImpl().getByIdentifier(skinIdentifier, skinVariant);
                    skin.setIdentifier(skinIdentifier);
                } catch (final SkinLoadException e) {
                    logger.error("NPC named '" + name + "' identified by '" + id + "' could not have their skin loaded.");
                    logger.error("  " + e.getReason() + " " + e.getMessage());
                }
            }


            if (onlyWorld == null && npcConfig.isSet("npcs." + id + ".skin.value") && npcConfig.isSet("npcs." + id + ".skin.signature")) {
                // using old skin system --> take backup
                takeBackup(npcConfig);

                String value = npcConfig.getString("npcs." + id + ".skin.value");
                String signature = npcConfig.getString("npcs." + id + ".skin.signature");

                if (value != null && !value.isEmpty() && signature != null && !signature.isEmpty()) {
                    SkinData oldSkin = new SkinData(skinIdentifier, SkinData.SkinVariant.AUTO, value, signature);
                    FancyNpcs.getInstance().getSkinManagerImpl().getFileCache().addSkin(oldSkin);
                    FancyNpcs.getInstance().getSkinManagerImpl().getMemCache().addSkin(oldSkin);
                }
            }

            boolean mirrorSkin = npcConfig.getBoolean("npcs." + id + ".skin.mirrorSkin");

            boolean showInTab = npcConfig.getBoolean("npcs." + id + ".showInTab");
            boolean spawnEntity = npcConfig.getBoolean("npcs." + id + ".spawnEntity");
            boolean collidable = npcConfig.getBoolean("npcs." + id + ".collidable", true);
            boolean glowing = npcConfig.getBoolean("npcs." + id + ".glowing");
            NamedTextColor glowingColor = NamedTextColor.NAMES.value(npcConfig.getString("npcs." + id + ".glowingColor", "white"));
            boolean turnToPlayer = npcConfig.getBoolean("npcs." + id + ".turnToPlayer");
            int turnToPlayerDistance = npcConfig.getInt("npcs." + id + ".turnToPlayerDistance", -1);

            Map<ActionTrigger, List<NpcAction.NpcActionData>> actions = new ConcurrentHashMap<>();

            ConfigurationSection actiontriggerSection = npcConfig.getConfigurationSection("npcs." + id + ".actions");
            if (actiontriggerSection != null) {
                actiontriggerSection.getKeys(false).forEach(trigger -> {
                    ActionTrigger actionTrigger = ActionTrigger.getByName(trigger);
                    if (actionTrigger == null) {
                        logger.warn("Could not find action trigger: " + trigger);
                        return;
                    }

                    List<NpcAction.NpcActionData> actionList = new ArrayList<>();
                    ConfigurationSection actionsSection = npcConfig.getConfigurationSection("npcs." + id + ".actions." + trigger);
                    if (actionsSection != null) {
                        actionsSection.getKeys(false).forEach(order -> {
                            String actionName = npcConfig.getString("npcs." + id + ".actions." + trigger + "." + order + ".action");
                            String value = npcConfig.getString("npcs." + id + ".actions." + trigger + "." + order + ".value");
                            NpcAction action = FancyNpcs.getInstance().getActionManager().getActionByName(actionName);
                            if (action == null) {
                                logger.warn("Could not find action: " + actionName);
                                action = new UnknownActionAction(actionTrigger, actionName, value, Integer.parseInt(order));
                            }

                            try {
                                actionList.add(new NpcAction.NpcActionData(Integer.parseInt(order), action, value));
                            } catch (NumberFormatException e) {
                                logger.warn("Could not parse order: " + order);
                            }
                        });

                        actions.put(actionTrigger, actionList);
                    }
                });
            }

            float interactionCooldown = (float) npcConfig.getDouble("npcs." + id + ".interactionCooldown", 0);
            float scale = (float) npcConfig.getDouble("npcs." + id + ".scale", 1);
            int visibilityDistance = npcConfig.getInt("npcs." + id + ".visibility_distance", -1);
            String visibilityStr = npcConfig.getString("npcs." + id + ".visibility", "ALL");
            NpcVisibility visibility = NpcVisibility.byString(visibilityStr).orElse(NpcVisibility.ALL);

            Map<NpcAttribute, String> attributes = new HashMap<>();
            if (npcConfig.isConfigurationSection("npcs." + id + ".attributes")) {
                for (String attrName : npcConfig.getConfigurationSection("npcs." + id + ".attributes").getKeys(false)) {
                    String value = npcConfig.getString("npcs." + id + ".attributes." + attrName);
                    if (value == null) {
                        continue;
                    }
                    NpcAttribute attribute = FancyNpcs.getInstance().getAttributeManager().getAttributeByName(type, attrName);
                    if (attribute != null && !attribute.isValidValue(value)) {
                        logger.warn("Keeping unavailable value for attribute " + attrName + " on NPC " + name);
                        attribute = null;
                    }
                    if (attribute == null) {
                        attribute = FancyNpcs.getInstance().getExtensions().attributeReference(type, attrName);
                    }

                    attributes.put(attribute, value);
                }
            }

            NpcData data = new NpcData(
                    id,
                    name,
                    creator,
                    displayName,
                    skin,
                    location,
                    showInTab,
                    spawnEntity,
                    collidable,
                    glowing,
                    glowingColor,
                    type,
                    new HashMap<>(),
                    turnToPlayer,
                    turnToPlayerDistance,
                    null,
                    actions,
                    interactionCooldown,
                    scale,
                    visibilityDistance,
                    attributes,
                    mirrorSkin
            );
            Npc npc = npcAdapter.apply(data);

            if (npcConfig.isConfigurationSection("npcs." + id + ".equipment")) {
                for (String equipmentSlotStr : npcConfig.getConfigurationSection("npcs." + id + ".equipment").getKeys(false)) {
                    NpcEquipmentSlot equipmentSlot = NpcEquipmentSlot.parse(equipmentSlotStr);
                    ItemStack item = npcConfig.getItemStack("npcs." + id + ".equipment." + equipmentSlotStr);
                    npc.getData().addEquipment(equipmentSlot, item);
                }
            }

            npc.getData().setVisibility(visibility);
            npc.create();
            loaded.put(id, npc);
        }
        return loaded;
    }

    @Override
    public boolean isLoaded() {
        return isLoaded;
    }

    public void reloadNpcs() {
        reloadFromStorage();
    }

    private void takeBackup(YamlConfiguration npcConfig) {
        String folderPath = "plugins" + File.separator + "FancyNpcs" + File.separator + "/backups";
        File backupDir = new File(folderPath);
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }

        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        String backupFileName = "npcs-" + formatter.format(now) + ".yml";
        File backupFile = new File(folderPath + File.separator + backupFileName);
        if (backupFile.exists()) {
            backupFile.delete();
        }

        try {
            backupFile.createNewFile();
        } catch (IOException e) {
            logger.error("Could not create backup file for NPCs");
        }

        try {
            npcConfig.save(backupFile);
        } catch (IOException e) {
            logger.error("Could not save backup file for NPCs");
        }
    }
}
