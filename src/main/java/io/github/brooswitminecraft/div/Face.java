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
 * <p>Rotation sense (independent review, PR #16 comment: NORTH with y=90 must become EAST, not
 * WEST - a log's own paired/symmetric faces conceal a handedness error, since swapping EAST/WEST
 * or NORTH/SOUTH changes nothing the log's own fixture can observe): Minecraft's blockstate
 * {@code x}/{@code y} rotation is CLOCKWISE when viewed from the positive end of that axis
 * looking toward the origin - the opposite sense from the textbook right-hand/CCW rotation
 * matrix. Verified against two real vanilla blockstates whose faces are NOT symmetric, so a
 * handedness error cannot hide the way it can on a log:
 * <ul>
 *  <li>{@code furnace} (default model front face points NORTH): {@code facing=east} is
 *  {@code y:90}, so {@code rotate(NORTH,0,90)} must be EAST.</li>
 *  <li>{@code dispenser} (default model front face also points NORTH): {@code facing=down} is
 *  {@code x:90}, so {@code rotate(NORTH,90,0)} must be DOWN.</li>
 * </ul>
 * Order: x about the X axis first, then y about the Y axis (both blockstate rotations, composed
 * in world space) - see {@code BlockModelResolverTest}'s {@code oak_log axis=x}/{@code axis=z}
 * fixture for the composed (both-axes) case.
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
        double[] afterX = rotateX(face.nx, face.ny, face.nz, xDegrees);
        double[] afterY = rotateY(afterX[0], afterX[1], afterX[2], yDegrees);
        return fromNormal((int) Math.round(afterY[0]), (int) Math.round(afterY[1]), (int) Math.round(afterY[2]));
    }

    /**
     * Same rotation as {@link #rotate}, generalized to an arbitrary vector rather than a face
     * normal - what a contact point or travel direction (NOT constrained to a unit axis) needs to
     * go from model-local space into the world space {@link #rotate}'s face already lives in.
     */
    public static double[] rotateVector(double x, double y, double z, int xDegrees, int yDegrees) {
        double[] afterX = rotateX(x, y, z, xDegrees);
        return rotateY(afterX[0], afterX[1], afterX[2], yDegrees);
    }

    /**
     * The inverse of {@link #rotateVector}: takes a WORLD-space vector (a block-local contact
     * point relative to the block center, or a travel direction) back into the MODEL-local space
     * the resolved face's own {@link FaceUv} convention actually describes. Reverses both the
     * rotation order and the angle sign ({@code Ry(-y)} first, then {@code Rx(-x)}) - the standard
     * inverse of a composed rotation.
     */
    public static double[] inverseRotateVector(double x, double y, double z, int xDegrees, int yDegrees) {
        double[] afterInvY = rotateY(x, y, z, -yDegrees);
        return rotateX(afterInvY[0], afterInvY[1], afterInvY[2], -xDegrees);
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

    private static double[] rotateX(double x, double y, double z, int degrees) {
        int c = cos(degrees), s = sin(degrees);
        return new double[] {x, y * c + z * s, -y * s + z * c};
    }

    private static double[] rotateY(double x, double y, double z, int degrees) {
        int c = cos(degrees), s = sin(degrees);
        return new double[] {x * c - z * s, y, x * s + z * c};
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
