package io.github.brooswitminecraft.div;

import java.awt.image.BufferedImage;

/**
 * Per-texel luma-gradient analysis of a canonical block texture (adapted from Dynamic Terrain's
 * {@code TextureAnalyzer} - see {@code GroundSurface}'s class doc). Deliberately does not reduce
 * a 16x16 texture to one number: every texel keeps its own local variation along X and Y, so
 * directional grain (wood) reads differently depending on which axis a tire crosses.
 */
public final class GrainAnalyzer {
    private GrainAnalyzer() {}

    public static DirectionalTexelField analyze(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        float[] luma = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                luma[y * w + x] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
            }
        }

        float[] xVariation = new float[w * h];
        float[] yVariation = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float left = luma[y * w + wrap(x - 1, w)];
                float right = luma[y * w + wrap(x + 1, w)];
                float up = luma[wrap(y - 1, h) * w + x];
                float down = luma[wrap(y + 1, h) * w + x];
                xVariation[i] = Math.abs(right - left) / 2f;
                yVariation[i] = Math.abs(down - up) / 2f;
            }
        }
        return new DirectionalTexelField(w, h, xVariation, yVariation);
    }

    private static int wrap(int v, int size) {
        int m = v % size;
        return m < 0 ? m + size : m;
    }
}
