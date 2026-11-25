package com.fancyinnovations.fancynpcsmodel.fancynpcshook;

import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import com.fancyinnovations.fancynpcsmodel.main.CarrierRestore;
import com.fancyinnovations.fancynpcsmodel.integration.NpcModelSession;
import com.fancyinnovations.fancynpcsmodel.listeners.NpcModelVisibilityListener;
import de.oliver.fancyanalytics.logger.properties.StringProperty;
import de.oliver.fancyanalytics.logger.properties.ThrowableProperty;
import de.oliver.fancylib.ReflectionUtils;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.NpcAttribute;
import de.oliver.fancynpcs.api.actions.ActionTrigger;
import de.oliver.fancynpcs.api.data.property.NpcVisibility;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.event.hitbox.HitBoxDamagedEvent;
import kr.toxicity.model.api.event.hitbox.HitBoxInteractAtEvent;
import kr.toxicity.model.api.platform.PlatformEntity;
import kr.toxicity.model.api.platform.PlatformPlayer;
import kr.toxicity.model.api.tracker.DummyTracker;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.EntityTrackerRegistry;
import kr.toxicity.model.api.tracker.Tracker;
import kr.toxicity.model.api.tracker.TrackerUpdateAction;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class CustomModelAttribute {

    public static final String ATTRIBUTE_NAME = "custom_model";

    private static final Map<String, ModelTracker> DUMMY_TRACKERS = new ConcurrentHashMap<>();
    private static final Set<String> HIDDEN_CARRIERS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> RECENT_INTERACTIONS = new ConcurrentHashMap<>();
    private static final long INTERACTION_DEDUPLICATION_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private static final class ModelTracker {
        private final Npc npc;
        private final World world;
        private final DummyTracker tracker;
        private final String modelName;
        private final float scale;
        private final ModelHitboxes hitboxes;
        private final ModelNameplates names;
        private boolean glowInitialized;
        private boolean glowing;
        private int glowColor = Integer.MIN_VALUE;

        private ModelTracker(Npc npc, World world, DummyTracker tracker, String modelName, float scale) {
            this.npc = npc;
            this.world = world;
            this.tracker = tracker;
            this.modelName = modelName;
            this.scale = scale;
            this.hitboxes = new ModelHitboxes(npc, tracker);
            this.names = new ModelNameplates(npc, tracker, scale);
        }

        private void close() {
            names.close();
            hitboxes.close();
            tracker.close();
        }

        private DummyTracker tracker() {
            return tracker;
        }

        private String modelName() {
            return modelName;
        }

        private float scale() {
            return scale;
        }

        private synchronized void applyVisualState(Npc npc) {
            boolean newGlowing = npc.getData().isGlowing();
            if (!glowInitialized || glowing != newGlowing) {
                tracker.update(TrackerUpdateAction.glow(newGlowing));
                glowing = newGlowing;
                glowInitialized = true;
            }

            if (npc.getData().getGlowingColor() != null) {
                int newGlowColor = npc.getData().getGlowingColor().value();
                if (glowColor != newGlowColor) {
                    tracker.update(TrackerUpdateAction.glowColor(newGlowColor));
                    glowColor = newGlowColor;
                }
            }
        }
    }

    public static NpcAttribute getModelAttribute() {
        return new NpcAttribute(
                ATTRIBUTE_NAME,
                () -> BetterModel.modelKeys().stream().toList(),
                List.of(EntityType.PLAYER, EntityType.INTERACTION),
                CustomModelAttribute::setModel
        ) {
            @Override
            public boolean isValidValue(String value) {
                // A saved binding remains valid even when BetterModel has not loaded the resource yet.
                return value != null && !value.isBlank();
            }
        };
    }

    private static void setModel(Npc npc, String modelName) {
        if (!NpcModelSession.isActive()) {
            return;
        }
        if (modelName.equalsIgnoreCase("@none")) {
            closeAllTrackers(npc);
            return;
        }

        ModelTracker modelTracker = getOrCreateDummyTracker(npc, modelName);
        if (modelTracker == null) {
            restoreCarrierAndSync(npc);
            return;
        }
        prepareCarrierEntity(npc);
        modelTracker.applyVisualState(npc);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (npc.isShownFor(player)) {
                spawnModelFor(player, npc, false);
            }
        }
    }

    private static synchronized ModelTracker getOrCreateDummyTracker(Npc npc, String modelName) {
        if (!NpcModelSession.isActive()) {
            return null;
        }
        Location location = npc.getData().getLocation();
        World world = location == null ? null : location.getWorld();
        if (world == null || ModelHitboxes.isUnloading(world) || Bukkit.getWorld(world.getName()) != world) {
            return null;
        }

        String trackerKey = trackerKey(npc);
        float scale = npc.getData().getScale();
        ModelTracker current = DUMMY_TRACKERS.get(trackerKey);
        if (current != null
                && current.npc == npc
                && current.world == world
                && !current.tracker().isClosed()
                && current.modelName().equalsIgnoreCase(modelName)
                && Float.compare(current.scale(), scale) == 0) {
            current.tracker().location(BukkitAdapter.adapt(location));
            return current;
        }

        if (current != null) {
            current.close();
        }

        closeLegacyEntityTrackers(npc);

        DummyTracker tracker = createDummyTracker(npc, modelName, location);
        if (tracker == null) {
            DUMMY_TRACKERS.remove(trackerKey);
            return null;
        }

        ModelTracker created = new ModelTracker(npc, world, tracker, modelName, scale);
        created.applyVisualState(npc);
        DUMMY_TRACKERS.put(trackerKey, created);
        return created;
    }

    private static DummyTracker createDummyTracker(Npc npc, String modelName, Location location) {
        DummyTracker tracker = BetterModel.model(modelName)
                .map(renderer -> renderer.create(BukkitAdapter.adapt(location)))
                .orElse(null);
        if (tracker == null) {
            FancyNpcsModelPlugin.get().getFancyLogger().error(
                    "Failed to get model with name " + modelName,
                    StringProperty.of("model_name", modelName),
                    StringProperty.of("npc_name", npc.getData().getName())
            );
            return null;
        }

        if (npc.getData().getScale() != 1) {
            tracker.scaler(tracker.scaler().multiply(npc.getData().getScale()));
        }

        attachHitboxActions(tracker, npc);
        return tracker;
    }

    private static void attachHitboxActions(Tracker tracker, Npc npc) {
        tracker.listenHitBox(HitBoxInteractAtEvent.class, event -> {
            Player player = Bukkit.getPlayer(event.getWho().uuid());
            if (player == null) return;

            interactOnce(npc, player, ActionTrigger.RIGHT_CLICK);
        });

        tracker.listenHitBox(HitBoxDamagedEvent.class, event -> {
            PlatformEntity causingEntity = event.getSource().getCausingEntity();
            if (causingEntity == null) return;
            Player player = Bukkit.getPlayer(causingEntity.uuid());
            if (player == null) return;

            interactOnce(npc, player, ActionTrigger.LEFT_CLICK);
        });
    }

    public static boolean claimInteraction(Npc npc, Player player, ActionTrigger trigger) {
        String key = trackerKey(npc) + "|" + player.getUniqueId() + "|" + trigger.name();
        long now = System.nanoTime();
        while (true) {
            Long previous = RECENT_INTERACTIONS.get(key);
            if (previous != null && now - previous <= INTERACTION_DEDUPLICATION_NANOS) {
                return false;
            }
            if (previous == null) {
                if (RECENT_INTERACTIONS.putIfAbsent(key, now) == null) {
                    return true;
                }
            } else if (RECENT_INTERACTIONS.replace(key, previous, now)) {
                return true;
            }
        }
    }

    public static void expireInteractions() {
        long now = System.nanoTime();
        RECENT_INTERACTIONS.entrySet().removeIf(entry -> now - entry.getValue() > INTERACTION_DEDUPLICATION_NANOS);
    }

    public static void forgetInteractions(Player player) {
        String playerKey = "|" + player.getUniqueId() + "|";
        RECENT_INTERACTIONS.keySet().removeIf(key -> key.contains(playerKey));
    }

    public static void forgetNpc(Npc npc) {
        String prefix = trackerKey(npc) + "|";
        RECENT_INTERACTIONS.keySet().removeIf(key -> key.startsWith(prefix));
        if (hasAttribute(npc) || DUMMY_TRACKERS.containsKey(trackerKey(npc))) closeAllTrackers(npc);
    }

    private static void interactOnce(Npc npc, Player player, ActionTrigger trigger) {
        if (NpcModelSession.isActive() && claimInteraction(npc, player, trigger)) {
            npc.interact(player, trigger);
        }
    }

    private static void prepareCarrierEntity(Npc npc) {
        synchronized (HIDDEN_CARRIERS) {
            if (!NpcModelSession.isActive()) return;
            Entity entity = getBukkitEntity(npc, false);
            if (entity == null) return;
            if (!CarrierRestore.setVirtualAppearance(entity, true, true, false)) {
                entity.customName(Component.empty());
                entity.setCustomNameVisible(false);
                entity.setInvisible(true);
                entity.setGlowing(false);
            }
            HIDDEN_CARRIERS.add(trackerKey(npc));
        }
    }

    private static Entity restoreCarrierEntity(Npc npc) {
        synchronized (HIDDEN_CARRIERS) {
            if (!HIDDEN_CARRIERS.remove(trackerKey(npc))) return null;
            Entity entity = getBukkitEntity(npc, false);
            if (entity != null && !restoreVirtualCarrier(npc, entity)) {
                entity.setInvisible(configuredInvisibility(npc));
                entity.setGlowing(npc.getData().isGlowing());
            }
            return entity;
        }
    }

    private static void restoreCarrierAndSync(Npc npc) {
        Entity entity = restoreCarrierEntity(npc);
        var host = NpcModelSession.activeHost();
        if (entity != null && host != null) syncCarrierForAll(npc, entity, host.getPlugin());
    }

    private static void syncCarrierForAll(Npc npc, Entity entity, Plugin owner) {
        Location location = npc.getData().getLocation();
        if (location == null || location.getWorld() == null) return;
        var world = location.getWorld();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!npc.isShownFor(viewer)) continue;
            viewer.getScheduler().run(owner, task -> {
                if (viewer.isOnline() && viewer.getWorld().equals(world) && npc.isShownFor(viewer)
                        && entity == getBukkitEntity(npc, false)) {
                    CarrierRestore.syncVirtualAppearance(entity, viewer);
                }
            }, null);
        }
    }

    private static boolean restoreVirtualCarrier(Npc npc, Entity entity) {
        return CarrierRestore.setVirtualAppearance(entity, false, configuredInvisibility(npc), npc.getData().isGlowing());
    }

    private static boolean configuredInvisibility(Npc npc) {
        return npc.getData().getAttributes().entrySet().stream()
                .anyMatch(entry -> entry.getKey().getName().equalsIgnoreCase("invisible") && Boolean.parseBoolean(entry.getValue()));
    }

    private static Entity getBukkitEntity(Npc npc) {
        return getBukkitEntity(npc, true);
    }

    private static Entity getBukkitEntity(Npc npc, boolean logMissing) {
        Object nmsEntity = ReflectionUtils.getValue(npc, "npc");
        if (nmsEntity == null) {
            if (logMissing) {
                FancyNpcsModelPlugin.get().getFancyLogger().error("Failed to get NMS entity from NPC");
            }
            return null;
        }

        try {
            return nmsEntity.getClass().getMethod("getBukkitEntity").invoke(nmsEntity) instanceof Entity entity
                    ? entity : null;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            if (logMissing) {
                FancyNpcsModelPlugin.get().getFancyLogger().error(
                        "Failed to invoke getBukkitEntity method on NMS entity",
                        ThrowableProperty.of(e),
                        StringProperty.of("npc_name", npc.getData().getName())
                );
            }
            return null;
        }
    }

    static synchronized void closeWorldTrackers(World world) {
        // Share the creation lock so a tracker already being built when unloading began is also retired.
        for (var entry : DUMMY_TRACKERS.entrySet()) {
            ModelTracker model = entry.getValue();
            if (model.world == world && DUMMY_TRACKERS.remove(entry.getKey(), model)) {
                model.close();
            }
        }
    }

    public static synchronized void closePluginTrackers() {
        ModelNameplateHeight.clearCache();
        var trackers = List.copyOf(DUMMY_TRACKERS.values());
        DUMMY_TRACKERS.clear();
        RECENT_INTERACTIONS.clear();
        for (ModelTracker tracker : trackers) {
            tracker.close();
        }
    }

    public static void clearCarrierState() {
        synchronized (HIDDEN_CARRIERS) {
            HIDDEN_CARRIERS.clear();
        }
    }

    public static void restoreCarriersOnDetach(Plugin model, FancyNpcsPlugin host) {
        if (Bukkit.isStopping() || !host.getPlugin().isEnabled()) return;
        Set<String> hidden;
        synchronized (HIDDEN_CARRIERS) {
            hidden = Set.copyOf(HIDDEN_CARRIERS);
        }
        for (Npc npc : host.getNpcManager().getAllNpcs()) {
            if (!hidden.contains(trackerKey(npc))) continue;
            Entity entity = getBukkitEntity(npc, false);
            if (entity == null) continue;
            Location location = npc.getData().getLocation();
            if (restoreVirtualCarrier(npc, entity)) {
                syncCarrierForAll(npc, entity, host.getPlugin());
            } else {
                CarrierRestore.afterDetach(model, host.getPlugin(), entity,
                        location == null ? null : location.clone(), configuredInvisibility(npc), npc.getData().isGlowing());
            }
        }
    }

    public static void closeAllTrackers(Npc npc) {
        closeDummyTracker(npc);
        closeLegacyEntityTrackers(npc);
        restoreCarrierEntity(npc);
    }

    public static void resetTrackerForReload(Npc npc) {
        closeDummyTracker(npc);
        closeLegacyEntityTrackers(npc);
    }

    public static void invalidateTrackerForTransform(Npc npc) {
        closeDummyTracker(npc);
    }

    private static void closeDummyTracker(Npc npc) {
        ModelTracker tracker = DUMMY_TRACKERS.remove(trackerKey(npc));
        if (tracker != null) {
            tracker.close();
        }
    }

    private static void closeLegacyEntityTrackers(Npc npc) {
        Entity bukkitEntity = getBukkitEntity(npc, false);
        if (bukkitEntity == null) {
            return;
        }

        BetterModel.registry(BukkitAdapter.adapt(bukkitEntity)).ifPresent(reg -> {
            for (EntityTracker tracker : new ArrayList<>(reg.trackers())) {
                tracker.close();
            }
        });
    }

    public static boolean hasAttribute(Npc npc) {
        return getModelName(npc)
                .filter(modelName -> !modelName.isBlank())
                .filter(modelName -> !modelName.equalsIgnoreCase("@none"))
                .isPresent();
    }

    public static void spawnModelFor(Player player, Npc npc) {
        spawnModelFor(player, npc, false);
    }

    public static void spawnModelFor(Player player, Npc npc, boolean forceRespawn) {
        var host = NpcModelSession.activeHost();
        if (host == null || !npc.isShownFor(player) || host.getNpcManager().getNpcById(npc.getData().getId()) != npc) {
            return;
        }
        if (!Bukkit.isOwnedByCurrentRegion(player)) {
            player.getScheduler().run(FancyNpcsModelPlugin.get(), task -> spawnModelFor(player, npc, forceRespawn), null);
            return;
        }
        if (!NpcModelVisibilityListener.canRenderFor(player, npc, host.getFancyNpcConfig().getVisibilityDistance())) return;
        Optional<String> modelNameOpt = getModelName(npc);
        if (modelNameOpt.isEmpty() || modelNameOpt.get().equalsIgnoreCase("@none")) {
            return;
        }

        Location location = npc.getData().getLocation();
        if (location == null || location.getWorld() == null || !player.getWorld().equals(location.getWorld())) {
            return;
        }

        ModelTracker modelTracker = getOrCreateDummyTracker(npc, modelNameOpt.get());
        if (modelTracker == null) {
            restoreCarrierAndSync(npc);
            return;
        }
        prepareCarrierEntity(npc);

        DummyTracker tracker = modelTracker.tracker();
        tracker.location(BukkitAdapter.adapt(location));
        modelTracker.applyVisualState(npc);

        PlatformPlayer platformPlayer = BukkitAdapter.adapt(player);
        if (forceRespawn && tracker.isSpawned(platformPlayer)) {
            modelTracker.names.hide(platformPlayer);
            tracker.remove(platformPlayer);
        }

        if (!tracker.isSpawned(platformPlayer)) {
            Entity carrier = getBukkitEntity(npc, false);
            if (carrier != null) CarrierRestore.syncVirtualAppearance(carrier, player);
            tracker.spawn(platformPlayer);
        }
        modelTracker.names.show(player, platformPlayer);
        modelTracker.hitboxes.show(player);
    }

    public static void removeModelFor(Player player, Npc npc) {
        ModelTracker modelTracker = DUMMY_TRACKERS.get(trackerKey(npc));
        if (modelTracker == null) {
            return;
        }

        PlatformPlayer platformPlayer = BukkitAdapter.adapt(player);
        modelTracker.names.hide(platformPlayer);
        if (modelTracker.tracker().isSpawned(platformPlayer)) {
            modelTracker.tracker().remove(platformPlayer);
            modelTracker.hitboxes.hide(player);
        }
    }

    public static Map<UUID, List<Npc>> permissionModelViewers() {
        Map<UUID, List<Npc>> viewers = new HashMap<>();
        for (ModelTracker model : DUMMY_TRACKERS.values()) {
            if (model.npc.getData().getVisibility() != NpcVisibility.PERMISSION_REQUIRED) continue;
            model.tracker.getPipeline().allPlayer().forEach(handler ->
                    viewers.computeIfAbsent(handler.uuid(), ignored -> new ArrayList<>()).add(model.npc));
        }
        return viewers;
    }

    public static void resetViewer(Player player) {
        PlatformPlayer viewer = BukkitAdapter.adapt(player);
        for (ModelTracker model : DUMMY_TRACKERS.values()) {
            model.names.hide(viewer);
            if (model.tracker().isSpawned(viewer)) {
                model.tracker().remove(viewer);
                model.hitboxes.hide(player);
            }
        }
    }

    private static Optional<String> getModelName(Npc npc) {
        for (Map.Entry<NpcAttribute, String> entry : npc.getData().getAttributes().entrySet()) {
            if (entry.getKey().getName().equalsIgnoreCase(ATTRIBUTE_NAME)) {
                return Optional.ofNullable(entry.getValue());
            }
        }

        return Optional.empty();
    }

    public static Tracker getModelTracker(Npc npc) {
        Optional<String> modelNameOpt = getModelName(npc);
        if (modelNameOpt.isEmpty() || modelNameOpt.get().equalsIgnoreCase("@none")) {
            return null;
        }

        ModelTracker modelTracker = getOrCreateDummyTracker(npc, modelNameOpt.get());
        return modelTracker == null ? null : modelTracker.tracker();
    }

    public static Tracker getEntityTracker(Npc npc) {
        return getModelTracker(npc);
    }

    public static Tracker getExistingModelTracker(Npc npc) {
        ModelTracker model = DUMMY_TRACKERS.get(trackerKey(npc));
        return model == null || model.tracker().isClosed() ? null : model.tracker();
    }

    private static EntityTracker getLegacyEntityTracker(Entity bukkitEntity) {
        Optional<EntityTrackerRegistry> trackersOpt = BetterModel.registry(BukkitAdapter.adapt(bukkitEntity));
        if (trackersOpt.isEmpty()) return null;

        Collection<EntityTracker> trackers = trackersOpt.get().trackers();
        if (trackers.isEmpty()) return null;

        return trackers.iterator().next();
    }

    private static String trackerKey(Npc npc) {
        String id = npc.getData().getId();
        return id == null || id.isBlank() ? npc.getData().getName() : id;
    }
}
