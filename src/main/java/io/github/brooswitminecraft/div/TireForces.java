package io.github.brooswitminecraft.div;
/** SI units throughout. One bounded tire budget shared by longitudinal/lateral forces. */
public final class TireForces {
 private TireForces() {}
 public record Force(double longitudinal, double lateral) {}
 public static double suspension(double compression, double normalSpeed, double spring, double damper) {
  return Math.max(0, compression * spring - normalSpeed * damper);
 }
 public static Force friction(double longitudinal, double lateral, double normal, double mu) {
  double budget = Math.max(0, normal * mu);
  double length = Math.hypot(longitudinal, lateral);
  double scale = length > budget && length > 0 ? budget / length : 1;
  return new Force(longitudinal * scale, lateral * scale);
 }
}
