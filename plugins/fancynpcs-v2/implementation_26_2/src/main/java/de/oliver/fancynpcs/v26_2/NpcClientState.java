package de.oliver.fancynpcs.v26_2;

import io.papermc.paper.threadedregions.EntityScheduler;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Each connection is tracked on its owning region. A retired session can only emit its final removal. */
public final class NpcClientState {
    private static final ConcurrentMap<UUID, NpcClientState> VIEWERS = new ConcurrentHashMap<>();
    private static volatile boolean tracking;
    private static volatile boolean retired;
    private final EntityScheduler scheduler;
    private final Set<Integer> entities = new HashSet<>();
    private final Set<UUID> profiles = new HashSet<>();
    private final Set<String> teams = new HashSet<>();
    private boolean closed;

    private NpcClientState(EntityScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public static void startTracking() {
        synchronized (VIEWERS) {
            retired = false;
            tracking = true;
        }
    }

    static void send(ServerPlayer player, Packet<?> packet) {
        if (!tracking) {
            if (!retired) player.connection.send(packet);
            return;
        }
        UUID id = player.getUUID();
        var owner = player.getBukkitEntity().taskScheduler;
        var state = VIEWERS.get(id);
        if (state == null || state.scheduler != owner) {
            synchronized (VIEWERS) {
                if (!tracking) return;
                state = VIEWERS.get(id);
                if (state == null || state.scheduler != owner) {
                    state = new NpcClientState(owner);
                    VIEWERS.put(id, state);
                }
            }
        }
        state.sendTracked(player, packet);
    }

    private synchronized void sendTracked(ServerPlayer player, Packet<?> packet) {
        if (closed || retired) return;
        player.connection.send(packet);
        record(packet);
    }

    private void record(Packet<?> packet) {
        switch (packet) {
            case ClientboundBundlePacket bundle -> bundle.subPackets().forEach(this::record);
            case ClientboundAddEntityPacket add -> entities.add(add.getId());
            case ClientboundRemoveEntitiesPacket remove -> entities.removeAll(remove.getEntityIds());
            case ClientboundPlayerInfoUpdatePacket update -> update.newEntries().forEach(entry -> profiles.add(entry.profileId()));
            case ClientboundPlayerInfoRemovePacket remove -> profiles.removeAll(remove.profileIds());
            case ClientboundSetPlayerTeamPacket team -> {
                var action = team.getTeamAction();
                if (action == ClientboundSetPlayerTeamPacket.Action.ADD) {
                    teams.add(team.getName());
                } else if (action == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
                    teams.remove(team.getName());
                }
            }
            default -> { }
        }
    }

    public static void forget(Player player) {
        var state = VIEWERS.get(player.getUniqueId());
        if (state != null && state.scheduler == ((CraftPlayer) player).taskScheduler) {
            VIEWERS.remove(player.getUniqueId(), state);
        }
    }

    public static void close(boolean sendCleanup) {
        List<NpcClientState> snapshot;
        synchronized (VIEWERS) {
            retired = true;
            tracking = false;
            snapshot = List.copyOf(VIEWERS.values());
            VIEWERS.clear();
        }
        if (sendCleanup) {
            snapshot.forEach(NpcClientState::finish);
        }
    }

    private synchronized void finish() {
        closed = true;
        if (entities.isEmpty() && profiles.isEmpty() && teams.isEmpty()) return;
        var removeEntities = entities.isEmpty() ? null :
                new ClientboundRemoveEntitiesPacket(entities.stream().mapToInt(Integer::intValue).toArray());
        var removeProfiles = profiles.isEmpty() ? null : new ClientboundPlayerInfoRemovePacket(new ArrayList<>(profiles));
        var removeTeams = new ArrayList<ClientboundSetPlayerTeamPacket>(teams.size());
        if (!teams.isEmpty()) {
            var scoreboard = new Scoreboard();
            for (String team : teams) {
                removeTeams.add(ClientboundSetPlayerTeamPacket.createRemovePacket(new PlayerTeam(scoreboard, team)));
            }
        }
        // This core-owned entity mailbox survives JavaPlugin disable. A retired player's connection needs no cleanup.
        scheduler.schedule(entity -> {
            var connection = ((ServerPlayer)entity).connection;
            if (removeEntities != null) connection.send(removeEntities);
            if (removeProfiles != null) connection.send(removeProfiles);
            removeTeams.forEach(connection::send);
        }, null, 1L);
    }
}
