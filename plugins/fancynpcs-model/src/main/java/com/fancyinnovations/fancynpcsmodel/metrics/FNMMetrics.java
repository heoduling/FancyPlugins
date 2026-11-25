package com.fancyinnovations.fancynpcsmodel.metrics;

import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import de.oliver.fancyanalytics.api.FancyAnalyticsAPI;
import de.oliver.fancyanalytics.api.metrics.MetricSupplier;
import de.oliver.fancyanalytics.logger.ExtendedFancyLogger;
import de.oliver.fancyanalytics.sdk.events.Event;
import de.oliver.fancylib.VersionConfig;
import org.bukkit.Bukkit;

import java.util.HashMap;
import java.util.logging.Logger;

public class FNMMetrics {

    private final ExtendedFancyLogger logger;
    private FancyAnalyticsAPI fancyAnalytics;

    public FNMMetrics() {
        logger = FancyNpcsModelPlugin.get().getFancyLogger();
    }

    public void register() {
        fancyAnalytics = new FancyAnalyticsAPI("ddb636b6-25b5-4d44-abfc-3bb0dabd2b97", "-J4fYDk1Y2RhZDI3ZWRmZDRkYmT0usLA");
        fancyAnalytics.getConfig().setDisableLogging(true);

        fancyAnalytics.registerMinecraftPluginMetrics(FancyNpcsModelPlugin.get());
        fancyAnalytics.getExceptionHandler().registerLogger(FancyNpcsModelPlugin.get().getLogger());
        fancyAnalytics.getExceptionHandler().registerLogger(Bukkit.getLogger());
        fancyAnalytics.getExceptionHandler().registerLogger(logger);

        fancyAnalytics.registerStringMetric(new MetricSupplier<>("commit_hash", () -> FancyNpcsModelPlugin.get().getVersionConfig().getCommitHash().substring(0, 7)));

        fancyAnalytics.registerStringMetric(new MetricSupplier<>("server_size", () -> {
            long onlinePlayers = Bukkit.getOnlinePlayers().size();

            if (onlinePlayers == 0) {
                return "empty";
            }

            if (onlinePlayers <= 25) {
                return "small";
            }

            if (onlinePlayers <= 100) {
                return "medium";
            }

            if (onlinePlayers <= 500) {
                return "large";
            }

            return "very_large";
        }));

        fancyAnalytics.registerNumberMetric(new MetricSupplier<>("amount_npcs_with_custom_model",
                () -> (double) FancyNpcsModelPlugin.get().modelCount()));

        fancyAnalytics.initialize();
    }

    public void detachErrorLogging() {
        if (fancyAnalytics == null) {
            return;
        }

        fancyAnalytics.getConfig().sendErrors(false);
        fancyAnalytics.getConfig().setSendMetrics(false);
        var handler = fancyAnalytics.getExceptionHandler();
        FancyNpcsModelPlugin.get().getLogger().removeHandler(handler);
        Bukkit.getLogger().removeHandler(handler);
        Logger.getGlobal().removeHandler(handler);
    }

    public void reportPluginVersionUpdated(String lastVersion) {
        VersionConfig versionConfig = FancyNpcsModelPlugin.get().getVersionConfig();
        String currentVersion = versionConfig.getVersion();
        logger.info("Plugin has been updated from version " + lastVersion + " to " + currentVersion + ".");
        fancyAnalytics.sendEvent(
                new Event("PluginVersionUpdated", new HashMap<>())
                        .withProperty("from", lastVersion)
                        .withProperty("to", currentVersion)
                        .withProperty("commit_hash", versionConfig.getCommitHash())
                        .withProperty("channel", versionConfig.getChannel())
                        .withProperty("platform", versionConfig.getPlatform())
        );
    }

    public FancyAnalyticsAPI getFancyAnalytics() {
        return fancyAnalytics;
    }

}
