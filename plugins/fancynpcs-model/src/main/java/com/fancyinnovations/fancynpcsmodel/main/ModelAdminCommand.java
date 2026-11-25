package com.fancyinnovations.fancynpcsmodel.main;

import com.fancyinnovations.fancynpcsmodel.commands.fancynpcsmodel.FNMConfigCMD;
import com.fancyinnovations.fancynpcsmodel.commands.fancynpcsmodel.FNMVersionCMD;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.help.GenericCommandHelpTopic;
import org.bukkit.help.HelpTopic;
import org.bukkit.help.IndexHelpTopic;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

final class ModelAdminCommand extends BukkitCommand implements PluginIdentifiableCommand {
    private static final String PERMISSION = "fancynpcsmodel.commands.fancynpcsmodel.";

    ModelAdminCommand() {
        super("fancynpcsmodel");
        setDescription("Manage FancyNpcsModel");
        setUsage("/fancynpcsmodel <version|config show|config reload|public>");
    }

    @Override
    public FancyNpcsModelPlugin getPlugin() {
        // PlugManX can retain plugin-typed command fields during another plugin's unload.
        return FancyNpcsModelPlugin.get();
    }

    void register() {
        var plugin = getPlugin();
        Bukkit.getCommandMap().register(plugin.getName().toLowerCase(Locale.ROOT), this);
        var help = Bukkit.getHelpMap();
        var ignored = help.getIgnoredPlugins();
        if (!ignored.contains("All") && !ignored.contains(plugin.getName())) {
            help.addTopic(new GenericCommandHelpTopic(this));
        }
    }

    void detach() {
        var plugin = getPlugin();
        var map = Bukkit.getCommandMap();
        map.getKnownCommands().values().removeIf(command -> command == this);
        unregister(map);
        var help = Bukkit.getHelpMap();
        // CraftBukkit keeps a second reference to command objects in its help map.
        try {
            var commandField = GenericCommandHelpTopic.class.getDeclaredField("command");
            var indexField = IndexHelpTopic.class.getDeclaredField("allTopics");
            commandField.setAccessible(true);
            indexField.setAccessible(true);
            synchronized (help) {
                Collection<HelpTopic> registered = help.getHelpTopics();
                Set<HelpTopic> owned = new HashSet<>();
                for (HelpTopic topic : registered) {
                    if (topic instanceof GenericCommandHelpTopic && commandField.get(topic) == this) owned.add(topic);
                }
                HelpTopic index = help.getHelpTopic(plugin.getName());
                if (index instanceof IndexHelpTopic && indexField.get(index) instanceof Collection<?> entries
                        && entries.stream().anyMatch(owned::contains)) {
                    if (owned.containsAll(entries)) {
                        registered.remove(index);
                    } else {
                        indexField.set(index, entries.stream().filter(entry -> !owned.contains(entry)).toList());
                    }
                }
                registered.removeAll(owned);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not remove FancyNpcsModel command help entries", e);
        }
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        var plugin = getPlugin();
        if (!plugin.isEnabled()) return false;
        if (args.length > 0 && args[0].equalsIgnoreCase("public")) {
            if (!sender.isOp()) {
                plugin.getTranslator().translate("common.no_permission").send(sender);
            } else if (args.length != 3 || !List.of("on", "off", "status").contains(args[2].toLowerCase(Locale.ROOT))) {
                plugin.getTranslator().translate("commands.fancynpcsmodel.public.usage").send(sender);
            } else {
                var session = plugin.activeSession();
                if (session == null) {
                    plugin.getTranslator().translate("common.integration_inactive").withPrefix().send(sender);
                } else {
                    session.publicNpc(sender, args[1], args[2].toLowerCase(Locale.ROOT));
                }
            }
            return true;
        }
        String operation = String.join(" ", args).toLowerCase(Locale.ROOT);
        String permission = switch (operation) {
            case "version" -> "version";
            case "config show" -> "config.show";
            case "config reload" -> "config.reload";
            default -> null;
        };
        if (permission == null) {
            plugin.getTranslator().translate("commands.fancynpcsmodel.usage").replace("label", label).send(sender);
        } else if (!sender.hasPermission(PERMISSION + permission)) {
            plugin.getTranslator().translate("common.no_permission").send(sender);
        } else {
            switch (operation) {
                case "version" -> FNMVersionCMD.INSTANCE.version(sender);
                case "config show" -> FNMConfigCMD.INSTANCE.show(sender);
                case "config reload" -> FNMConfigCMD.INSTANCE.configReload(sender);
                default -> throw new IllegalStateException(operation);
            }
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (!getPlugin().isEnabled()) return List.of();
        if (args.length == 1) {
            return List.of("version", "config", "public").stream()
                    .filter(name -> switch (name) {
                        case "version" -> sender.hasPermission(PERMISSION + "version");
                        case "config" -> sender.hasPermission(PERMISSION + "config.show") || sender.hasPermission(PERMISSION + "config.reload");
                        default -> sender.isOp();
                    })
                    .filter(name -> name.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("public") && sender.isOp()) {
            return List.of("on", "off", "status").stream()
                    .filter(mode -> mode.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("config")) {
            return List.of("show", "reload").stream().filter(name -> sender.hasPermission(PERMISSION + "config." + name))
                    .filter(name -> name.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
