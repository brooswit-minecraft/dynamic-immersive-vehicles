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

    /**
     * Self-review finding (PR #12 head 5fdc49f): {@code load()}'s own original throwing catch
     * block would abort DIV startup on a malformed/out-of-range config file, even though
     * {@code reload()} already handled the same input safely. The fix routes both through the
     * shared {@code apply(Supplier)} below - these tests exercise exactly that fallback for
     * "malformed JSON" (simulated as the parse supplier throwing, standing in for a real
     * {@code JsonSyntaxException} without needing Gson on the test classpath) and "out-of-range
     * value" (a real {@code Entry} failing its own {@code validate()}), at the same freshly-
     * defaulted state {@code load()} starts from - i.e. the specific gap the self-review found,
     * not just {@code reload()}'s already-covered last-known-good path.
     */
    @Test void malformedParseAtFreshDefaultStateFallsBackWithoutThrowing() {
        GroundSurfaceOverrides.setForTest(Map.of()); // the Map.of() state load() starts from
        GroundSurfaceOverrides.ReloadResult result = assertDoesNotThrow(
                () -> GroundSurfaceOverrides.apply(() -> { throw new RuntimeException("malformed json"); }));
        assertFalse(result.success());
        assertEquals(GroundSurface.ICE, GroundSurfaceOverrides.effective("ice", GroundSurface.ICE),
                "a malformed parse must never apply partial/garbage state - defaults stay in effect");
    }

    @Test void outOfRangeValueAtFreshDefaultStateFallsBackWithoutThrowing() {
        GroundSurfaceOverrides.setForTest(Map.of());
        var outOfRange = new GroundSurfaceOverrides.Entry();
        outOfRange.frictionMultiplier = -5.0;
        GroundSurfaceOverrides.ReloadResult result = assertDoesNotThrow(
                () -> GroundSurfaceOverrides.apply(() -> Map.of("ice", outOfRange)));
        assertFalse(result.success());
        assertEquals(GroundSurface.ICE, GroundSurfaceOverrides.effective("ice", GroundSurface.ICE),
                "an out-of-range field must never apply partial/garbage state - defaults stay in effect");
    }

    @Test void validParseSucceedsAndIsApplied() {
        var valid = new GroundSurfaceOverrides.Entry();
        valid.frictionMultiplier = 0.3;
        var result = GroundSurfaceOverrides.apply(() -> Map.of("ice", valid));
        assertTrue(result.success());
        assertEquals(0.3, GroundSurfaceOverrides.effective("ice", GroundSurface.ICE).frictionMultiplier());
    }

    /** A later malformed apply must preserve the earlier GOOD state, not reset to hardcoded
     * defaults - last-known-good, exactly {@code reload()}'s documented contract. */
    @Test void laterMalformedApplyKeepsThePriorGoodStateNotTheHardcodedDefault() {
        var good = new GroundSurfaceOverrides.Entry();
        good.frictionMultiplier = 0.77;
        GroundSurfaceOverrides.apply(() -> Map.of("ice", good));

        var badResult = GroundSurfaceOverrides.apply(() -> { throw new RuntimeException("malformed"); });

        assertFalse(badResult.success());
        assertEquals(0.77, GroundSurfaceOverrides.effective("ice", GroundSurface.ICE).frictionMultiplier(),
                "a later malformed apply must not discard the earlier good state");
    }
}
