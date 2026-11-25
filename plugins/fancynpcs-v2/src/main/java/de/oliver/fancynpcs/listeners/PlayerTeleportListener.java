package de.oliver.fancynpcs.listeners;

import de.oliver.fancynpcs.FancyNpcs;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

public class PlayerTeleportListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(@NotNull final PlayerTeleportEvent event) {
        FancyNpcs.getInstance().getVisibilityTracker().refresh(event.getPlayer());
    }

}
