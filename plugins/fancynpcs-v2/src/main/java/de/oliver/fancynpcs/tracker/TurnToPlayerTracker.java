package de.oliver.fancynpcs.tracker;

import de.oliver.fancynpcs.FancyNpcs;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.events.NpcStartLookingEvent;
import de.oliver.fancynpcs.api.events.NpcStopLookingEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

final class TurnToPlayerTracker {
    private final FancyNpcs plugin;

    TurnToPlayerTracker(FancyNpcs plugin) {
        this.plugin = plugin;
    }

    void update(Npc npc, Player player, UUID id, Location viewerLocation, double eyeHeight) {
        var data = npc.getData();
        var npcLocation = data.getLocation();
        boolean shownHere = npc.isShownFor(player) && npcLocation != null
                && npcLocation.getWorld() == viewerLocation.getWorld();
        int configuredDistance = data.getTurnToPlayerDistance();
        int distance = configuredDistance == -1 ? plugin.getFancyNpcConfig().getTurnToPlayerDistance() : configuredDistance;
        if (shownHere && data.isTurnToPlayer() && distance > 0
                && npcLocation.distanceSquared(viewerLocation) < (double) distance * distance) {
            Location direction = viewerLocation.clone().add(0, eyeHeight, 0)
                    .subtract(npcLocation.getX(), npcLocation.getY() + getEntityEyeHeight(data.getType()) * data.getScale(), npcLocation.getZ());
            direction.setDirection(direction.toVector());
            npc.lookAt(player, direction);
            if (!Boolean.TRUE.equals(npc.getIsLookingAtPlayer().put(id, true))) {
                plugin.getScheduler().runTask(null, () -> new NpcStartLookingEvent(npc, player).callEvent());
            }
        } else if (Boolean.TRUE.equals(npc.getIsLookingAtPlayer().remove(id))) {
            if (shownHere && plugin.getFancyNpcConfig().isTurnToPlayerResetToInitialDirection()) {
                npc.move(player, false);
            }
            plugin.getScheduler().runTask(null, () -> new NpcStopLookingEvent(npc, player).callEvent());
        }
    }

    /**
     * Gets the base eye height for different entity types.
     * Based on Minecraft's default entity eye heights.
     *
     * @param type The entity type
     * @return The base eye height in blocks
     */
    private double getEntityEyeHeight(org.bukkit.entity.EntityType type) {
        return switch (type) {
            case PLAYER -> 1.62;
            case ZOMBIE, SKELETON, STRAY, HUSK, DROWNED, WITHER_SKELETON -> 1.74;
            case CREEPER -> 1.7;
            case ENDERMAN -> 2.55;
            case SPIDER, CAVE_SPIDER -> 0.5;
            case PIG -> 0.6;
            case SHEEP -> 0.65;
            case COW, MOOSHROOM -> 1.3;
            case CHICKEN -> 0.4;
            case HORSE, DONKEY, MULE -> 1.52;
            case VILLAGER, ZOMBIE_VILLAGER -> 1.62;
            case IRON_GOLEM -> 2.7;
            case WOLF -> 0.68;
            case CAT, OCELOT -> 0.35;
            case RABBIT -> 0.3;
            case BAT -> 0.45;
            case SQUID, GLOW_SQUID -> 0.4;
            case SILVERFISH -> 0.13;
            case ENDERMITE -> 0.13;
            case BLAZE -> 1.7;
            case GHAST -> 2.0;
            case SLIME, MAGMA_CUBE -> 0.5;
            case WITCH -> 1.62;
            case EVOKER, VINDICATOR, ILLUSIONER, PILLAGER -> 1.62;
            case VEX -> 0.8;
            case GUARDIAN, ELDER_GUARDIAN -> 0.425;
            case SHULKER -> 0.5;
            case PHANTOM -> 0.5;
            case BEE -> 0.3;
            case FOX -> 0.4;
            case PANDA -> 1.13;
            case STRIDER -> 1.7;
            case HOGLIN, ZOGLIN -> 1.4;
            case PIGLIN, PIGLIN_BRUTE, ZOMBIFIED_PIGLIN -> 1.62;
            case AXOLOTL -> 0.3;
            case GOAT -> 0.9;
            case ALLAY -> 0.6;
            case FROG -> 0.25;
            case TADPOLE -> 0.13;
            case WARDEN -> 2.5;
            case CAMEL -> 2.275;
            case SNIFFER -> 1.0;
            case BREEZE -> 1.4;
            case ARMADILLO -> 0.26;
            case BOGGED -> 1.74;
            case ARMOR_STAND -> 1.975;
            case TEXT_DISPLAY, ITEM_DISPLAY, BLOCK_DISPLAY, INTERACTION -> 0.5;
            default -> 1.62;
        };
    }
}
