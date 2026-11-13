package io.github.brooswitminecraft.div;

/**
 * Pure 0-1 magnitude mapping for a configurable analog axis (ticket MINECRAFT-504's handbrake
 * path) - the same min/max-travel + inversion + deadzone shape IV's own {@code ConfigJoystick}
 * uses for its pedal axes (see {@code ControlsJoystick.getAxisState} in the installed jar),
 * reused here deliberately rather than inventing a different calibration scheme, but kept as an
 * independent input/config (own axis, own deadzone, own inversion) so it is never conflated with
 * service braking or the native digital parking-brake toggle.
 */
public final class WheelAxisCalibration {
 private WheelAxisCalibration() {}
 /**
  * Missing-channel-safe: a disabled axis ({@code axisMin==axisMax}, mirroring IV's own
  * "unconfigured" sentinel), a non-finite raw reading (disconnected device), or a reading
  * within the deadzone all return exactly 0.0 - never a stale or partial value.
  */
 public static double magnitude(double raw, double axisMin, double axisMax, boolean invert, double deadzone) {
  if (axisMin == axisMax || !Double.isFinite(raw)) return 0.0;
  double clamped = Math.max(Math.min(raw, Math.max(axisMin, axisMax)), Math.min(axisMin, axisMax));
  double travel = (clamped - axisMin) / (axisMax - axisMin);
  if (invert) travel = 1.0 - travel;
  // Deadzone is relative to the RELEASED end (travel==0) after inversion, not the raw axis -
  // inverting swaps which physical end that is, so the deadzone must follow it.
  if (travel < deadzone) return 0.0;
  return Math.max(0.0, Math.min(1.0, travel));
 }
}
