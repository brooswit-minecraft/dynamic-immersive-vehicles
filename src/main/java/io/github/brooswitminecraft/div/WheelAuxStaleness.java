package io.github.brooswitminecraft.div;

/**
 * Pure staleness-timeout arithmetic for the wheel-aux inputs (ticket MINECRAFT-504, comment
 * 53603: server lifecycle release/bounded heartbeat expiry) - extracted so the actual
 * threshold comparison {@link PhysicsBridge} relies on is independently testable without a
 * live tick counter or entity.
 */
public final class WheelAuxStaleness {
 private WheelAuxStaleness() {}
 public static boolean isStale(long lastUpdateTick, long currentTick, long timeoutTicks) {
  return currentTick - lastUpdateTick > timeoutTicks;
 }
}
