package de.oliver.fancynpcs.tracker;

import de.oliver.fancynpcs.FancyNpcs;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.events.NpcStopLookingEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class VisibilityTracker implements Runnable {
    private final FancyNpcs plugin;
    private final TurnToPlayerTracker turning;
    private final ConcurrentHashMap<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private long nextVisibilityPass = System.nanoTime();
    private long nextCooldownSweep = nextVisibilityPass;

    public VisibilityTracker(FancyNpcs plugin) {
        this.plugin = plugin;
        this.turning = new TurnToPlayerTracker(plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            viewers.putIfAbsent(player.getUniqueId(), new Viewer(player, 0));
        }
    }

    @Override
    public void run() {
        var npcs = List.copyOf(plugin.getNpcManagerImpl().getAllNpcs());
        long now = System.nanoTime();
        boolean visibilityPass = now >= nextVisibilityPass;
        if (visibilityPass) {
            nextVisibilityPass = now + TimeUnit.MILLISECONDS.toNanos(
                    Math.max(1, plugin.getFancyNpcConfig().getNpcUpdateVisibilityInterval()) * 50L);
        }
        if (now >= nextCooldownSweep) {
            nextCooldownSweep = now + TimeUnit.SECONDS.toNanos(1);
            long time = System.currentTimeMillis();
            for (Npc npc : npcs) {
                var cooldowns = npc.getLastPlayerInteraction();
                if (cooldowns.isEmpty()) continue;
                long duration = (long) (npc.getData().getInteractionCooldown() * 1000);
                cooldowns.forEach((id, stamp) -> {
                    if (duration <= 0 || time - stamp >= duration) cooldowns.remove(id, stamp);
                });
            }
        }
        if (npcs.isEmpty()) return;
        viewers.forEach((id, viewer) -> {
            if (now < viewer.readyAt) return;
            if (visibilityPass) viewer.refresh.set(true);
            if (!viewer.pending.compareAndSet(false, true)) return;
            Npc.runOnViewerOwner(plugin, viewer.player, () -> sample(id, viewer, npcs), () -> viewer.pending.set(false));
        });
    }

    private void sample(UUID id, Viewer viewer, List<Npc> npcs) {
        boolean submitted = false;
        try {
            if (viewers.get(id) != viewer) return;
            Player player = viewer.player;
            var location = player.getLocation();
            double eyeHeight = player.getEyeHeight();
            boolean[] visible = viewer.refresh.getAndSet(false) ? new boolean[npcs.size()] : null;
            if (visible != null) {
                for (int i = 0; i < npcs.size(); i++) {
                    visible[i] = npcs.get(i).shouldBeVisible(player, location);
                }
            }
            submitted = submit(() -> {
                try {
                    if (viewers.get(id) != viewer) return;
                    for (int i = 0; i < npcs.size(); i++) {
                        Npc npc = npcs.get(i);
                        if (plugin.getNpcManagerImpl().getNpcById(npc.getData().getId()) != npc) continue;
                        if (visible != null) npc.applyVisibility(player, location.getWorld(), visible[i]);
                        turning.update(npc, player, id, location, eyeHeight);
                    }
                } finally {
                    viewer.pending.set(false);
                }
            });
        } finally {
            if (!submitted) viewer.pending.set(false);
        }
    }

    public void join(Player player) {
        viewers.put(player.getUniqueId(), new Viewer(player, TimeUnit.SECONDS.toNanos(2)));
    }

    public void refresh(Player player) {
        Viewer viewer = viewers.get(player.getUniqueId());
        if (viewer != null && viewer.player == player) viewer.refresh.set(true);
    }

    public void ready(Player player) {
        Viewer viewer = viewers.get(player.getUniqueId());
        if (viewer != null && viewer.player == player) {
            viewer.readyAt = System.nanoTime();
            viewer.refresh.set(true);
        }
    }

    public void quit(Player player) {
        UUID id = player.getUniqueId();
        viewers.computeIfPresent(id, (key, viewer) -> viewer.player == player ? null : viewer);
        // The same worker applies snapshots and clears departed viewers. A queued old snapshot cannot restore them.
        submit(() -> {
            for (Npc npc : plugin.getNpcManagerImpl().getAllNpcs()) {
                if (npc.forgetViewer(id)) {
                    plugin.getScheduler().runTask(null, () -> new NpcStopLookingEvent(npc, player).callEvent());
                }
            }
        });
    }

    private boolean submit(Runnable task) {
        var worker = plugin.getNpcThread();
        if (worker.isShutdown()) return false;
        try {
            worker.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            if (!worker.isShutdown()) throw rejected;
            return false;
        }
    }

    private static final class Viewer {
        private final Player player;
        private volatile long readyAt;
        private final AtomicBoolean pending = new AtomicBoolean();
        private final AtomicBoolean refresh = new AtomicBoolean(true);

        private Viewer(Player player, long delay) {
            this.player = player;
            this.readyAt = System.nanoTime() + delay;
        }
    }
}
