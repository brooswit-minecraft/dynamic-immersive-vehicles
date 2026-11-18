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
 * fabricated. This event is only fired when that supported load is finite and strictly positive (a
 * wheel with zero or non-finite normal force has no real contact to report).
 */
public final class WheelGroundContactEvent extends Event {
    private final ServerLevel level;
    private final BlockPos blockPos;
    private final Direction face;
    private final double lateralSlipSpeedMetersPerSecond;
    private final double supportedLoadKg;
    private final long serverTick;

    public WheelGroundContactEvent(ServerLevel level, BlockPos blockPos, Direction face,
            double lateralSlipSpeedMetersPerSecond, double supportedLoadKg, long serverTick) {
        this.level = level;
        this.blockPos = blockPos;
        this.face = face;
        this.lateralSlipSpeedMetersPerSecond = lateralSlipSpeedMetersPerSecond;
        this.supportedLoadKg = supportedLoadKg;
        this.serverTick = serverTick;
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
