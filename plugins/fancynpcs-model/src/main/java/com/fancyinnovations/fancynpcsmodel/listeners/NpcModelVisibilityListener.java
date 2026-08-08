package com.fancyinnovations.fancynpcsmodel.listeners;

import com.fancyinnovations.fancynpcsmodel.fancynpcshook.CustomModelAttribute;
import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.events.NpcSpawnEvent;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
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
    private static final double ALWAYS_VISIBLE_REFRESH_DISTANCE = 96.0D;

    private final Map<String, Boolean> playersInModelRange = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingSpawns = new ConcurrentHashMap<>();
    private final AtomicLong spawnTokenCounter = new AtomicLong();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNpcSpawn(NpcSpawnEvent event) {
        Npc npc = event.getNpc();
        if (!CustomModelAttribute.hasAttribute(npc)) {
            return;
        }

        playersInModelRange.put(key(event.getPlayer(), npc), true);
        scheduleSpawnSequence(event.getPlayer(), npc, NPC_SPAWN_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || isSameBlock(from, to)) {
            return;
        }

        updatePlayerModelRange(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        updatePlayerModelRange(event.getPlayer());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        scheduleVisibleModelSync(event.getPlayer(), PLAYER_JOIN_INITIAL_SYNC_DELAY_TICKS);
        scheduleVisibleModelSync(event.getPlayer(), PLAYER_JOIN_LATE_SYNC_DELAY_TICKS);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        for (Npc npc : FancyNpcsPlugin.get().getNpcManager().getAllNpcs()) {
            if (CustomModelAttribute.hasAttribute(npc)) {
                CustomModelAttribute.removeModelFor(event.getPlayer(), npc);
            }
        }

        String prefix = event.getPlayer().getUniqueId() + "|";
        playersInModelRange.keySet().removeIf(key -> key.startsWith(prefix));
        pendingSpawns.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void scheduleOnlineModelSync(long delayTicks) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduleVisibleModelSync(player, delayTicks);
        }
    }

    private void scheduleVisibleModelSync(Player player, long delayTicks) {
        player.getScheduler().runDelayed(
                FancyNpcsModelPlugin.get(),
                ignoredTask -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    syncVisibleModels(player);
                },
                null,
                delayTicks
        );
    }

    private void syncVisibleModels(Player player) {
        for (Npc npc : FancyNpcsPlugin.get().getNpcManager().getAllNpcs()) {
            if (!CustomModelAttribute.hasAttribute(npc)) {
                continue;
            }

            boolean inRange = isInModelRefreshRange(player, npc);
            boolean shownForPlayer = npc.isShownFor(player);

            if (!inRange) {
                continue;
            }

            playersInModelRange.put(key(player, npc), true);
            if (shownForPlayer) {
                scheduleSpawnSequence(player, npc, MOVE_REFRESH_DELAY_TICKS);
            }
        }
    }

    private void updatePlayerModelRange(Player player) {
        for (Npc npc : FancyNpcsPlugin.get().getNpcManager().getAllNpcs()) {
            if (!CustomModelAttribute.hasAttribute(npc)) {
                continue;
            }

            boolean inRange = isInModelRefreshRange(player, npc);
            String key = key(player, npc);
            boolean wasInRange = Boolean.TRUE.equals(playersInModelRange.put(key, inRange));
            if (inRange && !wasInRange && npc.isShownFor(player)) {
                scheduleSpawnSequence(player, npc, MOVE_REFRESH_DELAY_TICKS);
            } else if (!inRange && wasInRange) {
                removePendingSpawns(player, npc);
                CustomModelAttribute.removeModelFor(player, npc);
            }
        }
    }

    private void scheduleSpawnSequence(Player player, Npc npc, long firstDelayTicks) {
        scheduleSpawn(player, npc, firstDelayTicks, false);
        scheduleSpawn(player, npc, FIRST_RETRY_DELAY_TICKS, false);
        scheduleSpawn(player, npc, SECOND_RETRY_DELAY_TICKS, false);
        scheduleSpawn(player, npc, THIRD_RETRY_DELAY_TICKS, false);
    }

    private void scheduleSpawn(Player player, Npc npc, long delayTicks, boolean forceRespawn) {
        String key = pendingKey(player, npc, delayTicks, forceRespawn);
        long token = spawnTokenCounter.incrementAndGet();
        pendingSpawns.put(key, token);

        player.getScheduler().runDelayed(
                FancyNpcsModelPlugin.get(),
                ignoredTask -> {
                    if (!pendingSpawns.remove(key, token) || !player.isOnline()) {
                        return;
                    }

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

    private boolean isInModelRefreshRange(Player player, Npc npc) {
        Location npcLocation = npc.getData().getLocation();
        if (npcLocation == null || !npc.getData().isSpawnEntity()) {
            return false;
        }

        if (!player.getWorld().equals(npcLocation.getWorld())) {
            return false;
        }

        int visibilityDistance = npc.getData().getVisibilityDistance();
        if (visibilityDistance < 0) {
            visibilityDistance = FancyNpcsPlugin.get().getFancyNpcConfig().getVisibilityDistance();
        }

        if (visibilityDistance == 0) {
            return false;
        }

        double refreshDistance = visibilityDistance == Integer.MAX_VALUE
                ? ALWAYS_VISIBLE_REFRESH_DISTANCE
                : visibilityDistance;
        return npcLocation.distanceSquared(player.getLocation()) <= refreshDistance * refreshDistance;
    }

    private boolean isSameBlock(Location from, Location to) {
        return from.getWorld().equals(to.getWorld())
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }

    private String key(Player player, Npc npc) {
        UUID playerId = player.getUniqueId();
        return playerId + "|" + npc.getData().getName();
    }

    private String pendingKey(Player player, Npc npc, long delayTicks, boolean forceRespawn) {
        return key(player, npc) + "|" + delayTicks + "|" + forceRespawn;
    }
}
