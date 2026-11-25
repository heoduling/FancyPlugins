package com.fancyinnovations.fancynpcsmodel.listeners;

import com.fancyinnovations.fancynpcsmodel.fancynpcshook.CustomModelAttribute;
import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import com.fancyinnovations.fancynpcsmodel.integration.NpcModelSession;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.data.property.NpcVisibility;
import de.oliver.fancynpcs.api.events.NpcSpawnEvent;
import de.oliver.fancynpcs.api.events.NpcModifyEvent;
import de.oliver.fancynpcs.api.events.NpcRemoveEvent;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class NpcModelVisibilityListener implements Listener {

    private static final long NPC_SPAWN_DELAY_TICKS = 1L;
    private static final long MOVE_REFRESH_DELAY_TICKS = 1L;
    private static final long FIRST_RETRY_DELAY_TICKS = 20L;
    private static final long SECOND_RETRY_DELAY_TICKS = 60L;
    private static final long THIRD_RETRY_DELAY_TICKS = 120L;
    private static final long PLAYER_JOIN_INITIAL_SYNC_DELAY_TICKS = 40L;
    private static final long PLAYER_JOIN_LATE_SYNC_DELAY_TICKS = 100L;
    private final Map<String, Boolean> playersInModelRange = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingSpawns = new ConcurrentHashMap<>();
    private final Map<UUID, Player> pendingViewerSync = new ConcurrentHashMap<>();
    private final AtomicLong spawnTokenCounter = new AtomicLong();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNpcRemove(NpcRemoveEvent event) {
        Npc npc = event.getNpc();
        String id = "|" + npc.getData().getId();
        playersInModelRange.keySet().removeIf(key -> key.endsWith(id));
        pendingSpawns.keySet().removeIf(key -> key.contains(id + "|"));
        FancyNpcsModelPlugin.get().publicNpcAccess().forget(npc.getData().getId());
        CustomModelAttribute.forgetNpc(npc);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVisibilityChange(NpcModifyEvent event) {
        if (event.getModification() != NpcModifyEvent.NpcModification.VISIBILITY_DISTANCE
                && event.getModification() != NpcModifyEvent.NpcModification.VISIBILITY) return;
        Npc npc = event.getNpc();
        if (!CustomModelAttribute.hasAttribute(npc)) return;
        // The host fires this event before updating the NPC. Wait on the command's owner first.
        Runnable afterCommit = () -> {
            var host = NpcModelSession.activeHost();
            if (host == null || event.isCancelled() || host.getNpcManager().getNpcById(npc.getData().getId()) != npc) return;
            for (Player player : Bukkit.getOnlinePlayers()) queueViewerSync(player);
        };
        var addon = FancyNpcsModelPlugin.get();
        if (event.getModifier() instanceof Player sender) {
            if (sender.getScheduler().run(addon, task -> afterCommit.run(), afterCommit) != null) return;
        }
        Bukkit.getGlobalRegionScheduler().run(addon, task -> afterCommit.run());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNpcSpawn(NpcSpawnEvent event) {
        if (!NpcModelSession.isActive()) {
            return;
        }
        Npc npc = event.getNpc();
        if (!CustomModelAttribute.hasAttribute(npc)) {
            return;
        }

        // A new carrier spawn means this client needs the model again, even if BetterModel
        // still counts it as a viewer after a dimension reset.
        scheduleSpawnSequence(event.getPlayer(), npc, NPC_SPAWN_DELAY_TICKS, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || isSameBlock(from, to)) {
            return;
        }

        queueViewerSync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null && event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            queueViewerSync(event.getPlayer());
        }
    }

    @EventHandler
    public void onChunkSent(PlayerChunkLoadEvent event) {
        queueViewerSync(event.getPlayer());
    }

    @EventHandler
    public void onChunkUnsent(PlayerChunkUnloadEvent event) {
        queueViewerSync(event.getPlayer());
    }

    private void queueViewerSync(Player player) {
        if (!NpcModelSession.isActive()) return;
        UUID id = player.getUniqueId();
        if (pendingViewerSync.put(id, player) == player) return;
        Runnable retire = () -> pendingViewerSync.remove(id, player);
        var addon = FancyNpcsModelPlugin.get();
        try {
            if (player.getScheduler().run(addon, task -> {
                if (pendingViewerSync.remove(id, player) && player.isOnline()) syncVisibleModels(player, false);
            }, retire) == null) retire.run();
        } catch (org.bukkit.plugin.IllegalPluginAccessException disabled) {
            retire.run();
            if (addon.isEnabled()) throw disabled;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (!NpcModelSession.isActive()) return;
        Player player = event.getPlayer();
        clearViewer(player);
        CustomModelAttribute.resetViewer(player);
        scheduleVisibleModelSync(player, NPC_SPAWN_DELAY_TICKS);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        scheduleVisibleModelSync(event.getPlayer(), PLAYER_JOIN_INITIAL_SYNC_DELAY_TICKS);
        scheduleVisibleModelSync(event.getPlayer(), PLAYER_JOIN_LATE_SYNC_DELAY_TICKS);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        CustomModelAttribute.forgetInteractions(event.getPlayer());
        FancyNpcsPlugin fancyNpcs = NpcModelSession.activeHost();
        if (fancyNpcs == null) {
            return;
        }
        for (Npc npc : fancyNpcs.getNpcManager().getAllNpcs()) {
            if (CustomModelAttribute.hasAttribute(npc)) {
                CustomModelAttribute.removeModelFor(event.getPlayer(), npc);
            }
        }

        clearViewer(event.getPlayer());
    }

    private void clearViewer(Player player) {
        pendingViewerSync.remove(player.getUniqueId(), player);
        String prefix = player.getUniqueId() + "|";
        playersInModelRange.keySet().removeIf(key -> key.startsWith(prefix));
        pendingSpawns.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void scheduleOnlineModelSync(long delayTicks) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduleVisibleModelSync(player, delayTicks);
        }
    }

    public void clear() {
        pendingViewerSync.clear();
        pendingSpawns.clear();
        playersInModelRange.clear();
    }

    public void recheckPermissionViewers(FancyNpcsPlugin host) {
        var addon = FancyNpcsModelPlugin.get();
        // A permission change can despawn the carrier without a player or NPC visibility event.
        CustomModelAttribute.permissionModelViewers().forEach((id, npcs) -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null) return;
            try {
                player.getScheduler().run(addon, task -> {
                    if (NpcModelSession.activeHost() != host || !player.isOnline()) return;
                    int distance = host.getFancyNpcConfig().getVisibilityDistance();
                    for (Npc npc : npcs) {
                        if (npc.getData().getVisibility() == NpcVisibility.PERMISSION_REQUIRED
                                && host.getNpcManager().getNpcById(npc.getData().getId()) == npc
                                && !canRenderFor(player, npc, distance)) forgetView(player, npc);
                    }
                }, null);
            } catch (org.bukkit.plugin.IllegalPluginAccessException disabled) {
                if (addon.isEnabled()) throw disabled;
            }
        });
    }

    private void scheduleVisibleModelSync(Player player, long delayTicks) {
        if (!NpcModelSession.isActive()) {
            return;
        }
        player.getScheduler().runDelayed(
                FancyNpcsModelPlugin.get(),
                ignoredTask -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    syncVisibleModels(player, true);
                },
                null,
                delayTicks
        );
    }

    private void syncVisibleModels(Player player, boolean forceCheck) {
        FancyNpcsPlugin fancyNpcs = NpcModelSession.activeHost();
        if (fancyNpcs == null) {
            return;
        }
        int visibilityDistance = fancyNpcs.getFancyNpcConfig().getVisibilityDistance();
        for (Npc npc : fancyNpcs.getNpcManager().getAllNpcs()) {
            if (!CustomModelAttribute.hasAttribute(npc)) {
                continue;
            }

            String key = key(player, npc);
            if (canRenderFor(player, npc, visibilityDistance)) {
                boolean first = playersInModelRange.put(key, true) == null;
                if (first || forceCheck) scheduleSpawnSequence(player, npc, MOVE_REFRESH_DELAY_TICKS, false);
            } else {
                forgetView(player, npc);
            }
        }
    }

    private void forgetView(Player player, Npc npc) {
        if (playersInModelRange.remove(key(player, npc)) != null) removePendingSpawns(player, npc);
        CustomModelAttribute.removeModelFor(player, npc);
    }

    private void scheduleSpawnSequence(Player player, Npc npc, long firstDelayTicks, boolean replaceFirst) {
        scheduleSpawn(player, npc, firstDelayTicks, replaceFirst);
        scheduleSpawn(player, npc, FIRST_RETRY_DELAY_TICKS, false);
        scheduleSpawn(player, npc, SECOND_RETRY_DELAY_TICKS, false);
        scheduleSpawn(player, npc, THIRD_RETRY_DELAY_TICKS, false);
    }

    private void scheduleSpawn(Player player, Npc npc, long delayTicks, boolean forceRespawn) {
        if (!NpcModelSession.isActive()) {
            return;
        }
        String key = pendingKey(player, npc, delayTicks, forceRespawn);
        long token = spawnTokenCounter.incrementAndGet();
        pendingSpawns.put(key, token);

        player.getScheduler().runDelayed(
                FancyNpcsModelPlugin.get(),
                ignoredTask -> {
                    var host = NpcModelSession.activeHost();
                    if (host == null || !pendingSpawns.remove(key, token) || !player.isOnline()
                            || !canRenderFor(player, npc, host.getFancyNpcConfig().getVisibilityDistance())) {
                        return;
                    }

                    playersInModelRange.put(key(player, npc), true);
                    CustomModelAttribute.spawnModelFor(player, npc, forceRespawn);
                },
                null,
                delayTicks
        );
    }

    private void removePendingSpawns(Player player, Npc npc) {
        String prefix = key(player, npc) + "|";
        pendingSpawns.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public static boolean canRenderFor(Player player, Npc npc, int defaultVisibilityDistance) {
        Location npcLocation = npc.getData().getLocation();
        if (npcLocation == null || !npc.getData().isSpawnEntity() || !npc.isShownFor(player)
                || !npc.getData().getVisibility().canSee(player, npc)) {
            return false;
        }

        if (!player.getWorld().equals(npcLocation.getWorld())) {
            return false;
        }

        int visibilityDistance = npc.getData().getVisibilityDistance();
        if (visibilityDistance < 0) {
            visibilityDistance = defaultVisibilityDistance;
        }

        if (visibilityDistance == 0) {
            return false;
        }

        if (visibilityDistance == Integer.MAX_VALUE) return player.isChunkSent(Chunk.getChunkKey(npcLocation));
        return npcLocation.distanceSquared(player.getLocation()) <= (double) visibilityDistance * visibilityDistance;
    }

    private boolean isSameBlock(Location from, Location to) {
        return from.getWorld().equals(to.getWorld())
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }

    private String key(Player player, Npc npc) {
        UUID playerId = player.getUniqueId();
        return playerId + "|" + npc.getData().getId();
    }

    private String pendingKey(Player player, Npc npc, long delayTicks, boolean forceRespawn) {
        return key(player, npc) + "|" + delayTicks + "|" + forceRespawn;
    }
}
