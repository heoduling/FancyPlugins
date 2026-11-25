package de.oliver.fancynpcs.commands;

import de.oliver.fancylib.translations.Translator;
import de.oliver.fancylib.translations.message.SimpleMessage;
import de.oliver.fancynpcs.FancyNpcs;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;
import org.jetbrains.annotations.NotNull;

public final class FancyNpcsCMD {

    public static final FancyNpcsCMD INSTANCE = new FancyNpcsCMD();

    private final FancyNpcs plugin = FancyNpcs.getInstance();
    private final Translator translator = FancyNpcs.getInstance().getTranslator();

    private FancyNpcsCMD() {
    }

    @Command("fancynpcs version")
    @Permission("fancynpcs.command.fancynpcs.version")
    public void onVersion(final CommandSender sender) {
        plugin.sendVersionInfo(sender, false);
    }

    @Command("fancynpcs reload")
    @Permission("fancynpcs.command.fancynpcs.reload")
    public void onReload(final CommandSender sender) {
        plugin.getFancyNpcConfig().reload();
        plugin.loadTranslations();
        // Reloading all NPCs.
        // NOTE: This sometimes creates duplicated NPCs on the client-side.
        if (plugin.getNpcManagerImpl().reloadFromStorage()) {
            translator.translate("fancynpcs_reload_success").withPrefix().send(sender);
        } else {
            translator.translate("fancynpcs_reload_failure").withPrefix().send(sender);
        }
    }

    @Command("fancynpcs prepare_unload")
    @Permission("fancynpcs.command.fancynpcs.reload")
    public void onPrepareUnload(final CommandSender sender) {
        var hotUnload = plugin.getHotUnload();
        if (hotUnload == null) {
            translator.translate("fancynpcs_unload_preparation_unsupported").withPrefix().send(sender);
            return;
        }
        hotUnload.prepare(sender);
    }

    @Command("fancynpcs save")
    @Permission("fancynpcs.command.fancynpcs.save")
    public void onSave(final CommandSender sender) {
        var manager = plugin.getNpcManagerImpl();
        var saved = manager.saveNpcsAsync(true);
        if (saved == null) {
            translator.translate("fancynpcs_save_unavailable").withPrefix().send(sender);
            return;
        }
        manager.replyWhenStored(sender, saved,
                () -> translator.translate("fancynpcs_save_success").withPrefix().send(sender),
                () -> translator.translate("fancynpcs_save_failure").withPrefix().send(sender));
    }

    // NOTE: In the future, if there is more than a few feature flags, we might consider listing entries automatically by iterating, just like in 'list' sub-command.
    @Command("fancynpcs feature_flags")
    @Permission("fancynpcs.command.fancynpcs.feature_flags")
    public void onFeatureFlags(final CommandSender sender) {
        translator.translate("fancynpcs_feature_flags_header").send(sender);
        translator.translate("fancynpcs_feature_flags_entry")
                .replace("number", "1")
                .replace("name", translatedText("fancynpcs_feature_player_npcs"))
                .replace("id", FancyNpcs.PLAYER_NPCS_FEATURE_FLAG.getName())
                .replace("state", getTranslatedState(FancyNpcs.PLAYER_NPCS_FEATURE_FLAG.isEnabled()))
                .send(sender);
        translator.translate("fancynpcs_feature_flags_entry")
                .replace("number", "2")
                .replace("name", translatedText("fancynpcs_feature_native_threads"))
                .replace("id", FancyNpcs.USE_NATIVE_THREADS_FEATURE_FLAG.getName())
                .replace("state", getTranslatedState(FancyNpcs.USE_NATIVE_THREADS_FEATURE_FLAG.isEnabled()))
                .send(sender);
        translator.translate("fancynpcs_feature_flags_entry")
                .replace("number", "3")
                .replace("name", translatedText("fancynpcs_feature_debug_mode"))
                .replace("id", FancyNpcs.ENABLE_DEBUG_MODE_FEATURE_FLAG.getName())
                .replace("state", getTranslatedState(FancyNpcs.ENABLE_DEBUG_MODE_FEATURE_FLAG.isEnabled()))
                .send(sender);
        translator.translate("fancynpcs_feature_flags_footer")
                .replace("count", "2")
                .replace("count_formatted", "· · 3")
                .replace("total", String.valueOf(FancyNpcs.getInstance().getNpcManager().getAllNpcs().size()))
                .replace("total_formatted", "· · 3")
                .send(sender);
    }

    // NOTE: Might need to be improved later down the line, should get work done for now.
    private @NotNull String getTranslatedState(final boolean bool) {
        return translatedText(bool ? "enabled" : "disabled");
    }

    private String translatedText(String key) {
        return ((SimpleMessage) translator.translate(key)).getMessage();
    }
}
