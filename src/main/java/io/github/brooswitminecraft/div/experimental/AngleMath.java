package io.github.brooswitminecraft.div.experimental;

/**
 * Review finding (John, Jira comment 53717): {@code BuoyancyAngularDragProbe.measure} summed
 * {@code Math.abs(rollNow - lastRollDegrees)} directly - a naive difference between two angles
 * reported in {@code (-180,180]} (MTS's own {@code RotationMatrix.convertToAngles} range). When a
 * tumbling hull crosses the +-180 degree boundary (e.g. 179 to -179), that naive difference counts
 * 358 degrees of "activity" for what is physically only a 2-degree rotation - and the undamped
 * {@code off} scenario oscillates right around that boundary (settling near 180 degrees, roll
 * computed via {@code atan2}), so its reported cumulative activity (and the resulting reduction
 * ratio against {@code on}) was inflated by this artifact, not an honest measurement.
 *
 * <p>No Mojang/SLF4J/Minecraft imports here deliberately (unlike {@code
 * BuoyancyAngularDragProbe} itself, which cannot be loaded in a plain unit test for exactly that
 * reason - see {@code OverridesTest}'s own doc for the same constraint on {@code Gson}) - this
 * pure function is the one piece of that probe's own measurement logic that CAN be, and is,
 * unit-tested directly.
 */
final class AngleMath {
 private AngleMath() {}

 /** The shortest signed angular delta from {@code fromDegrees} to {@code toDegrees}, correctly
  * wrapped across the +-180 degree boundary - e.g. {@code shortestDeltaDegrees(179, -179)} is
  * {@code 2.0}, not {@code -358.0}. Always lies in {@code (-180,180]}. Works for any finite
  * inputs, not just values already restricted to a single wrap of the circle. */
 static double shortestDeltaDegrees(double fromDegrees, double toDegrees) {
  double raw = toDegrees - fromDegrees;
  return raw - 360.0 * Math.round(raw / 360.0);
 }
}
