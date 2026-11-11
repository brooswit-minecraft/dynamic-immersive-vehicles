package io.github.brooswitminecraft.div.experimental;
import org.joml.Vector3d;

/**
 * Explicit, opt-in roll-restoring assist (ticket comment 53182 - the user's own words: "for
 * things like the atv and other ridables like motorcycles should probably have some sorta self
 * righting forces to cheat a little bit"). This EXPLICITLY SUPERSEDES this investigation's
 * earlier blanket "no arbitrary anti-roll clamp" stance for vehicles that opt in - but per
 * comment 53182, "not hidden universal uprighting": disabled unless a vehicle's own sparse
 * {@code Overrides.Tuning} metadata explicitly configures it (no inferred ATV/car
 * classification - the installed definitions have no such discriminator), and even then bounded
 * and fading so falls remain genuinely possible.
 *
 * <p>Upright reference: the body's own local up axis (world-frame, after orientation) versus
 * world up {@code (0,1,0)} - bank angle is {@code acos(bodyUp.dot(worldUp))}, the same
 * coordinate-independent tilt measure used throughout this investigation. Units: torque in
 * N*m (newton-meters), consistent with every other torque this mod computes via
 * {@code inertiaBody}-divided angular impulses; damping in N*m*s/rad (a true damping
 * coefficient on roll rate, not a dimensionless multiplier); the two fade angles in degrees.
 *
 * <p>Gating, each independently required (comment 53182's explicit list):
 * <ul>
 *  <li>{@code occupied} - a rider/driver must actually be present; this is driver assistance,
 *  not a passive stabilizer on an empty parked vehicle.</li>
 *  <li>{@code grounded} - at least one wheel must have valid support; airborne vehicles get
 *  zero assist, never an automatic mid-air recovery.</li>
 *  <li>Bank angle fade - full strength up to {@code fadeStartDegrees}, linearly fading to
 *  exactly zero at {@code fadeEndDegrees} (inverted or near-inverted vehicles get none at all -
 *  this is corrective assistance for a vehicle that is still basically upright, not a recovery
 *  system for one that has already properly flipped).</li>
 * </ul>
 *
 * <p>No pose snapping anywhere: the only output is a torque (N*m), applied through the same
 * impulse-and-inertia-division path as every other force in this mod - never a direct
 * orientation/quaternion write.
 */
public final class SelfRightingAssist {
 private SelfRightingAssist() {}
 /** @param strength N*m of restoring torque per unit of {@code sin(bankAngle)} (the magnitude
  *  of {@code bodyUp x worldUp}) at full fade.
  * @param damping N*m*s/rad opposing roll RATE (the angular velocity component along the
  *  restoring axis) - pure damping, never driving rotation on its own (zero at zero roll rate).
  * @param maxTorque N*m hard clamp on the combined (restoring+damping) output magnitude.
  * @param fadeStartDegrees bank angle below which the assist runs at full strength.
  * @param fadeEndDegrees bank angle at and beyond which the assist is exactly zero. */
 public record Config(double strength, double damping, double maxTorque, double fadeStartDegrees, double fadeEndDegrees) {
  public Config {
   if (!(Double.isFinite(strength) && strength>=0)) throw new IllegalArgumentException("strength must be finite and >=0");
   if (!(Double.isFinite(damping) && damping>=0)) throw new IllegalArgumentException("damping must be finite and >=0");
   if (!(Double.isFinite(maxTorque) && maxTorque>=0)) throw new IllegalArgumentException("maxTorque must be finite and >=0");
   if (!(Double.isFinite(fadeStartDegrees) && fadeStartDegrees>=0 && fadeStartDegrees<=180)) throw new IllegalArgumentException("fadeStartDegrees out of range");
   if (!(Double.isFinite(fadeEndDegrees) && fadeEndDegrees>fadeStartDegrees && fadeEndDegrees<=180)) throw new IllegalArgumentException("fadeEndDegrees must exceed fadeStartDegrees and be <=180");
  }
 }
 /** The disabled/off config - every call to {@link #torque} with this returns exactly zero,
  * regardless of state, used as the sparse metadata's own default (opt-in, never implicit). */
 public static final Config OFF = new Config(0,0,0,0,180);

 public static Vector3d torque(Config config, Vector3d bodyUp, Vector3d worldUp, Vector3d angularVelocity, boolean occupied, boolean grounded) {
  if (config.strength()==0 && config.damping()==0) return new Vector3d(0,0,0);
  if (!occupied || !grounded) return new Vector3d(0,0,0);
  double cosBank=Math.max(-1,Math.min(1, bodyUp.dot(worldUp)));
  double bankDegrees=Math.toDegrees(Math.acos(cosBank));
  if (bankDegrees>=config.fadeEndDegrees()) return new Vector3d(0,0,0);
  double fade = bankDegrees<=config.fadeStartDegrees() ? 1.0
    : 1.0 - (bankDegrees-config.fadeStartDegrees())/(config.fadeEndDegrees()-config.fadeStartDegrees());
  Vector3d axis=new Vector3d(bodyUp).cross(worldUp); // direction+magnitude ~ sin(bank); zero exactly at zero bank
  Vector3d restoring=new Vector3d(axis).mul(config.strength()*fade);
  double axisLength=axis.length();
  Vector3d damping=new Vector3d(0,0,0);
  if (axisLength>1e-9) {
   Vector3d axisUnit=new Vector3d(axis).div(axisLength);
   double rollRate=angularVelocity.dot(axisUnit); // component of angular velocity along the restoring axis only - never touches yaw/steering
   damping=new Vector3d(axisUnit).mul(-config.damping()*rollRate*fade);
  }
  Vector3d total=new Vector3d(restoring).add(damping);
  double totalLength=total.length();
  if (totalLength>config.maxTorque() && totalLength>0) total.mul(config.maxTorque()/totalLength);
  return total;
 }
}
