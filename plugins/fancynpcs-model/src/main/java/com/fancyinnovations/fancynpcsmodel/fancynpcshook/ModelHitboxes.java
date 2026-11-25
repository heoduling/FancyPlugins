package com.fancyinnovations.fancynpcsmodel.fancynpcshook;

import com.fancyinnovations.fancynpcsmodel.config.PublicNpcAccess;
import com.fancyinnovations.fancynpcsmodel.integration.NpcModelSession;
import com.fancyinnovations.fancynpcsmodel.listeners.NpcModelVisibilityListener;
import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.actions.ActionTrigger;
import de.oliver.fancynpcs.api.events.NpcPreInteractEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bone.BoneTags;
import kr.toxicity.model.api.bone.RenderedBone;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.DummyTracker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ModelHitboxes {
    private static final String NPC_IDENTITY = "NPC";
    private static final Map<UUID, ModelHitboxes> OWNERS = new ConcurrentHashMap<>();
    private static final Set<World> UNLOADING_WORLDS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public static final class ClickListener implements org.bukkit.event.Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onWorldUnload(WorldUnloadEvent event) {
            World world = event.getWorld();
            UNLOADING_WORLDS.add(world);
            for (ModelHitboxes owner : new HashSet<>(OWNERS.values())) {
                owner.detachBoxes(world).forEach(box -> removeBox(box, true));
            }
            CustomModelAttribute.closeWorldTrackers(world);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onWorldLoad(WorldLoadEvent event) {
            UNLOADING_WORLDS.remove(event.getWorld());
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void refreshIdentityBeforeProtection(PlayerInteractAtEntityEvent event) {
            if (!(event.getRightClicked() instanceof Interaction box) || !Bukkit.isOwnedByCurrentRegion(box)) return;
            ModelHitboxes owner = OWNERS.get(box.getUniqueId());
            if (owner != null) owner.updateIdentity(box);
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onRightClick(PlayerInteractAtEntityEvent event) {
            if (event.getHand() == EquipmentSlot.HAND) {
                click(event.getRightClicked().getUniqueId(), event.getPlayer(), ActionTrigger.RIGHT_CLICK);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onAttack(EntityDamageByEntityEvent event) {
            if (event.getDamager() instanceof Player player) {
                click(event.getEntity().getUniqueId(), player, ActionTrigger.LEFT_CLICK);
            }
        }
    }

    static boolean isUnloading(World world) {
        return UNLOADING_WORLDS.contains(world);
    }

    private static void click(UUID entity, Player player, ActionTrigger trigger) {
        ModelHitboxes owner = OWNERS.get(entity);
        if (owner != null && owner.canView(player) && new NpcPreInteractEvent(owner.npc, player, trigger).callEvent()) {
            owner.npc.interact(player, trigger);
        }
    }

    private final Npc npc;
    private final String publicNpcMarker;
    private final DummyTracker tracker;
    private final List<BoxSlot> slots;
    private final Set<UUID> markedBoxes = new HashSet<>();
    private volatile boolean closed;

    private static final class BoxSlot {
        private final RenderedBone bone;
        private Interaction entity;
        private Location location;
        private ScheduledTask following;
        private boolean creating;

        private BoxSlot(RenderedBone bone) { this.bone = bone; }
    }

    private record DetachedBox(Interaction entity, Location location) {}

    public ModelHitboxes(Npc npc, DummyTracker tracker) {
        this.npc = npc;
        this.publicNpcMarker = PublicNpcAccess.markerKey(npc.getData().getId());
        this.tracker = tracker;
        this.slots = tracker.getPipeline().stream()
                .filter(bone -> bone.getGroup().getHitBox() != null)
                .filter(bone -> bone.name().name().equalsIgnoreCase("hitbox") || bone.name().tagged(BoneTags.HITBOX))
                .map(BoxSlot::new).toList();
    }

    private boolean canView(Player viewer) {
        var host = NpcModelSession.activeHost();
        return !closed && host != null && !tracker.isClosed() && viewer.isOnline()
                && tracker.isSpawned(viewer.getUniqueId())
                && NpcModelVisibilityListener.canRenderFor(viewer, npc, host.getFancyNpcConfig().getVisibilityDistance());
    }

    void show(Player viewer) {
        if (!canView(viewer)) return;
        var addon = FancyNpcsModelPlugin.get();
        for (BoxSlot slot : slots) {
            Interaction entity;
            synchronized (this) {
                if (closed) return;
                if (slot.entity != null && !slot.entity.isValid()) retire(slot, slot.entity);
                entity = slot.entity;
                if (entity == null) {
                    if (slot.creating) continue;
                    slot.creating = true;
                }
            }
            if (entity != null) {
                viewer.showEntity(addon, entity);
                continue;
            }
            Location target = position(slot.bone);
            try {
                Bukkit.getRegionScheduler().run(addon, target, task -> {
                    try {
                        create(slot, target);
                    } finally {
                        finishCreation(slot);
                    }
                });
            } catch (org.bukkit.plugin.IllegalPluginAccessException disabled) {
                finishCreation(slot);
                if (addon.isEnabled()) throw disabled;
            }
        }
    }

    void hide(Player viewer) {
        var addon = FancyNpcsModelPlugin.get();
        for (Interaction entity : currentBoxes()) {
            // canSee() can dereference the box's region-owned handle even after the viewer has teleported.
            if (entity.isValid()) viewer.hideEntity(addon, entity);
        }
    }

    private synchronized List<Interaction> currentBoxes() {
        return slots.stream().map(slot -> slot.entity).filter(java.util.Objects::nonNull).toList();
    }

    private synchronized void finishCreation(BoxSlot slot) {
        slot.creating = false;
    }

    private synchronized void retire(BoxSlot slot, Interaction entity) {
        clearIdentity(entity);
        if (slot.entity == entity) {
            if (slot.following != null) slot.following.cancel();
            slot.following = null;
            slot.location = null;
            slot.entity = null;
        }
        OWNERS.remove(entity.getUniqueId(), this);
    }

    private synchronized void updateIdentity(Interaction box) {
        UUID id = box.getUniqueId();
        var model = FancyNpcsModelPlugin.get();
        if (closed || OWNERS.get(id) != this || !model.publicNpcAccess().isPublic(publicNpcMarker)) {
            clearIdentity(box);
        } else if (!markedBoxes.contains(id)) {
            box.setMetadata(NPC_IDENTITY, new FixedMetadataValue(model, true));
            markedBoxes.add(id);
        }
    }

    private synchronized void clearIdentity(Interaction box) {
        if (markedBoxes.remove(box.getUniqueId())) {
            // The core removes from its UUID-keyed store only, including on entity retirement.
            box.removeMetadata(NPC_IDENTITY, FancyNpcsModelPlugin.get());
        }
    }

    private Location position(RenderedBone bone) {
        var point = bone.hitBoxPosition();
        var bounds = bone.getGroup().getHitBox().center();
        return npc.getData().getLocation().clone().add(point.x(), point.y() + bounds.minY() * bone.hitBoxScale(), point.z());
    }

    private void create(BoxSlot slot, Location target) {
        if (closed || tracker.isClosed() || !NpcModelSession.isActive()
                || isUnloading(target.getWorld())
                || tracker.getPipeline().allPlayer().findAny().isEmpty()
                || !target.getWorld().isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) return;
        var addon = FancyNpcsModelPlugin.get();
        RenderedBone bone = slot.bone;
        var bounds = bone.getGroup().getHitBox();
        float width = (float) ((bounds.x() + bounds.z()) / 2) * bone.hitBoxScale();
        float height = (float) bounds.y() * bone.hitBoxScale();
        if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) return;
        Interaction entity = target.getWorld().spawn(target, Interaction.class, box -> {
            box.setPersistent(false);
            box.setVisibleByDefault(false);
            box.setInteractionWidth(width);
            box.setInteractionHeight(height);
        });
        synchronized (this) {
            if (closed || !addon.isEnabled()) {
                entity.remove();
                return;
            }
            slot.entity = entity;
            slot.location = target;
            OWNERS.put(entity.getUniqueId(), this);
            if (isUnloading(target.getWorld())) {
                retire(slot, entity);
                entity.remove();
                return;
            }
            slot.following = followBone(addon, entity, slot);
            if (slot.following == null) {
                retire(slot, entity);
                return;
            }
            updateIdentity(entity);
        }

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!tracker.isSpawned(BukkitAdapter.adapt(viewer))) continue;
            viewer.getScheduler().run(addon, task -> {
                if (canView(viewer) && entity.isValid() && OWNERS.get(entity.getUniqueId()) == this) {
                    viewer.showEntity(addon, entity);
                }
            }, null);
        }
    }

    private ScheduledTask followBone(Plugin addon, Interaction entity, BoxSlot slot) {
        // The tracker still owns the animation. The Bukkit box follows its translated bounds.
        var moving = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Boolean>>();
        return entity.getScheduler().runAtFixedRate(addon, task -> {
            if (closed || tracker.isClosed()) {
                clearIdentity(entity);
                task.cancel();
                return;
            }
            updateIdentity(entity);
            if (tracker.getPipeline().allPlayer().findAny().isEmpty()) return;
            var prior = moving.get();
            if (prior != null && !prior.isDone()) return;
            Location current = entity.getLocation();
            synchronized (this) {
                if (slot.entity != entity) return;
                slot.location = current;
            }
            Location next = position(slot.bone);
            if (current.getWorld().equals(next.getWorld()) && current.distanceSquared(next) > 0.0004
                    && next.getWorld().isChunkLoaded(next.getBlockX() >> 4, next.getBlockZ() >> 4)) {
                moving.set(entity.teleportAsync(next));
            }
        }, () -> retire(slot, entity), 1L, 1L);
    }

    private synchronized List<DetachedBox> detachBoxes(World world) {
        List<DetachedBox> detached = new ArrayList<>();
        for (BoxSlot slot : slots) {
            if (slot.entity != null && (world == null || slot.location.getWorld() == world)) {
                detached.add(new DetachedBox(slot.entity, slot.location));
                retire(slot, slot.entity);
            }
        }
        return detached;
    }

    private static void removeBox(DetachedBox box, boolean worldUnloading) {
        if (Bukkit.isStopping()) return;
        Interaction entity = box.entity();
        World world = box.location().getWorld();
        if (Bukkit.getWorld(world.getUID()) != world) return;
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            entity.remove();
            return;
        }
        // When the addon is already disabled, use the still-live dependency for the final owner callback.
        Plugin cleanup = (Plugin) BetterModel.platform();
        if (!cleanup.isEnabled()) return;
        if (worldUnloading) {
            // Worlds schedules its regional saves after WorldUnloadEvent; queue removal before those saves.
            Bukkit.getRegionScheduler().execute(cleanup, box.location(), () -> {
                if (Bukkit.isOwnedByCurrentRegion(entity)) entity.remove();
                else entity.getScheduler().execute(cleanup, entity::remove, null, 1L);
            });
        } else {
            entity.getScheduler().execute(cleanup, entity::remove, null, 1L);
        }
    }

    void close() {
        closed = true;
        detachBoxes(null).forEach(box -> removeBox(box, false));
    }
}
