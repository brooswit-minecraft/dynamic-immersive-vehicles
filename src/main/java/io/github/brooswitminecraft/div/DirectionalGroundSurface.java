package io.github.brooswitminecraft.div;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/**
 * MINECRAFT-517: the directional block-face tire response {@code GroundSurface}/
 * {@code GroundSurfaces} (MINECRAFT-508) explicitly deferred. Layers a per-face, per-travel-
 * direction grip adjustment on top of the existing tag-driven isotropic baseline, sourced from
 * each contacted block's own canonical (never-resource-pack) texture, so travelling along a
 * laid log's grain reads differently from crossing it - without ever requiring the texture/model
 * resolution to succeed: {@link #at} always returns a usable {@link GroundSurface}, falling back
 * to the exact MINECRAFT-508 baseline whenever the canonical texture, the block's model, or its
 * blockstate rotation can't be resolved (an unsupported block shape, a missing asset on a bare
 * dedicated server, a model this doesn't understand). This is the "actual tire-force consumer"
 * this ticket owns - {@link PhysicsBridge} calls {@link #at} in place of {@code GroundSurfaces.at}
 * at the one wheel/terrain contact hook, and the result still flows through the exact same
 * {@code TireForces}/{@code MultiContactFrictionSolver} path as before.
 *
 * <p>Deliberately bounded: this never produces a MORE severe penalty than
 * {@link #MAX_GRAIN_PENALTY} off the baseline grip, however rough the sampled texel evidence - it
 * augments MINECRAFT-508's accepted baseline tuning, it never overrides or dominates it. Rolling
 * resistance is left exactly at the baseline's value; only {@code frictionMultiplier} varies with
 * direction, since grain direction is a grip/traction phenomenon on the real surfaces this
 * targets (wood), not a rolling-resistance one.
 *
 * <p>Rotation (review finding, PR #16 comment 6861624029): {@link BlockFaceTextures#textureFor}
 * resolves a texture for the contacted WORLD face, but that texture's own U/V axes belong to
 * whichever MODEL-local face it was authored for - a blockstate's {@code x}/{@code y} rotation
 * carries the texture onto a different world face WITHOUT re-deriving a new "world" UV
 * convention for it, so the U/V axes rotate right along with the texture. Sampling with the
 * WORLD face's own (unrotated) {@link FaceUv} convention, as an earlier version of this class
 * did, is only correct for the identity rotation; for a genuinely rotated block (a laid log) it
 * silently reads grain direction off the wrong axes. {@link #at} fixes this by rotating the
 * contact point/travel vector BACK into model-local space ({@link Face#inverseRotateVector})
 * before handing them to {@link FaceUv}, using {@code modelFace} (not the world face) throughout.
 */
public final class DirectionalGroundSurface {
    private DirectionalGroundSurface() {}

    static final double MAX_GRAIN_PENALTY = 0.25;

    /**
     * @param localX, localY, localZ the contact point in block-local space (each 0..1)
     * @param travelX, travelY, travelZ the wheel's rolling direction, world-frame (need not be
     *     normalized or even nonzero - a zero/degenerate travel direction just leaves the
     *     isotropic baseline unchanged, since "which way is it travelling" has no answer)
     */
    public static GroundSurface at(LevelReader level, BlockPos pos, Direction face,
            double localX, double localY, double localZ, double travelX, double travelY, double travelZ) {
        GroundSurface baseline = GroundSurfaces.at(level, pos);
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return baseline;
        }
        Optional<BlockFaceTextures.Resolution> resolution = BlockFaceTextures.textureFor(state, face);
        Optional<DirectionalTexelField> field = resolution.flatMap(r -> CanonicalTextures.at(r.textureId()));
        if (resolution.isEmpty() || field.isEmpty()) {
            return baseline;
        }
        BlockFaceTextures.Resolution r = resolution.get();
        return combine(baseline, field, r.modelFace(), r.rotationXDegrees(), r.rotationYDegrees(),
                localX, localY, localZ, travelX, travelY, travelZ);
    }

    /**
     * Rotation-aware entry point: transforms the WORLD-space contact point/travel vector back
     * into MODEL-local space (the inverse of the rotation {@link BlockModelResolver} applied to
     * place this texture on its contacted world face) before handing them to {@code modelFace}'s
     * own {@link FaceUv} convention, then delegates to the fully pure {@link #combine(GroundSurface,
     * Optional, double, double, double, double)} below. Still no Minecraft types - {@code
     * modelFace}/rotation are exactly what {@link BlockFaceTextures.Resolution} already carries.
     */
    static GroundSurface combine(GroundSurface baseline, Optional<DirectionalTexelField> field, Face modelFace,
            int rotationXDegrees, int rotationYDegrees,
            double localX, double localY, double localZ, double travelX, double travelY, double travelZ) {
        if (field.isEmpty()) {
            return baseline;
        }
        // Rotation is about the block's own center (0.5, 0.5, 0.5); only the POINT needs that
        // offset removed/restored around the rotation - a direction vector has none to begin with.
        double[] modelPoint = Face.inverseRotateVector(localX - 0.5, localY - 0.5, localZ - 0.5,
                rotationXDegrees, rotationYDegrees);
        FaceUv.Uv uv = FaceUv.of(modelFace, modelPoint[0] + 0.5, modelPoint[1] + 0.5, modelPoint[2] + 0.5);
        double[] modelTravel = Face.inverseRotateVector(travelX, travelY, travelZ, rotationXDegrees, rotationYDegrees);
        FaceUv.Uv travel = FaceUv.direction(modelFace, modelTravel[0], modelTravel[1], modelTravel[2]);
        return combine(baseline, field, uv.u(), uv.v(), travel.u(), travel.v());
    }

    /**
     * Pure numeric core: no Minecraft types, no rotation - just the already-resolved texture UV
     * and travel-direction UV, the same "extract the pure decision" discipline
     * {@link GroundSurface#select} already uses. Returns {@code baseline} completely unchanged
     * whenever the texture evidence is absent or there is no travel-direction evidence at all:
     * the MINECRAFT-508 isotropic baseline is always the safe fallback, by construction, not by a
     * separate try/catch.
     */
    static GroundSurface combine(GroundSurface baseline, Optional<DirectionalTexelField> field,
            double u, double v, double travelU, double travelV) {
        if (field.isEmpty()) {
            return baseline;
        }
        double length = Math.hypot(travelU, travelV);
        if (length <= 0) {
            return baseline;
        }
        double nu = travelU / length;
        double nv = travelV / length;
        DirectionalTexelField.Sample sample = field.get().sample(u, v);
        // nu*nu+nv*nv==1 (travel direction is unit-normalized just above), so this is a convex
        // combination of xVariation/yVariation weighted by how much of the travel direction lies
        // along each texture axis - "how rough is the texture in the direction this tire is
        // actually moving", not just at this one texel in isolation.
        double roughnessAlongTravel = clamp01(nu * nu * sample.xVariation() + nv * nv * sample.yVariation());
        double grainFactor = 1.0 - MAX_GRAIN_PENALTY * roughnessAlongTravel;
        return new GroundSurface(baseline.frictionMultiplier() * grainFactor, baseline.rollingResistanceMultiplier());
    }

    private static double clamp01(double value) {
        return value < 0 ? 0 : (value > 1 ? 1 : value);
    }
}
