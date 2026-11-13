package io.github.brooswitminecraft.div;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GrainAnalyzer} against synthetic images with a known, deliberately exaggerated grain
 * direction - a vertically-striped texture (columns alternate black/white, every row identical)
 * is the idealized version of a log's bark: high variation crossing columns (X), none crossing
 * rows (Y), the exact signal {@link DirectionalGroundSurface} reads as "along grain" vs
 * "cross grain".
 */
class GrainAnalyzerTest {
    /** Width-2 vertical bands (period 4), every row identical: a centered left/right difference
     * at a band BOUNDARY sees opposite-shade neighbors (unlike a naive period-2 single-column
     * alternation, where the left/right neighbors of any column are always the SAME shade as
     * each other and a centered difference cancels to zero everywhere). */
    private static BufferedImage verticalStripes(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int shade = (x / 2) % 2 == 0 ? 0x000000 : 0xFFFFFF;
                image.setRGB(x, y, 0xFF000000 | shade);
            }
        }
        return image;
    }

    private static BufferedImage uniform(int size, int rgb) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        return image;
    }

    @Test void verticalStripesHaveHighXVariationAndZeroYVariation() {
        DirectionalTexelField field = GrainAnalyzer.analyze(verticalStripes(16));
        for (int i = 0; i < field.xVariation().length; i++) {
            assertTrue(field.xVariation()[i] > 0.4f, "expected strong X variation at texel " + i);
            assertEquals(0f, field.yVariation()[i], 1e-6, "expected zero Y variation at texel " + i);
        }
    }

    @Test void uniformTextureHasNoVariationInEitherAxis() {
        DirectionalTexelField field = GrainAnalyzer.analyze(uniform(16, 0x808080));
        for (int i = 0; i < field.xVariation().length; i++) {
            assertEquals(0f, field.xVariation()[i], 1e-6);
            assertEquals(0f, field.yVariation()[i], 1e-6);
        }
    }

    @Test void deterministicAcrossRepeatedAnalysis() {
        BufferedImage image = verticalStripes(16);
        DirectionalTexelField first = GrainAnalyzer.analyze(image);
        DirectionalTexelField second = GrainAnalyzer.analyze(image);
        assertArrayEquals(first.xVariation(), second.xVariation());
        assertArrayEquals(first.yVariation(), second.yVariation());
    }

    @Test void samplingWrapsLikeATiledTexture() {
        DirectionalTexelField field = GrainAnalyzer.analyze(verticalStripes(16));
        // u=1.0 wraps back to the same texel as u=0.0.
        assertEquals(field.sample(0.0, 0.5), field.sample(1.0, 0.5));
        assertEquals(field.sample(0.0, 0.5), field.sample(-1.0, 0.5));
    }
}
