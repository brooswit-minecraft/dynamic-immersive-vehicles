package io.github.brooswitminecraft.div;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DirectionalGroundSurface#combine}, the pure core of the directional response - no
 * Minecraft types, so these cover the along-grain/cross-grain/fallback/bounded-penalty behavior
 * directly, the same "extract the pure decision" discipline {@code GroundSurfaceTest} already
 * uses for {@code GroundSurface#select}. A dedicated "force-path" test at the bottom carries the
 * resulting {@code GroundSurface} into the real {@link TireForces} consumer, proving the
 * directional difference actually reaches the tire-force budget, not just an intermediate record.
 */
class DirectionalGroundSurfaceTest {
    private static final GroundSurface BASELINE = new GroundSurface(1.0, 2.0);

    /** A texture whose grain runs along V (high X-variation when crossing it, zero Y-variation
     * when travelling along it) - the idealized log-bark case. */
    private static DirectionalTexelField grainAlongV() {
        return new DirectionalTexelField(1, 1, new float[] {1f}, new float[] {0f});
    }

    @Test void missingTextureEvidenceLeavesBaselineUnchanged() {
        GroundSurface result = DirectionalGroundSurface.combine(BASELINE, Optional.empty(), 0.5, 0.5, 1, 0);
        assertEquals(BASELINE, result);
    }

    @Test void zeroTravelDirectionLeavesBaselineUnchanged() {
        GroundSurface result = DirectionalGroundSurface.combine(BASELINE, Optional.of(grainAlongV()), 0.5, 0.5, 0, 0);
        assertEquals(BASELINE, result);
    }

    @Test void travellingAlongGrainPreservesFullBaselineGrip() {
        // travel purely along V (the grain direction): yVariation is 0 there, so no penalty at all.
        GroundSurface result = DirectionalGroundSurface.combine(BASELINE, Optional.of(grainAlongV()), 0.5, 0.5, 0, 1);
        assertEquals(BASELINE.frictionMultiplier(), result.frictionMultiplier(), 1e-9);
    }

    @Test void travellingCrossGrainReducesGripBelowBaseline() {
        // travel purely along U (across the grain): xVariation is 1 there, the maximum penalty applies.
        GroundSurface result = DirectionalGroundSurface.combine(BASELINE, Optional.of(grainAlongV()), 0.5, 0.5, 1, 0);
        assertTrue(result.frictionMultiplier() < BASELINE.frictionMultiplier());
    }

    @Test void alongGrainGripExceedsCrossGrainGripOnTheSameTexture() {
        GroundSurface along = DirectionalGroundSurface.combine(BASELINE, Optional.of(grainAlongV()), 0.5, 0.5, 0, 1);
        GroundSurface cross = DirectionalGroundSurface.combine(BASELINE, Optional.of(grainAlongV()), 0.5, 0.5, 1, 0);
        assertTrue(along.frictionMultiplier() > cross.frictionMultiplier());
    }

    @Test void directionIsNormalizedSoMagnitudeDoesNotMatter() {
        GroundSurface unit = DirectionalGroundSurface.combine(BASELINE, Optional.of(grainAlongV()), 0.5, 0.5, 1, 0);
        GroundSurface scaled = DirectionalGroundSurface.combine(BASELINE, Optional.of(grainAlongV()), 0.5, 0.5, 50, 0);
        assertEquals(unit.frictionMultiplier(), scaled.frictionMultiplier(), 1e-9);
    }

    @Test void rollingResistanceIsNeverChangedByDirection() {
        GroundSurface cross = DirectionalGroundSurface.combine(BASELINE, Optional.of(grainAlongV()), 0.5, 0.5, 1, 0);
        assertEquals(BASELINE.rollingResistanceMultiplier(), cross.rollingResistanceMultiplier());
    }

    @Test void penaltyIsBoundedEvenAtMaximumRoughness() {
        GroundSurface worst = DirectionalGroundSurface.combine(BASELINE, Optional.of(grainAlongV()), 0.5, 0.5, 1, 0);
        double floor = BASELINE.frictionMultiplier() * (1.0 - DirectionalGroundSurface.MAX_GRAIN_PENALTY);
        assertEquals(floor, worst.frictionMultiplier(), 1e-9);
    }

    @Test void deterministicAcrossRepeatedCalls() {
        GroundSurface first = DirectionalGroundSurface.combine(BASELINE, Optional.of(grainAlongV()), 0.5, 0.5, 1, 0);
        GroundSurface second = DirectionalGroundSurface.combine(BASELINE, Optional.of(grainAlongV()), 0.5, 0.5, 1, 0);
        assertEquals(first, second);
    }

    /** Force-path: the directional grip difference must actually change what {@link TireForces}
     * is willing to deliver, not merely differ as an intermediate {@code GroundSurface} value. */
    @Test void crossGrainReducesTheActualTireForceBudget() {
        double mu = 0.6, normal = 500, longitudinalDemand = 1000;
        GroundSurface along = DirectionalGroundSurface.combine(BASELINE, Optional.of(grainAlongV()), 0.5, 0.5, 0, 1);
        GroundSurface cross = DirectionalGroundSurface.combine(BASELINE, Optional.of(grainAlongV()), 0.5, 0.5, 1, 0);

        TireForces.Force alongForce = TireForces.friction(longitudinalDemand, 0, normal, mu * along.frictionMultiplier());
        TireForces.Force crossForce = TireForces.friction(longitudinalDemand, 0, normal, mu * cross.frictionMultiplier());

        assertTrue(crossForce.longitudinal() < alongForce.longitudinal(),
                "cross-grain travel must deliver strictly less tire force than along-grain travel on the same texture");
    }
}
