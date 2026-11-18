package io.github.brooswitminecraft.div;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/**
 * Fired once per wheel, per tick, for every wheel with a real ground contact this tick (MINECRAFT-510,
 * direction/comment 55238/M55554611) - a generic, read-only observation of the EXACT contact
 * {@link PhysicsBridge.WheelHit} Phase 1 already measured and Phase 3 already consumed for the real
 * physics this tick. Carries no erosion/terrain-mutation policy itself: this mod owns no knowledge of
 * what, if anything, a listener does with it. A listener MUST NOT mutate the world (or anything else
 * with side effects) from inside this event's handler - it fires synchronously from inside the physics
 * step, not from a safe authoritative server-tick stage. A listener that needs to act on this data
 * should snapshot it into its own bounded queue and drain that queue later, on its own
 * {@code ServerTickEvent.Post} (or similar), after physics has fully finished for the tick.
 *
 * <p>{@link #blockPos()}/{@link #face()} are the EXACT raycast result Phase 1's own
 * {@code level.clip(...)} produced for this wheel this tick - never re-derived or floored from a
 * transformed/owner-relative contact point, which is not a reliable substitute for the original hit.
 * {@link #blockPos()} is defensively snapshotted via {@link BlockPos#immutable()} in {@link
 * #createIfValid} so a caller can never observe a later-mutated {@code BlockPos.MutableBlockPos}.
 *
 * <p>{@link #lateralSlipSpeedMetersPerSecond()} is this wheel's real TIRE-LOCAL lateral slip speed,
 * sampled at Phase 3 from the same {@code contactVelocity} Phase 3 itself uses for the real friction
 * solve this tick - {@code abs(contactVelocity.dot(right))} - not a vehicle-local Phase 1 velocity
 * component. This event makes no claim about longitudinal wheelspin/lockup; only lateral slip is
 * published.
 *
 * <p>{@link #supportedLoadKg()} is {@code normal / gravity} using this same tick's real, actual
 * per-dimension gravity magnitude ({@code DimensionPhysicsData.getGravity(level).length()}) and the
 * real, already-clamped-to-{@code mass*gravity} suspension normal force Phase 1 computed - never
 * fabricated. {@link #createIfValid} is the ONLY way to obtain an instance, and validates every
 * published value itself (not merely the raw {@code normal} input) - finite positive gravity, finite
 * non-negative slip, and a finite strictly-positive computed {@code supportedLoadKg} - returning
 * {@code null} (fire nothing) rather than ever publishing a non-finite or non-positive payload.
 */
public final class WheelGroundContactEvent extends Event {
    private final ServerLevel level;
    private final BlockPos blockPos;
    private final Direction face;
    private final double lateralSlipSpeedMetersPerSecond;
    private final double supportedLoadKg;
    private final long serverTick;

    private WheelGroundContactEvent(ServerLevel level, BlockPos blockPos, Direction face,
            double lateralSlipSpeedMetersPerSecond, double supportedLoadKg, long serverTick) {
        this.level = level;
        this.blockPos = blockPos;
        this.face = face;
        this.lateralSlipSpeedMetersPerSecond = lateralSlipSpeedMetersPerSecond;
        this.supportedLoadKg = supportedLoadKg;
        this.serverTick = serverTick;
    }

    /**
     * Validates every published value (not just the raw {@code normalForceN} input) and returns a
     * ready-to-post event, or {@code null} if this wheel has no real contact worth reporting this
     * tick. {@code gravityMetersPerSecondSquared} must be finite and strictly positive (the real
     * per-dimension value, never assumed); {@code normalForceN} must be finite and strictly
     * positive (a wheel with zero or non-finite normal force has no real contact); the resulting
     * {@code normalForceN / gravityMetersPerSecondSquared} must itself be finite and strictly
     * positive; and {@code rawLateralSlipMetersPerSecond} (NOT yet absolute-valued) must be finite
     * - its published {@link #lateralSlipSpeedMetersPerSecond()} is always {@code >= 0}.
     */
    public static WheelGroundContactEvent createIfValid(ServerLevel level, BlockPos blockPos, Direction face,
            double rawLateralSlipMetersPerSecond, double normalForceN, double gravityMetersPerSecondSquared,
            long serverTick) {
        if (blockPos == null || face == null) {
            return null;
        }
        if (!Double.isFinite(gravityMetersPerSecondSquared) || gravityMetersPerSecondSquared <= 0) {
            return null;
        }
        if (!Double.isFinite(normalForceN) || normalForceN <= 0) {
            return null;
        }
        if (!Double.isFinite(rawLateralSlipMetersPerSecond)) {
            return null;
        }
        double supportedLoadKg = normalForceN / gravityMetersPerSecondSquared;
        if (!Double.isFinite(supportedLoadKg) || supportedLoadKg <= 0) {
            return null;
        }
        double lateralSlipSpeedMetersPerSecond = Math.abs(rawLateralSlipMetersPerSecond);
        return new WheelGroundContactEvent(level, blockPos.immutable(), face,
                lateralSlipSpeedMetersPerSecond, supportedLoadKg, serverTick);
    }

    public ServerLevel level() {
        return level;
    }

    public BlockPos blockPos() {
        return blockPos;
    }

    public Direction face() {
        return face;
    }

    public double lateralSlipSpeedMetersPerSecond() {
        return lateralSlipSpeedMetersPerSecond;
    }

    public double supportedLoadKg() {
        return supportedLoadKg;
    }

    public long serverTick() {
        return serverTick;
    }
}
