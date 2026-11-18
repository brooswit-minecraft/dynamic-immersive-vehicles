package io.github.brooswitminecraft.div;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Focused contract coverage (review M55554611 on MINECRAFT-510 comment 55238) that
 * {@code PhysicsBridge.WheelHit}'s {@code contactBlockPos}/{@code contactFace} - the EXACT Phase 1
 * raycast result - survive the post-Phase-1 record-rebuild helpers unchanged, exactly like every
 * other already-measured field. A bug here would mean a later consumer could receive a block
 * position/face that no longer matches the real raycast hit for this wheel this tick.
 */
class WheelHitContactProvenanceTest {
    private static final Vector3d ZERO = new Vector3d(0, 0, 0);
    private static final BlockPos POS = new BlockPos(7, 8, 9);
    private static final Direction FACE = Direction.NORTH;

    private PhysicsBridge.WheelHit newHit() {
        return new PhysicsBridge.WheelHit(null, 0.4, ZERO, ZERO, ZERO, 0.6, 0.1, ZERO, 100.0,
                ZERO, ZERO, ZERO, 1.0, 0.0, 0.0, 0.0, Double.NaN, false, GroundSurface.DIRT, 0,
                POS, FACE);
    }

    @Test
    void withNormalPreservesContactProvenanceUnchanged() {
        var hit = newHit();
        var rebuilt = hit.withNormal(250.0);
        assertEquals(250.0, rebuilt.normal());
        assertSame(POS, rebuilt.contactBlockPos());
        assertSame(FACE, rebuilt.contactFace());
    }

    @Test
    void withDriveShareRatioPreservesContactProvenanceUnchanged() {
        var hit = newHit();
        var rebuilt = hit.withDriveShareRatio(0.75);
        assertEquals(0.75, rebuilt.driveShareRatio());
        assertSame(POS, rebuilt.contactBlockPos());
        assertSame(FACE, rebuilt.contactFace());
    }
}
