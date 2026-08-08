package de.oliver.fancynpcs.commands.npc;

import de.oliver.fancylib.translations.Translator;
import de.oliver.fancynpcs.FancyNpcs;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.events.NpcModifyEvent;
import de.oliver.fancynpcs.api.skins.SkinData;
import de.oliver.fancynpcs.api.skins.SkinLoadException;
import de.oliver.fancynpcs.skins.SkinUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Flag;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public enum SkinCMD {
    INSTANCE; // SINGLETON

    private final Translator translator = FancyNpcs.getInstance().getTranslator();
    private final Map<String, UUID> pendingRequests = new ConcurrentHashMap<>();

    /* PARSERS AND SUGGESTIONS */

    @Command("npc skin <npc> <skin>")
    @Permission("fancynpcs.command.npc.skin")
    public void onSkin(
            final @NotNull CommandSender sender,
            final @NotNull Npc npc,
            final @NotNull @Argument(suggestions = "SkinCMD/skin") String skin,
            final @Flag("slim") boolean slim
    ) {
        if (npc.getData().getType() != EntityType.PLAYER) {
            translator.translate("command_unsupported_npc_type").withPrefix().send(sender);
            return;
        }

        final String npcId = npc.getData().getId();
        pendingRequests.remove(npcId);
        final boolean isMirror = skin.equalsIgnoreCase("@mirror");
        final boolean isNone = skin.equalsIgnoreCase("@none");
        if (isMirror) {
            if (new NpcModifyEvent(npc, NpcModifyEvent.NpcModification.MIRROR_SKIN, true, sender).callEvent()) {
                npc.getData().setMirrorSkin(true);
                npc.removeForAll();
                npc.create();
                npc.spawnForAll();
                translator.translate("npc_skin_set_mirror").replace("npc", npc.getData().getName()).withPrefix().send(sender);
            } else {
                translator.translate("command_npc_modification_cancelled").withPrefix().send(sender);
            }
        } else if (isNone) {
            if (new NpcModifyEvent(npc, NpcModifyEvent.NpcModification.SKIN, false, sender).callEvent() && new NpcModifyEvent(npc, NpcModifyEvent.NpcModification.SKIN, null, sender).callEvent()) {
                npc.getData().setMirrorSkin(false);
                npc.getData().setSkinData(null);
                npc.removeForAll();
                npc.create();
                npc.spawnForAll();
                translator.translate("npc_skin_set_none").withPrefix().replace("npc", npc.getData().getName()).send(sender);
            } else {
                translator.translate("command_npc_modification_cancelled").withPrefix().send(sender);
            }
        } else {
            SkinData.SkinVariant variant = slim ? SkinData.SkinVariant.SLIM : SkinData.SkinVariant.AUTO;
            UUID requestId = UUID.randomUUID();
            UUID senderId = sender instanceof Player player ? player.getUniqueId() : null;
            pendingRequests.put(npcId, requestId);
            translator.translate("npc_skin_set_later").withPrefix().replace("npc", npc.getData().getName()).send(sender);

            FancyNpcs.getInstance().getSkinManagerImpl()
                    .getByIdentifierAsync(skin, variant)
                    .whenComplete((skinData, error) -> scheduleResult(npcId, requestId, senderId, skin, skinData, error));
        }
    }

    private void scheduleResult(
            String npcId,
            UUID requestId,
            UUID senderId,
            String identifier,
            SkinData skinData,
            Throwable error
    ) {
        FancyNpcs plugin = FancyNpcs.getInstance();
        if (!plugin.isEnabled()) {
            return;
        }

        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            if (!plugin.isEnabled()) {
                return;
            }

            if (senderId != null) {
                Player player = plugin.getServer().getPlayer(senderId);
                if (player != null && player.isOnline()) {
                    player.getScheduler().run(
                            plugin,
                            scheduledTask -> applyResult(npcId, requestId, player, identifier, skinData, error),
                            null
                    );
                    return;
                }
            }

            applyResult(npcId, requestId, plugin.getServer().getConsoleSender(), identifier, skinData, error);
        });
    }

    private void applyResult(
            String npcId,
            UUID requestId,
            CommandSender sender,
            String identifier,
            SkinData resolvedSkin,
            Throwable error
    ) {
        if (!requestId.equals(pendingRequests.get(npcId))) {
            return;
        }
        pendingRequests.remove(npcId, requestId);

        Npc npc = FancyNpcs.getInstance().getNpcManager().getNpcById(npcId);
        if (npc == null) {
            return;
        }

        Throwable cause = unwrap(error);
        if (cause != null) {
            sendFailure(sender, npc, cause);
            return;
        }

        SkinData skinData = new SkinData(
                identifier,
                resolvedSkin.getVariant(),
                resolvedSkin.getTextureValue(),
                resolvedSkin.getTextureSignature()
        );
        if (!new NpcModifyEvent(npc, NpcModifyEvent.NpcModification.SKIN, false, sender).callEvent()
                || !new NpcModifyEvent(npc, NpcModifyEvent.NpcModification.SKIN, skinData, sender).callEvent()) {
            translator.translate("command_npc_modification_cancelled").withPrefix().send(sender);
            return;
        }

        npc.getData().setMirrorSkin(false);
        npc.getData().setSkinData(skinData);
        if (!skinData.hasTexture()) {
            return;
        }

        translator.translate("npc_skin_set")
                .withPrefix()
                .replace("npc", npc.getData().getName())
                .replace("name", skinData.getIdentifier())
                .send(sender);
        npc.removeForAll();
        npc.create();
        npc.spawnForAll();
    }

    private void sendFailure(CommandSender sender, Npc npc, Throwable error) {
        if (error instanceof SkinLoadException skinLoadException) {
            switch (skinLoadException.getReason()) {
                case INVALID_URL ->
                        translator.translate("npc_skin_failure_invalid_url").withPrefix().replace("npc", npc.getData().getName()).send(sender);
                case INVALID_FILE ->
                        translator.translate("npc_skin_failure_invalid_file").withPrefix().replace("npc", npc.getData().getName()).send(sender);
                case INVALID_USERNAME ->
                        translator.translate("npc_skin_failure_invalid_username").withPrefix().replace("npc", npc.getData().getName()).send(sender);
                case INVALID_PLACEHOLDER ->
                        translator.translate("npc_skin_failure_invalid_placeholder").withPrefix().replace("npc", npc.getData().getName()).send(sender);
            }
            return;
        }

        FancyNpcs.getInstance().getFancyLogger().warn(
                "Asynchronous skin lookup failed for NPC " + npc.getData().getName() + ": "
                        + error.getClass().getSimpleName() + ": " + error.getMessage()
        );
        translator.translate("npc_skin_failure_invalid_username").withPrefix().replace("npc", npc.getData().getName()).send(sender);
    }

    private Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    public void shutdown() {
        pendingRequests.clear();
    }

    /* UTILITY METHODS */

    @Suggestions("SkinCMD/skin")
    public List<String> suggestSkin(final CommandContext<CommandSender> context, final CommandInput input) {
        return new ArrayList<>() {{
            add("@none");
            add("@mirror");
            Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(this::add);
            // Adding file names inside 'plugins/FancyNpcs/skins' to the list of completions.
            final File[] files = new File(FancyNpcs.getInstance().getDataFolder(), "skins").listFiles();
            if (files != null) {
                Arrays.stream(files).map(File::getName).filter(SkinUtils::isFile).forEach(this::add);
            }
        }};
    }

}
