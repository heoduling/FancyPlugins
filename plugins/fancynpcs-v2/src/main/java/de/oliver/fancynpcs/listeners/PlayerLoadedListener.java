package de.oliver.fancynpcs.listeners;

import de.oliver.fancynpcs.FancyNpcs;
import io.papermc.paper.event.player.PlayerClientLoadedWorldEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

public class PlayerLoadedListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLoaded(@NotNull final PlayerClientLoadedWorldEvent event) {
        FancyNpcs.getInstance().getVisibilityTracker().ready(event.getPlayer());
    }

}
