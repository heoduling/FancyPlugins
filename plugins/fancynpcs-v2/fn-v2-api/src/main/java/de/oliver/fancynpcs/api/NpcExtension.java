package de.oliver.fancynpcs.api;

import de.oliver.fancynpcs.api.actions.NpcAction;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/** Callbacks owned by a single enabled plugin instance. Keys are literal /npc subcommands. */
public record NpcExtension(Map<String, Command> commands, List<NpcAttribute> attributes, List<NpcAction> actions) {
    public NpcExtension {
        commands = Map.copyOf(commands);
        attributes = List.copyOf(attributes);
        actions = List.copyOf(actions);
    }

    /** Arguments are the remaining command text, not including the subcommand. */
    public record Command(String permission, BiConsumer<CommandSender, String> execute,
                          BiFunction<CommandSender, String, List<String>> suggestions) {
        public Command {
            if (Objects.requireNonNull(permission).isBlank()) {
                throw new IllegalArgumentException("An extension command must declare a permission");
            }
            Objects.requireNonNull(execute);
            Objects.requireNonNull(suggestions);
        }
    }

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
