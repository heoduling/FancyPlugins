package com.fancyinnovations.fancynpcsmodel.fancynpcshook;

import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import de.oliver.fancyanalytics.logger.properties.StringProperty;
import de.oliver.fancyanalytics.logger.properties.ThrowableProperty;
import de.oliver.fancylib.ReflectionUtils;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcAttribute;
import de.oliver.fancynpcs.api.actions.ActionTrigger;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class CustomModelAttribute {

    public static final String ATTRIBUTE_NAME = "custom_model";

    private static final Map<String, ModelTracker> DUMMY_TRACKERS = new ConcurrentHashMap<>();
    private static final Set<String> HIDDEN_CARRIERS = ConcurrentHashMap.newKeySet();
    private static final Map<InteractionKey, Long> RECENT_INTERACTIONS = new ConcurrentHashMap<>();
    private static final long INTERACTION_DEDUPLICATION_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private static final class ModelTracker {
        private final DummyTracker tracker;
        private final String modelName;
        private final float scale;
        private boolean glowInitialized;
        private boolean glowing;
        private int glowColor = Integer.MIN_VALUE;

        private ModelTracker(DummyTracker tracker, String modelName, float scale) {
            this.tracker = tracker;
            this.modelName = modelName;
            this.scale = scale;
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

    private record InteractionKey(String npcId, java.util.UUID playerId, ActionTrigger trigger) {
    }

    public static NpcAttribute getModelAttribute() {
        return new NpcAttribute(
                ATTRIBUTE_NAME,
                () -> BetterModel.modelKeys().stream().toList(),
                List.of(EntityType.PLAYER, EntityType.INTERACTION),
                CustomModelAttribute::setModel
        );
    }

    private static void setModel(Npc npc, String modelName) {
        if (modelName.equalsIgnoreCase("@none")) {
            closeAllTrackers(npc);
            return;
        }

        prepareCarrierEntity(npc);
        ModelTracker modelTracker = getOrCreateDummyTracker(npc, modelName);
        if (modelTracker == null) {
            return;
        }
        modelTracker.applyVisualState(npc);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (npc.isShownFor(player)) {
                spawnModelFor(player, npc, false);
            }
        }
    }

    private static synchronized ModelTracker getOrCreateDummyTracker(Npc npc, String modelName) {
        Location location = npc.getData().getLocation();
        if (location == null || location.getWorld() == null) {
            return null;
        }

        String trackerKey = trackerKey(npc);
        float scale = npc.getData().getScale();
        ModelTracker current = DUMMY_TRACKERS.get(trackerKey);
        if (current != null
                && !current.tracker().isClosed()
                && current.modelName().equalsIgnoreCase(modelName)
                && Float.compare(current.scale(), scale) == 0) {
            current.tracker().location(BukkitAdapter.adapt(location));
            return current;
        }

        if (current != null) {
            current.tracker().close();
        }

        closeLegacyEntityTrackers(npc);

        DummyTracker tracker = createDummyTracker(npc, modelName);
        if (tracker == null) {
            DUMMY_TRACKERS.remove(trackerKey);
            return null;
        }

        ModelTracker created = new ModelTracker(tracker, modelName, scale);
        created.applyVisualState(npc);
        DUMMY_TRACKERS.put(trackerKey, created);
        return created;
    }

    private static DummyTracker createDummyTracker(Npc npc, String modelName) {
        Location location = npc.getData().getLocation();
        if (location == null || location.getWorld() == null) {
            return null;
        }

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
        InteractionKey key = new InteractionKey(trackerKey(npc), player.getUniqueId(), trigger);
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

    private static void interactOnce(Npc npc, Player player, ActionTrigger trigger) {
        if (claimInteraction(npc, player, trigger)) {
            npc.interact(player, trigger);
        }
    }

    private static void prepareCarrierEntity(Npc npc) {
        Entity bukkitEntity = getBukkitEntity(npc, false);
        if (bukkitEntity == null) {
            return;
        }

        bukkitEntity.customName(Component.empty());
        bukkitEntity.setCustomNameVisible(false);
        bukkitEntity.setInvisible(true);
        bukkitEntity.setGlowing(false);
        HIDDEN_CARRIERS.add(trackerKey(npc));
    }

    private static void restoreCarrierEntity(Npc npc) {
        if (!HIDDEN_CARRIERS.remove(trackerKey(npc))) {
            return;
        }

        Entity bukkitEntity = getBukkitEntity(npc, false);
        if (bukkitEntity != null) {
            bukkitEntity.setInvisible(false);
            bukkitEntity.setGlowing(npc.getData().isGlowing());
        }
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
            return (Entity) ReflectionUtils.getMethod(nmsEntity, "getBukkitEntity").invoke(nmsEntity);
        } catch (IllegalAccessException | InvocationTargetException e) {
            FancyNpcsModelPlugin.get().getFancyLogger().error(
                    "Failed to invoke getBukkitEntity method on NMS entity",
                    ThrowableProperty.of(e),
                    StringProperty.of("npc_name", npc.getData().getName())
            );
            return null;
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
        prepareCarrierEntity(npc);
    }

    public static void invalidateTrackerForTransform(Npc npc) {
        closeDummyTracker(npc);
    }

    private static void closeDummyTracker(Npc npc) {
        ModelTracker tracker = DUMMY_TRACKERS.remove(trackerKey(npc));
        if (tracker != null) {
            tracker.tracker().close();
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
        Optional<String> modelNameOpt = getModelName(npc);
        if (modelNameOpt.isEmpty() || modelNameOpt.get().equalsIgnoreCase("@none")) {
            return;
        }

        Location location = npc.getData().getLocation();
        if (location == null || location.getWorld() == null || !player.getWorld().equals(location.getWorld())) {
            return;
        }

        prepareCarrierEntity(npc);
        ModelTracker modelTracker = getOrCreateDummyTracker(npc, modelNameOpt.get());
        if (modelTracker == null) {
            return;
        }

        DummyTracker tracker = modelTracker.tracker();
        tracker.location(BukkitAdapter.adapt(location));
        modelTracker.applyVisualState(npc);

        PlatformPlayer platformPlayer = BukkitAdapter.adapt(player);
        if (forceRespawn && tracker.isSpawned(platformPlayer)) {
            tracker.remove(platformPlayer);
        }

        if (!tracker.isSpawned(platformPlayer)) {
            tracker.spawn(platformPlayer);
        }
    }

    public static void removeModelFor(Player player, Npc npc) {
        ModelTracker modelTracker = DUMMY_TRACKERS.get(trackerKey(npc));
        if (modelTracker == null) {
            return;
        }

        PlatformPlayer platformPlayer = BukkitAdapter.adapt(player);
        if (modelTracker.tracker().isSpawned(platformPlayer)) {
            modelTracker.tracker().remove(platformPlayer);
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
