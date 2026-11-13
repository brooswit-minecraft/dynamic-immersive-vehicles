package io.github.brooswitminecraft.div;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Face#rotate}'s right-angle rotation math, verified against the real vanilla
 * {@code oak_log} blockstate's own {@code axis=x}/{@code axis=y}/{@code axis=z} variants (see
 * {@code BlockModelResolverTest} for the end-to-end resolution using this same fixture shape):
 * vertical ({@code axis=y}) is the identity (no rotation), {@code axis=z} rotates x=90 only
 * (end-grain ends up facing north/south), {@code axis=x} rotates x=90,y=90 (end-grain ends up
 * facing east/west).
 */
class FaceTest {
    @Test void identityRotationChangesNothing() {
        for (Face face : Face.values()) {
            assertEquals(face, Face.rotate(face, 0, 0));
        }
    }

    @Test void axisZLogRotationMovesEndGrainToNorthSouth() {
        // model UP/DOWN carry the end-grain texture on a standing log (see BlockModelResolverTest).
        assertEquals(Face.SOUTH, Face.rotate(Face.UP, 90, 0));
        assertEquals(Face.NORTH, Face.rotate(Face.DOWN, 90, 0));
        // model NORTH/SOUTH (side/bark texture) end up as up/down.
        assertEquals(Face.UP, Face.rotate(Face.NORTH, 90, 0));
        assertEquals(Face.DOWN, Face.rotate(Face.SOUTH, 90, 0));
        // east/west (side texture) are unaffected by a pure X rotation.
        assertEquals(Face.EAST, Face.rotate(Face.EAST, 90, 0));
        assertEquals(Face.WEST, Face.rotate(Face.WEST, 90, 0));
    }

    @Test void axisXLogRotationMovesEndGrainToEastWest() {
        assertEquals(Face.EAST, Face.rotate(Face.UP, 90, 90));
        assertEquals(Face.WEST, Face.rotate(Face.DOWN, 90, 90));
        assertEquals(Face.UP, Face.rotate(Face.NORTH, 90, 90));
        assertEquals(Face.DOWN, Face.rotate(Face.SOUTH, 90, 90));
        assertEquals(Face.NORTH, Face.rotate(Face.EAST, 90, 90));
        assertEquals(Face.SOUTH, Face.rotate(Face.WEST, 90, 90));
    }

    @Test void everyFaceRotationIsItsOwnPermutationOfAllSixFaces() {
        // A rotation must be a bijection over the six faces: no two model faces may collide onto
        // the same world face, and nothing may vanish.
        for (int x = 0; x < 360; x += 90) {
            for (int y = 0; y < 360; y += 90) {
                var seen = java.util.EnumSet.noneOf(Face.class);
                for (Face face : Face.values()) {
                    assertTrue(seen.add(Face.rotate(face, x, y)), "x=" + x + " y=" + y + " collided");
                }
            }
        }
    }

    @Test void fullTurnIsIdentity() {
        for (Face face : Face.values()) {
            assertEquals(face, Face.rotate(face, 360, 360));
            assertEquals(face, Face.rotate(face, -360, 0));
        }
    }

    @Test void rightAngleDegreesRecognizesAllFourQuadrants() {
        assertTrue(Face.isRightAngle(0));
        assertTrue(Face.isRightAngle(90));
        assertTrue(Face.isRightAngle(180));
        assertTrue(Face.isRightAngle(270));
        assertTrue(Face.isRightAngle(-90));
        assertTrue(Face.isRightAngle(360));
    }

    @Test void nonRightAngleDegreesAreRejected() {
        assertFalse(Face.isRightAngle(45));
        assertFalse(Face.isRightAngle(1));
        assertFalse(Face.isRightAngle(89.999));
    }
}
