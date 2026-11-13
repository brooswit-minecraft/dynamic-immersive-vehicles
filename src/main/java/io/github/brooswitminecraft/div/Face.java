package io.github.brooswitminecraft.div;

/**
 * The six block faces, independent of any Minecraft type so {@link FaceUv} and
 * {@link BlockModelResolver} stay plain Java, testable without the game (same discipline as
 * {@link GroundSurface}). Also carries the pure right-angle rotation math {@link #rotate} needs
 * to turn a model-local face into the world face it actually ends up on once a blockstate variant
 * rotates the model - the piece MINECRAFT-508 explicitly deferred ("per-pixel textures,
 * directional 16x16-per-face response and rotated-log orientation remain deferred") and this
 * ticket now owns.
 *
 * <p>Rotation order/sense (apply x about the X axis first, then y about the Y axis, both via the
 * standard right-handed rotation matrices on the face's own normal vector) was derived from, and
 * is verified against, the real vanilla {@code oak_log} blockstate's own {@code axis=x}/
 * {@code axis=z} entries - see {@code BlockModelResolverTest} for the exact fixture and expected
 * faces.
 */
public enum Face {
    UP(0, 1, 0), DOWN(0, -1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), EAST(1, 0, 0), WEST(-1, 0, 0);

    final int nx, ny, nz;

    Face(int nx, int ny, int nz) {
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
    }

    /**
     * @param xDegrees rotation about the X axis, must be a multiple of 90 (see {@link #isRightAngle})
     * @param yDegrees rotation about the Y axis, applied after {@code xDegrees}, same constraint
     */
    public static Face rotate(Face face, int xDegrees, int yDegrees) {
        int[] afterX = rotateX(face.nx, face.ny, face.nz, xDegrees);
        int[] afterY = rotateY(afterX[0], afterX[1], afterX[2], yDegrees);
        return fromNormal(afterY[0], afterY[1], afterY[2]);
    }

    /** Whether a rotation (as read straight from blockstate JSON, which may be any double) is a
     * multiple of 90 degrees - the only rotations {@link #rotate} knows how to carry through
     * honestly. Anything else (e.g. a 45-degree tilt) is genuinely unsupported: refuse, don't guess. */
    public static boolean isRightAngle(double degrees) {
        long rounded = Math.round(degrees);
        return Math.abs(degrees - rounded) < 1e-6 && normalize((int) rounded) % 90 == 0;
    }

    private static Face fromNormal(int x, int y, int z) {
        for (Face f : values()) {
            if (f.nx == x && f.ny == y && f.nz == z) {
                return f;
            }
        }
        throw new IllegalArgumentException("not a unit axis normal: (" + x + "," + y + "," + z + ")");
    }

    private static int[] rotateX(int x, int y, int z, int degrees) {
        int c = cos(degrees), s = sin(degrees);
        return new int[] {x, y * c - z * s, y * s + z * c};
    }

    private static int[] rotateY(int x, int y, int z, int degrees) {
        int c = cos(degrees), s = sin(degrees);
        return new int[] {x * c + z * s, y, -x * s + z * c};
    }

    private static int cos(int degrees) {
        return switch (normalize(degrees)) {
            case 0 -> 1;
            case 180 -> -1;
            default -> 0; // 90 or 270
        };
    }

    private static int sin(int degrees) {
        return switch (normalize(degrees)) {
            case 90 -> 1;
            case 270 -> -1;
            default -> 0; // 0 or 180
        };
    }

    private static int normalize(int degrees) {
        int m = degrees % 360;
        return m < 0 ? m + 360 : m;
    }
}
