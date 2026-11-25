package de.oliver.fancynpcs.listeners;

import de.oliver.fancynpcs.FancyNpcs;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerQuitListener implements Listener {

    /**
     * Removes the player UUID from the Npcs states, except last interaction.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        FancyNpcs.getInstance().getVisibilityTracker().quit(event.getPlayer());
    }
}
