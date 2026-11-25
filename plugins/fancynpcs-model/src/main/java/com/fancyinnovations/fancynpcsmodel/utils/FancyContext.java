package com.fancyinnovations.fancynpcsmodel.utils;

import com.fancyinnovations.fancynpcsmodel.config.FancyNpcsModelConfigImpl;
import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import de.oliver.fancyanalytics.logger.ExtendedFancyLogger;
import de.oliver.fancylib.translations.Translator;
import org.bukkit.command.CommandSender;

public abstract class FancyContext {

    protected final FancyNpcsModelPlugin plugin;
    protected final FancyNpcsModelConfigImpl config;
    protected final ExtendedFancyLogger logger;

    public FancyContext() {
        this.plugin = FancyNpcsModelPlugin.get();
        this.config = FancyNpcsModelPlugin.get().getFancyNpcsModelConfig();
        this.logger = FancyNpcsModelPlugin.get().getFancyLogger();
    }

    protected Translator translator() {
        return plugin.getTranslator();
    }

    protected boolean requireActive(CommandSender sender) {
        if (plugin.isModelActive()) {
            return true;
        }
        translator().translate("common.integration_inactive").withPrefix().send(sender);
        return false;
    }

}
