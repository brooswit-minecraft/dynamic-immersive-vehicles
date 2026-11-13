package io.github.brooswitminecraft.div;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * GroundSurfaceOverrides.load()/reload() read FML's config directory and cannot run outside a
 * modded runtime (same reasoning as OverridesTest) - these tests exercise what can run as a
 * plain unit test: the pure effective()/validate() logic, via the setForTest seam. The loader's
 * file I/O and JSON parsing remain covered only by the runtime-smoke/acceptance path, same as
 * Overrides' own reload().
 */
class GroundSurfaceOverridesTest {
    @AfterEach void resetToDefaults() {
        GroundSurfaceOverrides.setForTest(Map.of());
    }

    @Test void noConfiguredEntryReturnsFallbackUnchanged() {
        assertEquals(GroundSurface.ICE, GroundSurfaceOverrides.effective("ice", GroundSurface.ICE));
    }

    @Test void fullOverrideReplacesBothFields() {
        var entry = new GroundSurfaceOverrides.Entry();
        entry.frictionMultiplier = 0.42;
        entry.rollingResistanceMultiplier = 1.5;
        GroundSurfaceOverrides.setForTest(Map.of("ice", entry));
        var result = GroundSurfaceOverrides.effective("ice", GroundSurface.ICE);
        assertEquals(0.42, result.frictionMultiplier());
        assertEquals(1.5, result.rollingResistanceMultiplier());
    }

    @Test void partialOverrideFallsBackOnlyForTheOmittedField() {
        var entry = new GroundSurfaceOverrides.Entry();
        entry.frictionMultiplier = 0.9;
        // rollingResistanceMultiplier left null - must fall back to GroundSurface.DIRT's own value.
        GroundSurfaceOverrides.setForTest(Map.of("dirt", entry));
        var result = GroundSurfaceOverrides.effective("dirt", GroundSurface.DIRT);
        assertEquals(0.9, result.frictionMultiplier());
        assertEquals(GroundSurface.DIRT.rollingResistanceMultiplier(), result.rollingResistanceMultiplier());
    }

    @Test void overrideForADifferentSurfaceNameDoesNotAffectThisOne() {
        var entry = new GroundSurfaceOverrides.Entry();
        entry.frictionMultiplier = 0.1;
        GroundSurfaceOverrides.setForTest(Map.of("ice", entry));
        assertEquals(GroundSurface.GRAVEL, GroundSurfaceOverrides.effective("gravel", GroundSurface.GRAVEL));
    }

    @Test void frictionMultiplierOutOfRangeFailsValidation() {
        var entry = new GroundSurfaceOverrides.Entry();
        entry.frictionMultiplier = -1.0;
        assertThrows(IllegalArgumentException.class, entry::validate);
    }

    @Test void rollingResistanceMultiplierOutOfRangeFailsValidation() {
        var entry = new GroundSurfaceOverrides.Entry();
        entry.rollingResistanceMultiplier = 999.0;
        assertThrows(IllegalArgumentException.class, entry::validate);
    }

    @Test void bothFieldsNullIsAllowedAndValidates() {
        assertDoesNotThrow(new GroundSurfaceOverrides.Entry()::validate);
    }
}
