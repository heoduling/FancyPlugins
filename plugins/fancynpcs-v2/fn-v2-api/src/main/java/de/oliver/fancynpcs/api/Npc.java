package de.oliver.fancynpcs.api;

import de.oliver.fancylib.RandomUtils;
import de.oliver.fancylib.serverSoftware.ServerSoftware;
import de.oliver.fancylib.translations.Translator;
import de.oliver.fancynpcs.api.actions.ActionTrigger;
import de.oliver.fancynpcs.api.actions.NpcAction;
import de.oliver.fancynpcs.api.actions.executor.ActionExecutor;
import de.oliver.fancynpcs.api.events.NpcInteractEvent;
import de.oliver.fancynpcs.api.utils.Interval;
import de.oliver.fancynpcs.api.utils.Interval.Unit;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public abstract class Npc {

    private static final NpcAttribute INVISIBLE_ATTRIBUTE = FancyNpcsPlugin.get().getAttributeManager().getAttributeByName(EntityType.PLAYER, "invisible");
    private static final char[] localNameChars = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f', 'k', 'l', 'm', 'n', 'o', 'r'};
    protected final Map<UUID, Boolean> isTeamCreated = new ConcurrentHashMap<>();
    protected final Map<UUID, Boolean> isVisibleForPlayer = new ConcurrentHashMap<>();
    protected final Map<UUID, Boolean> isLookingAtPlayer = new ConcurrentHashMap<>();
    protected final Map<UUID, Long> lastPlayerInteraction = new ConcurrentHashMap<>();
    private final Translator translator = FancyNpcsPlugin.get().getTranslator();
    protected NpcData data;
    protected boolean saveToFile;

    public Npc(NpcData data) {
        this.data = data;
        this.saveToFile = true;
    }

    protected String generateLocalName() {
        StringBuilder localName = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            localName.append('&').append(localNameChars[(int) RandomUtils.randomInRange(0, localNameChars.length)]);
        }

        return ChatColor.translateAlternateColorCodes('&', localName.toString());
    }

    public abstract void create();

    public Consumer<String> createConsoleCommandDispatcher() {
        return command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    public abstract void spawn(Player player);

    @ApiStatus.Internal
    public void spawn(Player player, World viewerWorld) {
        spawn(player);
    }

    public void spawnForAll() {
        submitNpcWork(() -> {
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                spawn(onlinePlayer);
            }
        });
    }

    public abstract void remove(Player player);

    public void removeForAll() {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            remove(onlinePlayer);
        }
    }

    /**
     * Checks if the NPC should be visible for the player.
     *
     * @param player The player to check for.
     * @return True if the NPC should be visible for the player, otherwise false.
     */
    protected boolean shouldBeVisible(Player player) {
        return shouldBeVisible(player, player.getLocation());
    }

    /** Evaluates visibility on the viewer's owning thread with an already sampled location. */
    @ApiStatus.Internal
    public boolean shouldBeVisible(Player player, Location viewerLocation) {
        if (!data.getVisibility().canSee(player, this)) {
            return false;
        }

        int visibilityDistance = (data.getVisibilityDistance() > -1) ? data.getVisibilityDistance() : FancyNpcsPlugin.get().getFancyNpcConfig().getVisibilityDistance();

        if (visibilityDistance == 0) {
            return false;
        }

        if (!data.isSpawnEntity()) {
            return false;
        }

        Location npcLocation = data.getLocation();
        if (npcLocation == null) {
            return false;
        }

        if (viewerLocation.getWorld() != npcLocation.getWorld()) {
            return false;
        }

        if (visibilityDistance != Integer.MAX_VALUE && npcLocation.distanceSquared(viewerLocation) > (double) visibilityDistance * visibilityDistance) {
            return false;
        }

        return !FancyNpcsPlugin.get().getFancyNpcConfig().isSkipInvisibleNpcs()
                || !data.getAttributes().getOrDefault(INVISIBLE_ATTRIBUTE, "false").equalsIgnoreCase("true")
                || data.isGlowing() || !data.getEquipment().isEmpty();
    }

    public void checkAndUpdateVisibility(Player player) {
        FancyNpcsPlugin.get().requestVisibilityCheck(this, player);
    }

    @ApiStatus.Internal
    public void applyVisibility(Player player, World viewerWorld, boolean visible) {
        boolean wasVisible = isShownFor(player);
        if (visible && !wasVisible) {
            spawn(player, viewerWorld);
            var owner = FancyNpcsPlugin.get();
            if (ServerSoftware.isFolia() && owner.getFeatureFlagConfig().getFeatureFlag("enable-folia-visibility-fix").isEnabled()) {
                owner.getNpcThread().schedule(() -> runOnViewerOwner(owner, player, () -> {
                    Location location = player.getLocation();
                    if (!shouldBeVisible(player, location)) return;
                    submitNpcWork(() -> {
                        if (isShownFor(player) && owner.getNpcManager().getNpcById(data.getId()) == this) {
                            remove(player);
                            spawn(player, location.getWorld());
                        }
                    });
                }, () -> {}), 100, TimeUnit.MILLISECONDS);
            }
        } else if (!visible && wasVisible) {
            remove(player);
        }
    }

    protected void submitNpcWork(Runnable task) {
        var worker = FancyNpcsPlugin.get().getNpcThread();
        if (worker.isShutdown()) {
            return;
        }
        try {
            worker.execute(task);
        } catch (RejectedExecutionException rejected) {
            if (!worker.isShutdown()) {
                throw rejected;
            }
        }
    }

    public void checkAndUpdateVisibilityForAll() {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            checkAndUpdateVisibility(onlinePlayer);
        }
    }

    public abstract void lookAt(Player player, Location location);

    public abstract void update(Player player, boolean swingArm);

    public void update(Player player) {
        update(player, FancyNpcsPlugin.get().getFancyNpcConfig().isSwingArmOnUpdate());
    }

    public void updateForAll(boolean swingArm) {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            update(onlinePlayer, swingArm);
        }
    }

    public void updateForAll() {
        updateForAll(FancyNpcsPlugin.get().getFancyNpcConfig().isSwingArmOnUpdate());
    }

    public abstract void move(Player player, boolean swingArm);

    public void move(Player player) {
        move(player, FancyNpcsPlugin.get().getFancyNpcConfig().isSwingArmOnUpdate());
    }

    public void moveForAll(boolean swingArm) {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            move(onlinePlayer, swingArm);
        }
    }

    public void moveForAll() {
        moveForAll(FancyNpcsPlugin.get().getFancyNpcConfig().isSwingArmOnUpdate());
    }

    public void interact(Player player) {
        interact(player, ActionTrigger.CUSTOM);
    }

    public void interact(Player player, ActionTrigger actionTrigger) {
        if (FancyNpcsPlugin.get().getNpcThread().isShutdown()) {
            return;
        }
        if (data.getInteractionCooldown() > 0) {
            final long interactionCooldownMillis = (long) (data.getInteractionCooldown() * 1000);
            final long lastInteractionMillis = lastPlayerInteraction.getOrDefault(player.getUniqueId(), 0L);
            final Interval interactionCooldownLeft = Interval.between(lastInteractionMillis + interactionCooldownMillis, System.currentTimeMillis(), Unit.MILLISECONDS);
            if (interactionCooldownLeft.as(Unit.MILLISECONDS) > 0) {

                if (!FancyNpcsPlugin.get().getFancyNpcConfig().isInteractionCooldownMessageDisabled()) {
                    translator.translate("interaction_on_cooldown").replace("time", interactionCooldownLeft.toString()).send(player);
                }

                return;
            }
            lastPlayerInteraction.put(player.getUniqueId(), System.currentTimeMillis());
        }

        List<NpcAction.NpcActionData> actions = data.getActions(actionTrigger);
        if (!new NpcInteractEvent(this, data.getOnClick(), actions, player, actionTrigger).callEvent()) {
            return;
        }

        // onClick
        if (data.getOnClick() != null) {
            data.getOnClick().accept(player);
        }

        // actions
        ActionExecutor.execute(actionTrigger, this, player);

        if (actionTrigger == ActionTrigger.LEFT_CLICK || actionTrigger == ActionTrigger.RIGHT_CLICK) {
            ActionExecutor.execute(ActionTrigger.ANY_CLICK, this, player);
        }
    }

    protected abstract void refreshEntityData(Player serverPlayer);

    public abstract int getEntityId();

    public NpcData getData() {
        return data;
    }

    public abstract float getEyeHeight();

    public Map<UUID, Boolean> getIsTeamCreated() {
        return isTeamCreated;
    }

    public Map<UUID, Boolean> getIsVisibleForPlayer() {
        return isVisibleForPlayer;
    }

    public boolean isShownFor(Player player) {
        return isVisibleForPlayer.getOrDefault(player.getUniqueId(), false);
    }

    public Map<UUID, Boolean> getIsLookingAtPlayer() {
        return isLookingAtPlayer;
    }

    @ApiStatus.Internal
    public boolean forgetViewer(UUID viewer) {
        isVisibleForPlayer.remove(viewer);
        isTeamCreated.remove(viewer);
        resetLookDirection(viewer);
        return Boolean.TRUE.equals(isLookingAtPlayer.remove(viewer));
    }

    protected void resetLookDirection(UUID viewer) {
    }

    public Map<UUID, Long> getLastPlayerInteraction() {
        return lastPlayerInteraction;
    }

    public boolean isDirty() {
        return data.isDirty();
    }

    public void setDirty(boolean dirty) {
        data.setDirty(dirty);
    }

    public boolean isSaveToFile() {
        return saveToFile;
    }

    @ApiStatus.Internal
    public static void runOnViewerOwner(FancyNpcsPlugin owner, Player player, Runnable task, Runnable unavailable) {
        if (!owner.getPlugin().isEnabled() || owner.getNpcThread().isShutdown()) {
            unavailable.run();
            return;
        }
        Runnable guarded = () -> {
            if (owner.getNpcThread().isShutdown() || !player.isConnected()) unavailable.run();
            else task.run();
        };
        try {
            if (ServerSoftware.isFolia()) {
                if (Bukkit.isOwnedByCurrentRegion(player)) guarded.run();
                else if (player.getScheduler().run(owner.getPlugin(), ignored -> guarded.run(), unavailable) == null) unavailable.run();
            } else if (Bukkit.isPrimaryThread()) {
                guarded.run();
            } else {
                Bukkit.getScheduler().runTask(owner.getPlugin(), guarded);
            }
        } catch (IllegalPluginAccessException disabled) {
            unavailable.run();
            if (owner.getPlugin().isEnabled() && !owner.getNpcThread().isShutdown()) throw disabled;
        }
    }

    public void setSaveToFile(boolean saveToFile) {
        this.saveToFile = saveToFile;
    }

    /**
     * Runs a task on the player's scheduler, when using Folia
     *
     * @param player The player whose scheduler to run the task on.
     * @param task   The task to run.
     */
    @ApiStatus.Internal
    protected void runOnPlayerScheduler(Player player, Runnable task) {
        var owner = FancyNpcsPlugin.get();
        if (!owner.getPlugin().isEnabled() || owner.getNpcThread().isShutdown()) {
            return;
        }
        if (ServerSoftware.isFolia()) {
            try {
                player.getScheduler().run(owner.getPlugin(), ignored -> {
                    if (!owner.getNpcThread().isShutdown()) {
                        task.run();
                    }
                }, null);
            } catch (IllegalPluginAccessException disabled) {
                if (owner.getPlugin().isEnabled() && !owner.getNpcThread().isShutdown()) {
                    throw disabled;
                }
            }
            return;
        }

        task.run();
    }
}
