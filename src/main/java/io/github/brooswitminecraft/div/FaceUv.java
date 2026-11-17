package io.github.brooswitminecraft.div;

/**
 * Turns a contact point (or travel vector) in block-local space (each coordinate 0..1, or a
 * direction with no fixed origin) into the 0..1 texture-space UV on the face it touches, using
 * vanilla's own per-face UV convention. This is what lets a tire crossing a face read the texels
 * it actually visits instead of an arbitrary world-aligned grid, and is what makes directional
 * grain (travelling along wood grain versus crossing it) emerge from the texture alone rather
 * than a hand-tuned per-block constant. Adapted from Dynamic Terrain's own {@code FaceUv} (see
 * {@code GroundSurface}'s class doc on this ticket's "inspect/extract, don't depend on" rule) -
 * same convention, plus {@link #direction} for a travel VECTOR rather than a point.
 */
public final class FaceUv {
    private FaceUv() {}
    DELIBERATE_CI_VALIDATION_SYNTAX_ERROR_MINECRAFT_550

    public record Uv(double u, double v) {}

    public static Uv of(Face face, double localX, double localY, double localZ) {
        return switch (face) {
            case UP -> new Uv(localX, 1 - localZ);
            case DOWN -> new Uv(localX, localZ);
            case NORTH -> new Uv(1 - localX, 1 - localY);
            case SOUTH -> new Uv(localX, 1 - localY);
            case WEST -> new Uv(localZ, 1 - localY);
            case EAST -> new Uv(1 - localZ, 1 - localY);
        };
    }

    /**
     * Same per-face axis convention as {@link #of}, but for a direction vector (e.g. a wheel's
     * rolling direction) rather than a point - the constant offsets (the {@code 1 -} terms) drop
     * out, leaving only the sign/axis each face's U/V tracks. {@code (0,0)} when the direction has
     * no component in this face's plane at all (e.g. travelling straight into/out of the face).
     */
    public static Uv direction(Face face, double dx, double dy, double dz) {
        return switch (face) {
            case UP -> new Uv(dx, -dz);
            case DOWN -> new Uv(dx, dz);
            case NORTH -> new Uv(-dx, -dy);
            case SOUTH -> new Uv(dx, -dy);
            case WEST -> new Uv(dz, -dy);
            case EAST -> new Uv(-dz, -dy);
        };
    }
}
