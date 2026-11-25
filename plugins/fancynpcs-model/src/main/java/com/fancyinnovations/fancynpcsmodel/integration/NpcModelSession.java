package com.fancyinnovations.fancynpcsmodel.integration;

import com.fancyinnovations.fancynpcsmodel.config.PublicNpcAccess;
import com.fancyinnovations.fancynpcsmodel.commands.npc.CustomModelCMD;
import com.fancyinnovations.fancynpcsmodel.commands.npc.PlayAnimationCMD;
import com.fancyinnovations.fancynpcsmodel.fancynpcshook.CustomModelAttribute;
import com.fancyinnovations.fancynpcsmodel.fancynpcshook.ModelHitboxes;
import com.fancyinnovations.fancynpcsmodel.fancynpcshook.PlayAnimationLoopAction;
import com.fancyinnovations.fancynpcsmodel.fancynpcshook.PlayAnimationOnceAction;
import com.fancyinnovations.fancynpcsmodel.listeners.NpcInteractListener;
import com.fancyinnovations.fancynpcsmodel.listeners.NpcModelVisibilityListener;
import com.fancyinnovations.fancynpcsmodel.listeners.NpcTransformListener;
import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import com.fancyinnovations.fancynpcsmodel.main.ModelSession;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcAttribute;
import de.oliver.fancynpcs.api.actions.NpcAction;
import de.oliver.fancynpcs.api.events.NpcsLoadedEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.BetterModelPlatform;
import kr.toxicity.model.api.event.ModelEventListener;
import kr.toxicity.model.api.event.PluginEndReloadEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.List;

public final class NpcModelSession implements ModelSession, Listener {
    private static volatile NpcModelSession current;

    private final FancyNpcsModelPlugin model;
    private final FancyNpcsPlugin host;
    private final NpcModelVisibilityListener visibility = new NpcModelVisibilityListener();
    private final List<Listener> listeners = List.of(this, visibility, new NpcInteractListener(),
            new NpcTransformListener(), new ModelHitboxes.ClickListener(), new ModelExtension());
    private volatile boolean active;
    private AutoCloseable registration;
    private ModelEventListener reloadListener = ModelEventListener.NONE;
    private ScheduledTask initialSync;
    private ScheduledTask interactionCleanup;

    public NpcModelSession(FancyNpcsModelPlugin model, Plugin host) {
        this.model = model;
        this.host = (FancyNpcsPlugin) host;
        current = this;
    }

    public static FancyNpcsPlugin activeHost() {
        NpcModelSession session = current;
        return session != null && session.active ? session.host : null;
    }

    public static boolean isActive() {
        return activeHost() != null;
    }

    @Override
    public Plugin host() {
        return host.getPlugin();
    }

    @Override
    public void start() {
        NpcAttribute attribute = CustomModelAttribute.getModelAttribute();
        List<NpcAction> actions = List.of(new PlayAnimationOnceAction(), new PlayAnimationLoopAction());
        registration = ModelExtension.register(model, host, attribute, actions);
        if (registration == null) {
            registration = () -> {
                host.getAttributeManager().getAllAttributes().remove(attribute);
                var manager = host.getActionManager();
                for (NpcAction action : actions) {
                    if (manager.getActionByName(action.getName()) == action) manager.unregisterAction(action);
                }
            };
            host.registerCommand(CustomModelCMD.INSTANCE);
            host.registerCommand(PlayAnimationCMD.INSTANCE);
            host.getAttributeManager().registerAttribute(attribute);
            actions.forEach(host.getActionManager()::registerAction);
        }
        active = true;
        listeners.forEach(listener -> Bukkit.getPluginManager().registerEvents(listener, model));
        finishPublicNpcStartup();
        // The event bus can keep an empty registry's application key after unregister().
        reloadListener = BetterModel.eventBus().subscribe(BetterModel.platform(), PluginEndReloadEvent.class, event -> {
            if (active && event.result() instanceof BetterModelPlatform.ReloadResult.Success) {
                CustomModelAttribute.closePluginTrackers();
                visibility.scheduleOnlineModelSync(5L);
            }
        });
        initialSync = Bukkit.getGlobalRegionScheduler().runDelayed(model, ignored -> {
            if (active) visibility.scheduleOnlineModelSync(1L);
        }, 1L);
        interactionCleanup = Bukkit.getGlobalRegionScheduler().runAtFixedRate(model, ignored -> {
            if (!active) return;
            CustomModelAttribute.expireInteractions();
            visibility.recheckPermissionViewers(host);
        }, 20L, 20L);
    }

    @EventHandler
    public void onNpcsLoaded(NpcsLoadedEvent event) {
        finishPublicNpcStartup();
    }

    private void finishPublicNpcStartup() {
        var manager = host.getNpcManager();
        if (active && manager.isLoaded() && !model.publicNpcAccess().isUpgradeReady()) {
            model.finishPublicNpcStartup(manager.getAllNpcs().stream().map(npc -> npc.getData().getId()).toList(),
                    host.getPlugin().getDataFolder().toPath().resolve("npcs.yml"));
        }
    }

    @Override
    public void stop(boolean restoreCarriers) {
        active = false;
        current = null;
        if (initialSync != null) initialSync.cancel();
        if (interactionCleanup != null) interactionCleanup.cancel();
        reloadListener.unregister();
        listeners.forEach(HandlerList::unregisterAll);
        visibility.clear();
        try {
            CustomModelAttribute.closePluginTrackers();
        } finally {
            try {
                if (restoreCarriers) CustomModelAttribute.restoreCarriersOnDetach(model, host);
            } finally {
                CustomModelAttribute.clearCarrierState();
                if (registration != null) {
                    try {
                        registration.close();
                    } catch (Exception e) {
                        throw new IllegalStateException("Could not close the FancyNpcs extension registration", e);
                    } finally {
                        registration = null;
                    }
                }
            }
        }
    }

    @Override
    public int modelCount() {
        return active ? (int) host.getNpcManager().getAllNpcs().stream()
                .filter(CustomModelAttribute::hasAttribute).count() : 0;
    }

    @Override
    public void publicNpc(CommandSender sender, String target, String mode) {
        if (!active || !sender.isOp()) return;
        var manager = host.getNpcManager();
        Npc npc = manager.getNpcById(target);
        if (npc == null) {
            String legacyId = PublicNpcAccess.legacyUuidAlias(target);
            if (legacyId != null) npc = manager.getNpcById(legacyId);
        }
        if (npc == null) {
            var matches = manager.getAllNpcs().stream()
                    .filter(n -> n.getData().getName().equalsIgnoreCase(target)).limit(2).toList();
            if (matches.size() > 1) {
                model.getTranslator().translate("commands.fancynpcsmodel.public.ambiguous").withPrefix().send(sender);
                return;
            }
            npc = matches.isEmpty() ? null : matches.getFirst();
        }
        if (npc == null) {
            model.getTranslator().translate("common.npc_not_found").withPrefix().replaceStripped("npc", target).send(sender);
            return;
        }
        String id = npc.getData().getId();
        var access = model.publicNpcAccess();
        if (PublicNpcAccess.legacyUuidAlias(id) != null && !access.isUpgradeReady()) {
            model.getTranslator().translate("commands.fancynpcsmodel.public.upgrade_pending").withPrefix().send(sender);
            return;
        }
        String name = npc.getData().getName();
        if (mode.equals("status")) {
            replyPublic(sender, name, access.isPublic(PublicNpcAccess.markerKey(id)) ? "enabled" : "disabled");
            return;
        }
        boolean enabled = mode.equals("on");
        access.set(id, enabled).whenComplete((ignored, error) -> {
            Runnable reply = () -> {
                if (active) replyPublic(sender, name, error != null ? "failed" : enabled ? "enabled" : "disabled");
            };
            if (!active || !model.isEnabled()) return;
            try {
                if (sender instanceof Player player) player.getScheduler().run(model, task -> reply.run(), null);
                else Bukkit.getGlobalRegionScheduler().run(model, task -> reply.run());
            } catch (org.bukkit.plugin.IllegalPluginAccessException disabled) {
                if (model.isEnabled()) throw disabled;
            }
        });
    }

    private void replyPublic(CommandSender sender, String npc, String key) {
        model.getTranslator().translate("commands.fancynpcsmodel.public." + key)
                .withPrefix().replaceStripped("npc", npc).send(sender);
    }
}
