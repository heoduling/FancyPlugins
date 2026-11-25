package de.oliver.fancynpcs.commands.npc;

import de.oliver.fancylib.translations.Translator;
import de.oliver.fancynpcs.FancyNpcs;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import de.oliver.fancynpcs.api.events.NpcCreateEvent;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// TO-DO: Console support with --position and --world parameter flags.
public enum CopyCMD {
    INSTANCE; // SINGLETON

    private final Translator translator = FancyNpcs.getInstance().getTranslator();

    @Command(value = "npc copy <npc> <name>", requiredSender = Player.class)
    @Permission("fancynpcs.command.npc.copy")
    public void onCopy(
            final @NotNull Player sender,
            final @NotNull Npc npc,
            final @NotNull String name
    ) {
        // Sending error message if name does not match configured pattern.
        if (!CreateCMD.isValidName(name)) {
            translator.translate("npc_create_failure_invalid_name").withPrefix().replaceStripped("name", name).send(sender);
            return;
        }
        var plugin = FancyNpcs.getInstance();
        var manager = plugin.getNpcManagerImpl();
        var creator = sender.getUniqueId();
        if (manager.findReservedCreationName(name, creator) != null) {
            sendRegistrationFailure(sender, name, true);
            return;
        }

        final Npc copied = plugin.getNpcAdapter().apply(
                new NpcData(
                        UUID.randomUUID().toString(),
                        name,
                        creator,
                        npc.getData().getDisplayName(),
                        npc.getData().getSkinData(),
                        sender.getLocation().clone(),
                        npc.getData().isShowInTab(),
                        npc.getData().isSpawnEntity(),
                        npc.getData().isCollidable(),
                        npc.getData().isGlowing(),
                        npc.getData().getGlowingColor(),
                        npc.getData().getType(),
                        new ConcurrentHashMap<>(npc.getData().getEquipment()),
                        npc.getData().isTurnToPlayer(),
                        npc.getData().getTurnToPlayerDistance(),
                        npc.getData().getOnClick(),
                        npc.getData().getActions()
                                .entrySet()
                                .stream()
                                .collect(Collectors.toConcurrentMap(
                                        Map.Entry::getKey,
                                        e -> new ArrayList<>(e.getValue())
                                )),
                        npc.getData().getInteractionCooldown(),
                        npc.getData().getScale(),
                        npc.getData().getVisibilityDistance(),
                        new ConcurrentHashMap<>(npc.getData().getAttributes()),
                        npc.getData().isMirrorSkin()
                ));
        // Calling the event and creating + registering copied NPC if not cancelled.
        if (new NpcCreateEvent(copied, sender).callEvent()) {
            copied.create();
            if (!manager.registerCreatedNpc(copied)) {
                sendRegistrationFailure(sender, name, manager.findReservedCreationName(name, creator) != null);
                return;
            }
            copied.spawnForAll();
            translator.translate("npc_copy_success").withPrefix().replace("npc", npc.getData().getName()).replace("new_npc", copied.getData().getName()).send(sender);
        } else {
            translator.translate("command_npc_modification_cancelled").withPrefix().send(sender);
        }
    }

    private void sendRegistrationFailure(Player sender, String name, boolean duplicate) {
        translator.translate(duplicate ? "npc_create_failure_already_exists" : "npc_create_incomplete")
                .withPrefix().replaceStripped("npc", name).send(sender);
    }
}
