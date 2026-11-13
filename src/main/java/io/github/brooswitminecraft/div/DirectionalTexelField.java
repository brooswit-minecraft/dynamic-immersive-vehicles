package io.github.brooswitminecraft.div;

/**
 * Per-texel directional-grain evidence for one canonical texture (adapted from Dynamic Terrain's
 * {@code TexelField} - see {@code GroundSurface}'s class doc). Deliberately narrower than DT's
 * original: this ticket only needs the local luma-gradient magnitude along each texture axis
 * (high {@code xVariation}, low {@code yVariation} is a vertically-grained texture like log bark;
 * the reverse is horizontally-grained), not chroma or an isotropic contrast scalar, so that's all
 * {@link GrainAnalyzer} computes and this record stores.
 */
public record DirectionalTexelField(int width, int height, float[] xVariation, float[] yVariation) {

    public DirectionalTexelField {
        int expected = width * height;
        if (xVariation.length != expected || yVariation.length != expected) {
            throw new IllegalArgumentException("texel arrays must have length width*height");
        }
    }

    /** One texel's directional-grain evidence, each 0..1. */
    public record Sample(float xVariation, float yVariation) {}

    /** Nearest-texel sample at (u, v) in 0..1 texture space, wrapping like a tiled texture. */
    public Sample sample(double u, double v) {
        int x = wrap((int) Math.floor(u * width), width);
        int y = wrap((int) Math.floor(v * height), height);
        int i = y * width + x;
        return new Sample(xVariation[i], yVariation[i]);
    }

    private static int wrap(int v, int size) {
        int m = v % size;
        return m < 0 ? m + size : m;
    }
}
