package io.github.brooswitminecraft.div;

/**
 * MINECRAFT-508 bounded baseline slice: the per-block ground-surface data this mod's shared tire-
 * force consumer actually uses ({@code PhysicsBridge#stepGround} via
 * {@code MultiContactFrictionSolver.FrictionContact}). Deliberately plain data with no Minecraft
 * types at all - the tag/{@code SoundType} glue that resolves a live block to one of these lives
 * in {@link GroundSurfaces}, kept separate so {@link #select} is unit-testable without
 * bootstrapping Minecraft's registries, the same "extract the pure decision" discipline
 * {@code PhysicsBridge} already uses elsewhere (see {@code PhysicsBridge#hasFloatDevice}/
 * {@code #selectStepper}). Numeric baselines are adapted (not imported as a dependency) from
 * Dynamic Terrain's reference {@code SurfaceProperties}; this mod has no build/runtime dependency
 * on Dynamic Terrain or Dynamic Vehicles. This record itself still only carries ONE isotropic pair
 * of multipliers - the directional, per-face, canonical-texture response (including rotated-log
 * orientation) MINECRAFT-517 adds lives in {@link DirectionalGroundSurface}, which wraps an
 * instance of this baseline rather than replacing it: every instance below remains the safe,
 * always-available fallback whenever that directional resolution can't succeed.
 *
 * @param frictionMultiplier tire grip relative to dry pavement (1.0 = pavement baseline)
 * @param rollingResistanceMultiplier rolling drag relative to dry pavement (1.0 = pavement baseline)
 */
public record GroundSurface(double frictionMultiplier, double rollingResistanceMultiplier) {
    public static final GroundSurface PAVEMENT = new GroundSurface(1.0, 1.0);
    public static final GroundSurface DIRT = new GroundSurface(0.85, 2.0);
    public static final GroundSurface GRASS = new GroundSurface(0.8, 2.5);
    public static final GroundSurface GRAVEL = new GroundSurface(0.75, 3.0);
    public static final GroundSurface ICE = new GroundSurface(0.05, 0.5);

    /** The handful of vanilla sound-type families {@link GroundSurfaces#at} maps an untagged
     * block's real {@code SoundType} onto before calling {@link #select} below. */
    public enum SoundFamily { GRAVEL_LIKE, SAND_LIKE, GRASS_LIKE, OTHER }

    /**
     * Pure priority decision: explicit tags win over the inferred {@code SoundFamily} fallback,
     * in a fixed order (pavement first - an intentionally paved block should never be downgraded
     * by an incidental sound-type match). No Minecraft types in this signature, so the whole
     * decision is unit-testable without a live Minecraft/NeoForge runtime.
     */
    public static GroundSurface select(boolean pavementTag, boolean iceTag, boolean gravelTag, boolean grassTag,
            boolean dirtTag, SoundFamily fallbackFamily) {
        if (pavementTag) return PAVEMENT;
        if (iceTag) return ICE;
        if (gravelTag) return GRAVEL;
        if (grassTag) return GRASS;
        if (dirtTag) return DIRT;
        return switch (fallbackFamily) {
            case GRAVEL_LIKE -> GRAVEL;
            case SAND_LIKE -> DIRT;
            case GRASS_LIKE -> GRASS;
            case OTHER -> PAVEMENT;
        };
    }
}
