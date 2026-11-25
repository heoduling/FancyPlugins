package de.oliver.fancynpcs.v26_2;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.entrypoint.Entrypoint;
import io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler;
import org.bukkit.Bukkit;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class PaperLaunchRegistration {
    private PaperLaunchRegistration() {
    }

    public static void detachOnGlobal(PluginMeta meta, Logger logger) {
        Runnable release = () -> {
            try {
                if (detach(meta)) {
                    logger.info("Released the retired FancyNpcs startup provider.");
                }
            } catch (LinkageError | RuntimeException failure) {
                logger.log(Level.WARNING, "Could not release the FancyNpcs startup provider", failure);
            }
        };
        if (Bukkit.isGlobalTickThread()) {
            release.run();
            return;
        }
        try {
            // This Folia-only mailbox is absent from the Paper compile bundle and survives plugin disable.
            Class<?> server = Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false,
                    LaunchEntryPointHandler.class.getClassLoader());
            Object global = server.getMethod("getInstance").invoke(null);
            server.getMethod("addTask", Runnable.class).invoke(global, release);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            logger.log(Level.WARNING, "Could not schedule release of the FancyNpcs startup provider", failure);
        }
    }

    /** The 26.2 provider list is live. Only detach this instance's completed launch registration. */
    public static boolean detach(PluginMeta meta) {
        var launcher = LaunchEntryPointHandler.INSTANCE;
        if (!launcher.hasEntered(Entrypoint.PLUGIN)) {
            return false;
        }
        var providers = launcher.get(Entrypoint.PLUGIN).getRegisteredProviders().iterator();
        while (providers.hasNext()) {
            if (providers.next().getMeta() == meta) {
                providers.remove();
                return true;
            }
        }
        return false;
    }
}
