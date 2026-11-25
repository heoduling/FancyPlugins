package com.fancyinnovations.fancynpcsmodel.fancynpcshook;

import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import de.oliver.fancynpcs.api.Npc;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bone.BoneMovement;
import kr.toxicity.model.api.bone.BonePosition;
import kr.toxicity.model.api.bone.RenderedBone;
import kr.toxicity.model.api.nms.ModelNametag;
import kr.toxicity.model.api.platform.PlatformLocation;
import kr.toxicity.model.api.platform.PlatformPlayer;
import kr.toxicity.model.api.tracker.DummyTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

final class ModelNameplates {
    private final Npc npc;
    private final DummyTracker tracker;
    private final RenderedBone anchor;
    private final ModelNameplateHeight.Placement placement;
    private final float scale;
    private final Map<UUID, View> views = new HashMap<>();
    private boolean closed;

    private record View(PlatformPlayer player, ModelNametag tag, String name, PlatformLocation location) {}

    ModelNameplates(Npc npc, DummyTracker tracker, float scale) {
        this.npc = npc;
        this.tracker = tracker;
        anchor = tracker.bones().stream().findFirst().orElse(null);
        this.scale = scale;
        placement = ModelNameplateHeight.cached(tracker.renderer());
    }

    synchronized void show(Player viewer, PlatformPlayer target) {
        if (closed || anchor == null || !tracker.isSpawned(target)) return;
        UUID id = target.uuid();
        String name = npc.getData().getDisplayName();
        if (name == null || name.isBlank() || name.equalsIgnoreCase("<empty>")) {
            hide(target);
            return;
        }
        PlatformLocation location = tracker.location();
        View previous = views.get(id);
        if (previous != null && name.equals(previous.name()) && location.equals(previous.location())) return;
        Component component = NameFormatter.translate(name, viewer);
        if (PlainTextComponentSerializer.plainText().serialize(component).isBlank()) {
            hide(target);
            return;
        }
        ModelNametag tag = previous == null ? BetterModel.nms().createNametag(anchor) : previous.tag();
        tag.alwaysVisible(true);
        tag.component(component);

        // BetterModel adds the bone's offset when sending. Place the tag over the model's center and top.
        var offset = anchor.worldPosition(new BonePosition(new Vector3f(), anchor.getGroup().getHitBoxPoint(), id), new BoneMovement());
        var targetPosition = placement.atScale(scale, anchor.rotation());
        tag.teleport(location.add(targetPosition.x() - offset.x(), targetPosition.y() - offset.y(), targetPosition.z() - offset.z()));
        tag.send(target);
        views.put(id, new View(target, tag, name, location));
    }

    synchronized void hide(PlatformPlayer player) {
        View view = views.remove(player.uuid());
        if (view != null) remove(view);
    }

    synchronized void close() {
        closed = true;
        views.values().forEach(ModelNameplates::remove);
        views.clear();
    }

    private static void remove(View view) {
        var packets = BetterModel.nms().createBundler(1);
        view.tag().remove(packets);
        packets.send(view.player());
    }

    // This class is loaded with the host session. Use the same parser and player context as FancyNpcs.
    private record NameFormatter(Object handler, Method method) {
        private static final NameFormatter INSTANCE = resolve();

        private static NameFormatter resolve() {
            try {
                var type = Class.forName("de.oliver.fancynpcs.libs.chatcolorhandler.paper.PaperColor");
                return new NameFormatter(type.getMethod("handler").invoke(null), type.getMethod("translate", String.class, Player.class));
            } catch (ReflectiveOperationException e) {
                FancyNpcsModelPlugin.get().getLogger().log(Level.WARNING, "Could not access FancyNpcs name formatting", e);
                return null;
            }
        }

        private static Component translate(String name, Player viewer) {
            if (INSTANCE == null) return Component.empty();
            try {
                return (Component) INSTANCE.method.invoke(INSTANCE.handler, name, viewer);
            } catch (ReflectiveOperationException e) {
                FancyNpcsModelPlugin.get().getLogger().log(Level.WARNING, "Could not format a model NPC name", e);
                return Component.empty();
            }
        }
    }
}
