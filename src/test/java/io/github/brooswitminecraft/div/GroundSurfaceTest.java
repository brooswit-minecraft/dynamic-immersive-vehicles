package io.github.brooswitminecraft.div;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the pure {@link GroundSurface#select} decision directly, with no Minecraft types in its
 * signature and no bootstrapped registries (same convention as {@code TireForcesTest}/
 * {@code PhysicsBridgeStepperTest}) - this is the whole decision {@link GroundSurface#at} makes
 * once a tag/sound-family have been read, so it exercises every branch of the MINECRAFT-508
 * bounded surface slice without any live game runtime.
 */
class GroundSurfaceTest {
    private static GroundSurface select(boolean pavement, boolean ice, boolean gravel, boolean grass, boolean dirt, GroundSurface.SoundFamily family) {
        return GroundSurface.select(pavement, ice, gravel, grass, dirt, family);
    }

    @Test void pavementTagWins() {
        assertEquals(GroundSurface.PAVEMENT, select(true, false, false, false, false, GroundSurface.SoundFamily.GRASS_LIKE));
    }

    @Test void iceTagWins() {
        assertEquals(GroundSurface.ICE, select(false, true, false, false, false, GroundSurface.SoundFamily.OTHER));
    }

    @Test void gravelTagWins() {
        assertEquals(GroundSurface.GRAVEL, select(false, false, true, false, false, GroundSurface.SoundFamily.OTHER));
    }

    @Test void grassTagWins() {
        assertEquals(GroundSurface.GRASS, select(false, false, false, true, false, GroundSurface.SoundFamily.OTHER));
    }

    @Test void dirtTagWins() {
        assertEquals(GroundSurface.DIRT, select(false, false, false, false, true, GroundSurface.SoundFamily.OTHER));
    }

    @Test void pavementTagBeatsEveryOtherTag() {
        assertEquals(GroundSurface.PAVEMENT, select(true, true, true, true, true, GroundSurface.SoundFamily.GRASS_LIKE));
    }

    @Test void untaggedGravelFamilyFallsBackToGravel() {
        assertEquals(GroundSurface.GRAVEL, select(false, false, false, false, false, GroundSurface.SoundFamily.GRAVEL_LIKE));
    }

    @Test void untaggedSandFamilyFallsBackToDirt() {
        assertEquals(GroundSurface.DIRT, select(false, false, false, false, false, GroundSurface.SoundFamily.SAND_LIKE));
    }

    @Test void untaggedGrassFamilyFallsBackToGrass() {
        assertEquals(GroundSurface.GRASS, select(false, false, false, false, false, GroundSurface.SoundFamily.GRASS_LIKE));
    }

    /** A genuinely unsupported surface (e.g. metal, wood, glass) falls back to the documented
     * pavement baseline - matches Dynamic Terrain's own baseline-fallback convention. */
    @Test void unsupportedFamilyFallsBackToPavement() {
        assertEquals(GroundSurface.PAVEMENT, select(false, false, false, false, false, GroundSurface.SoundFamily.OTHER));
    }

    @Test void deterministicAcrossRepeatedCalls() {
        GroundSurface first = select(false, false, true, false, false, GroundSurface.SoundFamily.OTHER);
        GroundSurface second = select(false, false, true, false, false, GroundSurface.SoundFamily.OTHER);
        assertEquals(first, second);
        assertSame(GroundSurface.GRAVEL, first);
        assertSame(GroundSurface.GRAVEL, second);
    }

    @Test void iceHasNearZeroGripUnlikeAnyDynamicTerrainFamily() {
        assertTrue(GroundSurface.ICE.frictionMultiplier() < 0.1);
        assertTrue(GroundSurface.ICE.frictionMultiplier() < GroundSurface.PAVEMENT.frictionMultiplier());
    }
}
