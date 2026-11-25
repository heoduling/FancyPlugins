package de.oliver.fancynpcs;

import de.oliver.fancynpcs.v26_2.NpcClientState;
import de.oliver.fancynpcs.v26_2.PaperLaunchRegistration;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;

public final class HotUnload implements Listener {
    private static volatile boolean stopping;
    private final FancyNpcs plugin;

    HotUnload(FancyNpcs plugin) {
        this.plugin = plugin;
        stopping = false;
        NpcClientState.startTracking();
    }

    public static boolean isBatchingCommandRemoval() {
        return stopping;
    }

    public void prepare(CommandSender sender) {
        plugin.getTranslator().translate("fancynpcs_unload_preparation_not_required").withPrefix().send(sender);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) {
            stopping = true;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        NpcClientState.forget(event.getPlayer());
    }

    void close() {
        stopping = true;
        boolean shutdown = Bukkit.isStopping();
        NpcClientState.close(!shutdown);
        if (!shutdown) {
            PaperLaunchRegistration.detachOnGlobal(plugin.getPluginMeta(), plugin.getLogger());
        }
    }
}
