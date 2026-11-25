package com.fancyinnovations.fancynpcsmodel.integration;

import com.fancyinnovations.fancynpcsmodel.commands.npc.CustomModelCMD;
import com.fancyinnovations.fancynpcsmodel.commands.npc.PlayAnimationCMD;
import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcAttribute;
import de.oliver.fancynpcs.api.actions.NpcAction;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.incendo.cloud.context.CommandInput;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

final class ModelExtension implements Listener {
    private static final String PERMISSION = "fancynpcsmodel.command.npc.custom_model";

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onModelPermissionDenied(PlayerCommandPreprocessEvent event) {
        FancyNpcsPlugin host = NpcModelSession.activeHost();
        if (host == null) return;
        String message = event.getMessage();
        int separator = message.indexOf(' ');
        if (separator < 2 || message.charAt(0) != '/') return;
        String root = message.substring(1, separator).toLowerCase(Locale.ROOT);
        if (!root.equals("npc") && !root.equals("fancynpcs:npc")) return;
        CommandInput arguments = CommandInput.of(message.substring(separator + 1).stripLeading());
        String command = arguments.readString().toLowerCase(Locale.ROOT);
        if ((!command.equals("custom_model") && !command.equals("play_animation"))
                || event.getPlayer().hasPermission(PERMISSION)) return;
        if (!(Bukkit.getCommandMap().getCommand(root) instanceof PluginIdentifiableCommand owner)
                || owner.getPlugin() != host.getPlugin()) return;

        // The host rejects these before calling the extension; localize only the same denial.
        event.setCancelled(true);
        FancyNpcsModelPlugin.get().getTranslator().translate("common.no_permission").withPrefix().send(event.getPlayer());
    }

    // The extension interface is optional on older FancyNpcs builds.
    static AutoCloseable register(Plugin owner, FancyNpcsPlugin host, NpcAttribute attribute, List<NpcAction> actions) {
        ClassLoader loader = host.getClass().getClassLoader();
        Class<?> extensionType;
        try {
            extensionType = loader.loadClass("de.oliver.fancynpcs.api.NpcExtension");
        } catch (ClassNotFoundException olderHost) {
            return null;
        }
        try {
            var command = loader.loadClass(extensionType.getName() + "$Command")
                    .getConstructor(String.class, BiConsumer.class, BiFunction.class);
            BiConsumer<CommandSender, String> model = (sender, args) -> execute(sender, args, false);
            BiConsumer<CommandSender, String> animation = (sender, args) -> execute(sender, args, true);
            BiFunction<CommandSender, String, List<String>> models = (sender, args) -> suggest(host, sender, args, false);
            BiFunction<CommandSender, String, List<String>> animations = (sender, args) -> suggest(host, sender, args, true);
            var commands = Map.of("custom_model", command.newInstance(PERMISSION, model, models),
                    "play_animation", command.newInstance(PERMISSION, animation, animations));
            Object extension = extensionType.getConstructor(Map.class, List.class, List.class)
                    .newInstance(commands, List.of(attribute), actions);
            return (AutoCloseable) host.getClass().getMethod("registerExtension", Plugin.class, extensionType)
                    .invoke(host, owner, extension);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("FancyNpcs rejected the model extension", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Incompatible FancyNpcs extension API", e);
        }
    }

    private static void execute(CommandSender sender, String args, boolean animation) {
        FancyNpcsPlugin host = NpcModelSession.activeHost();
        if (host == null) return;
        var input = CommandInput.of(args.strip());
        String npcName = input.readString();
        String value = input.skipWhitespace().readString();
        String flag = input.skipWhitespace().remainingInput();
        if (npcName.isEmpty() || value.isEmpty() || !flag.isEmpty() && (!animation || !flag.equals("--loop"))) {
            FancyNpcsModelPlugin.get().getTranslator().translate(animation ? "commands.npc.play_animation.usage"
                    : "commands.npc.custom_model.usage").withPrefix().send(sender);
            return;
        }
        Npc npc = findNpc(host, sender, npcName);
        if (npc == null) {
            FancyNpcsModelPlugin.get().getTranslator().translate("common.npc_not_found")
                    .withPrefix().replaceStripped("npc", npcName).send(sender);
            return;
        }
        if (animation) {
            PlayAnimationCMD.INSTANCE.onPlayAnimation(sender, npc, value, !flag.isEmpty());
        } else {
            CustomModelCMD.INSTANCE.onCustomModel(sender, npc, value);
        }
    }

    private static List<String> suggest(FancyNpcsPlugin host, CommandSender sender, String args, boolean animation) {
        if (NpcModelSession.activeHost() != host) return List.of();
        var input = CommandInput.of(args.stripLeading());
        String name = input.readString();
        if (!input.hasRemainingInput(false)) {
            Player player = playerNpcOwner(host, sender);
            return host.getNpcManager().getAllNpcs().stream()
                    .filter(npc -> player == null || player.getUniqueId().equals(npc.getData().getCreator()))
                    .map(npc -> npc.getData().getName())
                    .filter(candidate -> startsWith(candidate, name)).toList();
        }
        Npc npc = findNpc(host, sender, name);
        if (npc == null) return List.of();
        String prefix = input.input().substring(0, input.cursor());
        String value = input.skipWhitespace().readString();
        if (animation && input.hasRemainingInput(false)) return List.of(prefix + " " + value + " --loop");
        List<String> candidates = animation ? PlayAnimationCMD.animationNames(npc) : CustomModelCMD.modelNames();
        return candidates.stream().filter(candidate -> startsWith(candidate, value))
                .map(candidate -> prefix + " " + candidate).toList();
    }

    private static boolean startsWith(String value, String prefix) {
        return value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }

    private static Player playerNpcOwner(FancyNpcsPlugin host, CommandSender sender) {
        var flag = host.getFeatureFlagConfig().getFeatureFlag("player-npcs");
        return flag != null && flag.isEnabled() && sender instanceof Player player ? player : null;
    }

    private static Npc findNpc(FancyNpcsPlugin host, CommandSender sender, String name) {
        var manager = host.getNpcManager();
        Player player = playerNpcOwner(host, sender);
        if (player != null) return manager.getNpc(name, player.getUniqueId());
        try {
            UUID.fromString(name);
            return manager.getNpcById(name);
        } catch (IllegalArgumentException notAnId) {
            return manager.getNpc(name);
        }
    }
}
