package com.fancyinnovations.fancynpcsmodel.main;

import org.bukkit.Bukkit;

import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ModelProviderRelease {
    private ModelProviderRelease() {}

    static void release(FancyNpcsModelPlugin plugin) {
        if (Bukkit.isStopping() || !Bukkit.getMinecraftVersion().equals("26.2")) return;
        var meta = plugin.getPluginMeta();
        Logger logger = plugin.getLogger();
        ClassLoader serverLoader = Bukkit.getServer().getClass().getClassLoader();
        Runnable cleanup = () -> detach(meta, logger, serverLoader);
        if (Bukkit.isGlobalTickThread()) {
            cleanup.run();
            return;
        }
        try {
            // Match 26.2's main-plugin cleanup. This mailbox outlives a disabled addon.
            Class<?> regionized = serverLoader.loadClass("io.papermc.paper.threadedregions.RegionizedServer");
            Object server = regionized.getMethod("getInstance").invoke(null);
            regionized.getMethod("addTask", Runnable.class).invoke(server, cleanup);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not queue model startup provider cleanup", e);
        }
    }

    private static void detach(Object meta, Logger logger, ClassLoader serverLoader) {
        try {
            Class<?> entrypointClass = serverLoader.loadClass("io.papermc.paper.plugin.entrypoint.Entrypoint");
            Object pluginEntry = entrypointClass.getField("PLUGIN").get(null);
            Class<?> handlerClass = serverLoader.loadClass("io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler");
            Object handler = handlerClass.getField("INSTANCE").get(null);
            if (!Boolean.TRUE.equals(handlerClass.getMethod("hasEntered", entrypointClass).invoke(handler, pluginEntry))) return;
            Object storage = handlerClass.getMethod("get", entrypointClass).invoke(handler, pluginEntry);
            var providers = (Collection<?>) storage.getClass().getMethod("getRegisteredProviders").invoke(storage);
            Class<?> providerClass = serverLoader.loadClass("io.papermc.paper.plugin.provider.PluginProvider");
            var getMeta = providerClass.getMethod("getMeta");
            for (var iterator = providers.iterator(); iterator.hasNext();) {
                if (getMeta.invoke(iterator.next()) == meta) {
                    iterator.remove();
                    logger.info("Released this FancyNpcsModel startup provider.");
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not release the FancyNpcsModel startup provider", e);
        }
    }
}
