package de.oliver.fancynpcs;

import de.oliver.fancylib.translations.message.SimpleMessage;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.help.GenericCommandHelpTopic;
import org.bukkit.help.HelpTopic;
import org.bukkit.help.IndexHelpTopic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/** CraftBukkit's help map outlives dynamically unloaded Cloud command objects. */
final class NpcCommandHelp {
    private static final List<String> LABELS = List.of("npc", "fancynpcs", "npcconvert");

    private NpcCommandHelp() {
    }

    static void register(FancyNpcs plugin) {
        var server = plugin.getServer();
        var helpMap = server.getHelpMap();
        synchronized (helpMap) {
            var ignored = helpMap.getIgnoredPlugins();
            if (ignored.contains("All") || ignored.contains(plugin.getName())) {
                return;
            }
            List<HelpTopic> entries = new ArrayList<>();
            for (String label : LABELS) {
                var command = server.getCommandMap().getCommand(label);
                if (command instanceof PluginIdentifiableCommand owned && owned.getPlugin() == plugin) {
                    helpMap.addTopic(new GenericCommandHelpTopic(command));
                    var added = helpMap.getHelpTopic("/" + label);
                    if (added != null) {
                        entries.add(added);
                    }
                }
            }
            if (!entries.isEmpty()) {
                String description = ((SimpleMessage) plugin.getTranslator().translate("fancynpcs_command_index")).getMessage();
                helpMap.addTopic(new IndexHelpTopic(plugin.getName(), description, null, entries));
            }
        }
    }

    static void detach(FancyNpcs plugin) {
        var helpMap = plugin.getServer().getHelpMap();
        try {
            Field commandField = GenericCommandHelpTopic.class.getDeclaredField("command");
            commandField.setAccessible(true);
            Field indexField = IndexHelpTopic.class.getDeclaredField("allTopics");
            indexField.setAccessible(true);
            synchronized (helpMap) {
                Collection<HelpTopic> registered = helpMap.getHelpTopics();
                Set<HelpTopic> owned = new HashSet<>();
                for (HelpTopic topic : registered) {
                    if (topic instanceof GenericCommandHelpTopic && commandField.get(topic) instanceof PluginIdentifiableCommand command
                            && command.getPlugin() == plugin) {
                        owned.add(topic);
                    }
                }
                var index = helpMap.getHelpTopic(plugin.getName());
                if (index instanceof IndexHelpTopic && indexField.get(index) instanceof Collection<?> entries
                        && entries.stream().anyMatch(owned::contains)) {
                    if (owned.containsAll(entries)) {
                        registered.remove(index);
                    } else {
                        // Preserve custom index entries unrelated to this plugin instance.
                        indexField.set(index, entries.stream().filter(entry -> !owned.contains(entry)).toList());
                    }
                }
                registered.removeAll(owned);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not detach FancyNpcs command help topics", failure);
        }
    }
}
