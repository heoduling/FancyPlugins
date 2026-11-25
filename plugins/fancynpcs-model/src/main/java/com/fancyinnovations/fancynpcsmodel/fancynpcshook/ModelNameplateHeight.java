package com.fancyinnovations.fancynpcsmodel.fancynpcshook;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.animation.AnimationOverrideState;
import kr.toxicity.model.api.bone.BoneItemMapper;
import kr.toxicity.model.api.bone.BoneMovement;
import kr.toxicity.model.api.bone.BoneName;
import kr.toxicity.model.api.bone.BonePosition;
import kr.toxicity.model.api.bone.BoneRenderContext;
import kr.toxicity.model.api.bone.BoneTags;
import kr.toxicity.model.api.bone.RenderedBone;
import kr.toxicity.model.api.data.blueprint.BlueprintElement;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.data.renderer.RendererGroup;
import kr.toxicity.model.api.mount.MountControllers;
import kr.toxicity.model.api.tracker.ModelRotation;
import kr.toxicity.model.api.util.TransformedItemStack;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

final class ModelNameplateHeight {
    private static final float EPSILON = 0.0001F;
    private static final float GAP = 0.25F;
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(ModelRenderer renderer, Placement placement) {}

    record Placement(float x, float y, float z, boolean explicit) {
        Vector3f atScale(float scale, ModelRotation rotation) {
            return explicit
                    ? new Vector3f(x, y, z).mul(scale).rotateX(-rotation.radianX()).rotateY(-spawnYawRadians(rotation.y()))
                    : new Vector3f(0, y * scale + GAP, 0);
        }
    }

    private static float spawnYawRadians(float yaw) {
        // Display spawn packets floor the angle, including negative values.
        byte packed = (byte) (int) Math.floor(yaw * 256F / 360F);
        return (float) Math.toRadians(packed * 360F / 256F);
    }

    private record ClickBounds(Vector3f center, float bottom, float top, float width) {
        boolean covers(Bounds shape) {
            float tall = shape.maxY - shape.minY;
            float midX = (shape.minX + shape.maxX) * 0.5F;
            float midZ = (shape.minZ + shape.maxZ) * 0.5F;
            return tall > EPSILON && top - bottom >= tall * 0.65F
                    && bottom <= shape.minY + tall * 0.25F && top >= shape.maxY - tall * 0.25F
                    && width >= Math.max(shape.maxX - shape.minX, shape.maxZ - shape.minZ) * 0.5F
                    && Math.abs(center.x - midX) <= width * 0.5F && Math.abs(center.z - midZ) <= width * 0.5F;
        }
    }

    private static final class Bounds {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        void add(Vector3f point) {
            if (!point.isFinite()) return;
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            maxZ = Math.max(maxZ, point.z);
        }
    }

    static Placement cached(ModelRenderer renderer) {
        return CACHE.compute(renderer.name(), (name, old) -> old != null && old.renderer() == renderer
                ? old : new Cached(renderer, measure(renderer))).placement();
    }

    static void clearCache() {
        CACHE.clear();
    }

    static Placement measure(ModelRenderer renderer) {
        // Use BetterModel's pose evaluator without displays, viewers or the live NPC tracker.
        var context = new BoneRenderContext(null, null);
        var air = TransformedItemStack.of(new Vector3f(), new Vector3f(), new Vector3f(1),
                BetterModel.platform().adapter().air());
        var roots = renderer.rendererGroups().values().stream().map(group -> withoutDisplay(group, air))
                .map(group -> group.create(context)).toList();
        var bones = roots.stream().flatMap(RenderedBone::flatten).toList();
        bones.forEach(bone -> bone.scale(() -> 1F));
        roots.forEach(root -> root.addPositionModifier(bone -> true, Function.identity()));
        renderer.animation("idle").ifPresent(idle -> {
            var mod = AnimationModifier.builder().start(0).type(AnimationIterator.Type.LOOP).build();
            roots.forEach(root -> root.matchAnimation(AnimationOverrideState.NOT_MATCHED,
                    (bone, state) -> bone.addAnimation(state, idle, mod, () -> {})));
        });
        for (int step = 0; step < 2; step++) {
            bones.forEach(RenderedBone::tick);
            bones.forEach(bone -> bone.sendTransformation(null, null));
        }

        var anchors = bones.stream().filter(bone -> bone.name().rawName().equalsIgnoreCase("fnm_nameplate")).limit(2).toList();
        if (anchors.size() == 1) {
            var pivot = anchors.getFirst().worldPosition();
            if (pivot.isFinite()) return new Placement(pivot.x, pivot.y, pivot.z, true);
        }

        var shape = new Bounds();
        bones.forEach(bone -> addVisibleGeometry(bone, shape));
        var clicks = bones.stream().filter(bone -> bone.getGroup().getHitBox() != null
                && (bone.name().name().equalsIgnoreCase("hitbox") || bone.name().tagged(BoneTags.HITBOX))).limit(2).toList();
        if (clicks.size() == 1 && Float.isFinite(shape.maxY)) {
            var bone = clicks.getFirst();
            var box = bone.getGroup().getHitBox();
            var center = bone.hitBoxPosition();
            float bottom = center.y + (float) box.center().minY();
            var interaction = new ClickBounds(center, bottom, bottom + (float) box.y(), (float) ((box.x() + box.z()) / 2));
            if (interaction.covers(shape)) return new Placement(0, interaction.top(), 0, false);
        }
        return new Placement(0, Float.isFinite(shape.maxY) ? shape.maxY : 1.8F, 0, false);
    }

    private static RendererGroup withoutDisplay(RendererGroup source, TransformedItemStack air) {
        var children = new LinkedHashMap<BoneName, RendererGroup>();
        source.getChildren().forEach((key, child) -> children.put(key, withoutDisplay(child, air)));
        return new RendererGroup(source.getParent(), source.getPosition(), source.getParent().rotation().toVector(),
                air, children, source.getHitBox(), source.getHitBoxPoint(), BoneItemMapper.EMPTY, MountControllers.INVALID);
    }

    private static void addVisibleGeometry(RenderedBone bone, Bounds shape) {
        if (!(bone.getGroup().getParent() instanceof BlueprintElement.Group group) || !group.visibility()) return;
        var movement = new BoneMovement();
        bone.worldPosition(movement);
        var scale = movement.scale();
        int dimensions = (Math.abs(scale.x) > EPSILON ? 1 : 0) + (Math.abs(scale.y) > EPSILON ? 1 : 0)
                + (Math.abs(scale.z) > EPSILON ? 1 : 0);
        if (dimensions < 2) return;
        var groupOrigin = group.origin().toVector();
        var zero = new Vector3f();
        for (var element : group.children()) {
            if (element instanceof BlueprintElement.Cube cube && cube.visibility() && cube.hasTexture()) {
                var from = cube.from().toVector().min(cube.to().toVector()).sub(cube.inflate(), cube.inflate(), cube.inflate());
                var to = cube.from().toVector().max(cube.to().toVector()).add(cube.inflate(), cube.inflate(), cube.inflate());
                var pivot = cube.origin().toVector();
                var rotation = cube.rotation().toQuaternionZYX();
                var faces = cube.faces();
                for (int corner = 0; corner < 8; corner++) {
                    boolean east = (corner & 1) != 0, up = (corner & 2) != 0, south = (corner & 4) != 0;
                    if (!(east ? faces.east() : faces.west()).hasTexture()
                            && !(up ? faces.up() : faces.down()).hasTexture()
                            && !(south ? faces.south() : faces.north()).hasTexture()) continue;
                    var point = new Vector3f(east ? to.x : from.x, up ? to.y : from.y, south ? to.z : from.z);
                    point.sub(pivot).rotate(rotation).add(pivot).sub(groupOrigin).div(16);
                    shape.add(bone.worldPosition(new BonePosition(zero, point, null), movement));
                }
            } else if (element instanceof BlueprintElement.Mesh mesh && mesh.visibility()) {
                var rotation = mesh.rotation().toQuaternionXYZ();
                var offset = mesh.origin().toVector().sub(groupOrigin);
                for (var face : mesh.faces()) {
                    if (face.texture() < 0) continue;
                    for (var vertex : face.points()) {
                        var point = vertex.vertices().toVector().rotate(rotation).add(offset).div(16);
                        shape.add(bone.worldPosition(new BonePosition(zero, point, null), movement));
                    }
                }
            }
        }
    }
}
