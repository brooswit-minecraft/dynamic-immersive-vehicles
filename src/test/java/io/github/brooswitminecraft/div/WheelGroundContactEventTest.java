package io.github.brooswitminecraft.div;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Focused contract coverage for {@link WheelGroundContactEvent#createIfValid} (review M55554611 on
 * MINECRAFT-510 comment 55238): the published values themselves must be validated, not merely the
 * raw {@code normalForceN} input, and the factory must return {@code null} (fire nothing) for any
 * non-finite or non-positive input or computed result.
 */
class WheelGroundContactEventTest {
    private static final BlockPos POS = new BlockPos(1, 2, 3);
    private static final Direction FACE = Direction.UP;
    private static final double GRAVITY = 9.8;

    @Test
    void validInputsProduceExpectedPublishedValues() {
        var event = WheelGroundContactEvent.createIfValid(null, POS, FACE, -3.5, 49.0, GRAVITY, 42L);
        assertEquals(POS, event.blockPos());
        assertEquals(FACE, event.face());
        assertEquals(3.5, event.lateralSlipSpeedMetersPerSecond());
        assertEquals(49.0 / GRAVITY, event.supportedLoadKg());
        assertEquals(42L, event.serverTick());
    }

    @Test
    void publishedLateralSlipIsAlwaysNonNegativeRegardlessOfRawSign() {
        var positive = WheelGroundContactEvent.createIfValid(null, POS, FACE, 2.0, 49.0, GRAVITY, 1L);
        var negative = WheelGroundContactEvent.createIfValid(null, POS, FACE, -2.0, 49.0, GRAVITY, 1L);
        assertEquals(2.0, positive.lateralSlipSpeedMetersPerSecond());
        assertEquals(2.0, negative.lateralSlipSpeedMetersPerSecond());
    }

    @Test
    void zeroOrNegativeNormalForceIsRejected() {
        assertNull(WheelGroundContactEvent.createIfValid(null, POS, FACE, 1.0, 0.0, GRAVITY, 1L));
        assertNull(WheelGroundContactEvent.createIfValid(null, POS, FACE, 1.0, -5.0, GRAVITY, 1L));
    }

    @Test
    void nonFiniteNormalForceIsRejected() {
        assertNull(WheelGroundContactEvent.createIfValid(null, POS, FACE, 1.0, Double.NaN, GRAVITY, 1L));
        assertNull(WheelGroundContactEvent.createIfValid(null, POS, FACE, 1.0, Double.POSITIVE_INFINITY, GRAVITY, 1L));
    }

    @Test
    void zeroOrNegativeOrNonFiniteGravityIsRejected() {
        assertNull(WheelGroundContactEvent.createIfValid(null, POS, FACE, 1.0, 49.0, 0.0, 1L));
        assertNull(WheelGroundContactEvent.createIfValid(null, POS, FACE, 1.0, 49.0, -9.8, 1L));
        assertNull(WheelGroundContactEvent.createIfValid(null, POS, FACE, 1.0, 49.0, Double.NaN, 1L));
    }

    @Test
    void nonFiniteRawLateralSlipIsRejected() {
        assertNull(WheelGroundContactEvent.createIfValid(null, POS, FACE, Double.NaN, 49.0, GRAVITY, 1L));
        assertNull(WheelGroundContactEvent.createIfValid(null, POS, FACE, Double.POSITIVE_INFINITY, 49.0, GRAVITY, 1L));
    }

    @Test
    void missingBlockPosOrFaceIsRejected() {
        assertNull(WheelGroundContactEvent.createIfValid(null, null, FACE, 1.0, 49.0, GRAVITY, 1L));
        assertNull(WheelGroundContactEvent.createIfValid(null, POS, null, 1.0, 49.0, GRAVITY, 1L));
    }

    @Test
    void blockPosIsDefensivelySnapshottedImmutable() {
        var mutable = new BlockPos.MutableBlockPos(5, 5, 5);
        var event = WheelGroundContactEvent.createIfValid(null, mutable, FACE, 1.0, 49.0, GRAVITY, 1L);
        mutable.set(99, 99, 99);
        assertEquals(new BlockPos(5, 5, 5), event.blockPos());
    }
}
