package io.github.brooswitminecraft.div;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FaceUvTest {
    @Test void upFaceMapsLocalXZToUv() {
        var uv = FaceUv.of(Face.UP, 0.25, 1.0, 0.75);
        assertEquals(0.25, uv.u(), 1e-9);
        assertEquals(0.25, uv.v(), 1e-9);
    }

    @Test void downFaceMapsLocalXZToUvWithoutFlippingV() {
        var uv = FaceUv.of(Face.DOWN, 0.25, 0.0, 0.75);
        assertEquals(0.25, uv.u(), 1e-9);
        assertEquals(0.75, uv.v(), 1e-9);
    }

    @Test void travelDirectionHasNoConstantOffset() {
        // Two points 0.1 apart on the UP face must produce the same delta as of(p2)-of(p1).
        var p1 = FaceUv.of(Face.NORTH, 0.2, 0.3, 0.0);
        var p2 = FaceUv.of(Face.NORTH, 0.3, 0.3, 0.0);
        var dir = FaceUv.direction(Face.NORTH, 0.1, 0.0, 0.0);
        assertEquals(p2.u() - p1.u(), dir.u(), 1e-9);
        assertEquals(p2.v() - p1.v(), dir.v(), 1e-9);
    }

    @Test void travelAlongZOnEastWestFacesOnlyAffectsU() {
        var east = FaceUv.direction(Face.EAST, 0, 0, 1);
        assertEquals(-1.0, east.u(), 1e-9);
        assertEquals(0.0, east.v(), 1e-9);
        var west = FaceUv.direction(Face.WEST, 0, 0, 1);
        assertEquals(1.0, west.u(), 1e-9);
        assertEquals(0.0, west.v(), 1e-9);
    }

    @Test void travelStraightIntoAFaceProducesZeroUv() {
        // Moving purely along Y has no component in the UP/DOWN face's own plane.
        var uv = FaceUv.direction(Face.UP, 0, 1, 0);
        assertEquals(0.0, uv.u(), 1e-9);
        assertEquals(0.0, uv.v(), 1e-9);
    }
}
