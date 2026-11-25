package de.oliver.fancynpcs;

import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcAttribute;
import de.oliver.fancynpcs.api.NpcExtension;
import de.oliver.fancynpcs.api.actions.NpcAction;
import de.oliver.fancynpcs.api.actions.executor.ActionExecutionContext;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ExtensionRegistry implements Listener {
    private final FancyNpcs plugin;
    private volatile List<Entry> entries = List.of();

    ExtensionRegistry(FancyNpcs plugin) {
        this.plugin = plugin;
    }

    synchronized NpcExtension.Registration attach(Plugin owner, NpcExtension extension, Set<String> coreCommands) {
        if (owner == plugin || !owner.isEnabled() || !plugin.isEnabled() || plugin.getNpcThread().isShutdown()) {
            throw new IllegalStateException("Both the extension plugin and FancyNpcs must be enabled");
        }
        for (String name : extension.commands().keySet()) {
            validName(name);
            if (coreCommands.contains(name) || entries.stream().anyMatch(e -> e.extension.commands().containsKey(name))) {
                throw new IllegalArgumentException("NPC command already registered: " + name);
            }
        }
        var attributeRefs = new ArrayList<AttributeReference>();
        var keys = new HashSet<AttributeKey>();
        for (NpcAttribute attribute : extension.attributes()) {
            validName(attribute.getName());
            for (EntityType type : List.copyOf(attribute.getTypes())) {
                if (!keys.add(new AttributeKey(type, attribute.getName()))
                        || plugin.getAttributeManager().getAttributeByName(type, attribute.getName()) != null) {
                    throw new IllegalArgumentException("NPC attribute already registered: " + type + "/" + attribute.getName());
                }
                attributeRefs.add(new AttributeReference(type, attribute.getName()));
            }
        }
        var actionRefs = new ArrayList<NpcAction>();
        var actionNames = new HashSet<String>();
        for (NpcAction action : extension.actions()) {
            String name = action.getName();
            validName(name);
            if (!actionNames.add(name) || plugin.getActionManager().getActionByName(name) != null) {
                throw new IllegalArgumentException("NPC action already registered: " + name);
            }
            actionRefs.add(new NpcAction(name, action.requiresValue()) {
                @Override
                public void execute(ActionExecutionContext context, String value) {
                    NpcAction target = findAction(name);
                    if (target != null) target.execute(context, value);
                }
            });
        }

        Token token = new Token(this);
        Entry entry = new Entry(owner, extension, List.copyOf(attributeRefs), List.copyOf(actionRefs), token);
        var next = new ArrayList<>(entries);
        next.add(entry);
        for (AttributeReference ref : attributeRefs) plugin.getAttributeManager().registerAttribute(ref);
        for (NpcAction ref : actionRefs) plugin.getActionManager().registerAction(ref);
        entries = List.copyOf(next);
        return token;
    }

    private static void validName(String name) {
        if (!name.matches("[a-z0-9][a-z0-9_-]*")) throw new IllegalArgumentException("Invalid extension key: " + name);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public synchronized void onPluginDisable(PluginDisableEvent event) {
        for (Entry entry : entries) {
            if (entry.owner == event.getPlugin()) detach(entry.token);
        }
    }

    synchronized void closeAll() {
        for (Entry entry : entries) detach(entry.token);
    }

    private synchronized void detach(Token token) {
        for (Entry entry : entries) {
            if (entry.token != token) continue;
            entries = entries.stream().filter(other -> other != entry).toList();
            token.registry = null;
            plugin.getAttributeManager().getAllAttributes().removeIf(candidate -> entry.attributes.stream().anyMatch(ref -> ref == candidate));
            for (NpcAction action : entry.actions) plugin.getActionManager().unregisterAction(action);
            return;
        }
    }

    public boolean mayUse(CommandSender sender) {
        return entries.stream().filter(e -> e.owner.isEnabled()).anyMatch(e -> e.extension.commands().values().stream()
                .anyMatch(command -> sender.hasPermission(command.permission())));
    }

    public List<String> suggestNames(CommandSender sender) {
        return entries.stream().filter(e -> e.owner.isEnabled()).flatMap(e -> e.extension.commands().entrySet().stream())
                .filter(command -> sender.hasPermission(command.getValue().permission())).map(java.util.Map.Entry::getKey).sorted().toList();
    }

    public NpcExtension.Command command(CommandSender sender, String name) {
        for (Entry entry : entries) {
            var command = entry.extension.commands().get(name.toLowerCase(Locale.ROOT));
            if (command != null && entry.owner.isEnabled() && sender.hasPermission(command.permission()) && plugin.isEnabled()) return command;
        }
        return null;
    }

    private NpcAction findAction(String name) {
        for (Entry entry : entries) {
            if (!entry.owner.isEnabled() || !plugin.isEnabled()) continue;
            for (NpcAction action : entry.extension.actions()) if (action.getName().equals(name)) return action;
        }
        return null;
    }

    AttributeReference attributeReference(EntityType type, String name) {
        return new AttributeReference(type, name);
    }

    private NpcAttribute findAttribute(AttributeKey key) {
        if (!plugin.isEnabled()) return null;
        for (Entry entry : entries) {
            if (!entry.owner.isEnabled()) continue;
            for (NpcAttribute attribute : entry.extension.attributes()) {
                if (attribute.getName().equalsIgnoreCase(key.name) && attribute.getTypes().contains(key.type)) return attribute;
            }
        }
        NpcAttribute registered = plugin.getAttributeManager().getAttributeByName(key.type, key.name);
        return registered instanceof AttributeReference ? null : registered;
    }

    // Record equality would adapt a JDK method handle to this plugin's class and soft-cache it past unload.
    private static final class AttributeKey {
        private final EntityType type;
        private final String name;

        AttributeKey(EntityType type, String name) {
            this.type = type;
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AttributeKey key && type == key.type && name.equals(key.name);
        }

        @Override
        public int hashCode() {
            return 31 * type.hashCode() + name.hashCode();
        }
    }

    final class AttributeReference extends NpcAttribute {
        private final AttributeKey key;

        AttributeReference(EntityType type, String name) {
            super(name, List.of(), List.of(type), (npc, value) -> { });
            key = new AttributeKey(type, name.toLowerCase(Locale.ROOT));
        }

        @Override
        public boolean isValidValue(String value) {
            NpcAttribute target = findAttribute(key);
            return target == null || target.isValidValue(value);
        }

        @Override
        public List<String> getPossibleValues() {
            NpcAttribute target = findAttribute(key);
            return target == null ? List.of() : target.getPossibleValues();
        }

        @Override
        public void apply(Npc npc, String value) {
            NpcAttribute target = findAttribute(key);
            if (target != null && target.isValidValue(value)) target.apply(npc, value);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AttributeReference ref && key.equals(ref.key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    private record Entry(Plugin owner, NpcExtension extension, List<AttributeReference> attributes,
                         List<NpcAction> actions, Token token) { }

    private static final class Token implements NpcExtension.Registration {
        private volatile ExtensionRegistry registry;

        Token(ExtensionRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void close() {
            ExtensionRegistry current = registry;
            if (current != null) current.detach(this);
        }
    }
}
