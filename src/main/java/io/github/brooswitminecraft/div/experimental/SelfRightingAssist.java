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
 * <p>Review (comment 53184) caught a real physics bug in the first version: {@code bodyUp x
 * worldUp} is a TOTAL-TILT correction, not roll-only - it also corrects pure pitch (silently
 * acting like an anti-wheelie assist under a "roll" label, which the user never asked for), its
 * direction rotates as the vehicle pitches even with zero roll, and it degenerates to the zero
 * vector at exactly zero bank, incorrectly dropping ALL damping right when it matters most
 * (killing a growing roll oscillation before it becomes a large bank angle).
 *
 * <p>Fixed by decomposing roll explicitly against the vehicle's own FIXED forward axis instead
 * of a cross product between two world-ish vectors: signed roll angle {@code atan2(bodyRight.y,
 * bodyUp.y)} measures rotation about {@code bodyForward} alone (verified algebraically: a pure
 * pitch, rotating only {@code bodyForward}/{@code bodyUp} about {@code bodyRight}, leaves
 * {@code bodyRight} unchanged - so {@code bodyRight.y} stays whatever it already was, and if it
 * started at exactly 0 (upright, unyawed) it STAYS 0 under pure pitch, correctly reporting zero
 * roll regardless of pitch). Both restoring torque and damping act along {@code bodyForward},
 * which is always well-defined (never degenerates), so damping is continuous straight through
 * zero bank.
 *
 * <p>Units: torque in N*m (newton-meters), consistent with every other torque this mod computes
 * via {@code inertiaBody}-divided angular impulses; damping in N*m*s/rad (a true damping
 * coefficient on roll rate, not a dimensionless multiplier); the two fade angles in degrees.
 *
 * <p>Gating, each independently required (comment 53182's explicit list), PLUS a hard,
 * non-configurable safety cap (comment 53184: an arbitrary {@code fadeEndDegrees} up to 180
 * would otherwise defeat the "near-inverted gets zero" intent for a careless config - this cap
 * applies regardless of what {@code fadeEndDegrees} is set to):
 * <ul>
 *  <li>{@code occupied} - a rider/driver must actually be present; this is driver assistance,
 *  not a passive stabilizer on an empty parked vehicle.</li>
 *  <li>{@code grounded} - at least one wheel must have valid support; airborne vehicles get
 *  zero assist, never an automatic mid-air recovery.</li>
 *  <li>Bank angle fade - full strength up to {@code fadeStartDegrees}, linearly fading to
 *  exactly zero at {@code fadeEndDegrees}.</li>
 *  <li>{@link #HARD_SAFETY_CAP_DEGREES} - zero assist at or beyond this absolute roll magnitude
 *  no matter how {@code fadeEndDegrees} is configured; a vehicle this far over is not "basically
 *  upright" by any reasonable config.</li>
 * </ul>
 *
 * <p>No pose snapping anywhere: the only output is a torque (N*m), applied through the same
 * impulse-and-inertia-division path as every other force in this mod - never a direct
 * orientation/quaternion write.
 */
public final class SelfRightingAssist {
 private SelfRightingAssist() {}
 /** Absolute roll-magnitude cutoff, independent of any configured {@code fadeEndDegrees} - see
  * class doc. 90deg (fully on its side) is already far past "basically upright"; chosen as the
  * hard ceiling so a careless {@code fadeEndDegrees} config can only ever narrow the assisted
  * range, never widen it past this. */
 public static final double HARD_SAFETY_CAP_DEGREES = 90.0;
 /** @param strength N*m of restoring torque per unit of {@code sin(rollAngle)} at full fade.
  * @param damping N*m*s/rad opposing roll RATE (the angular velocity component along the
  *  vehicle's own forward axis) - pure damping, never driving rotation on its own (zero at zero
  *  roll rate), continuous through zero bank.
  * @param maxTorque N*m hard clamp on the combined (restoring+damping) output magnitude.
  * @param fadeStartDegrees roll-magnitude below which the assist runs at full strength.
  * @param fadeEndDegrees roll-magnitude at and beyond which the configured fade reaches zero -
  *  still subject to {@link #HARD_SAFETY_CAP_DEGREES} regardless of this value. */
 public record Config(double strength, double damping, double maxTorque, double fadeStartDegrees, double fadeEndDegrees) {
  public Config {
   if (!(Double.isFinite(strength) && strength>=0)) throw new IllegalArgumentException("strength must be finite and >=0");
   if (!(Double.isFinite(damping) && damping>=0)) throw new IllegalArgumentException("damping must be finite and >=0");
   if (!(Double.isFinite(maxTorque) && maxTorque>=0)) throw new IllegalArgumentException("maxTorque must be finite and >=0");
   if (!(Double.isFinite(fadeStartDegrees) && fadeStartDegrees>=0 && fadeStartDegrees<=HARD_SAFETY_CAP_DEGREES)) throw new IllegalArgumentException("fadeStartDegrees out of range");
   if (!(Double.isFinite(fadeEndDegrees) && fadeEndDegrees>fadeStartDegrees && fadeEndDegrees<=HARD_SAFETY_CAP_DEGREES)) throw new IllegalArgumentException("fadeEndDegrees must exceed fadeStartDegrees and be within the hard safety cap");
  }
 }
 /** The disabled/off config - every call to {@link #torque} with this returns exactly zero,
  * regardless of state, used as the sparse metadata's own default (opt-in, never implicit). */
 public static final Config OFF = new Config(0,0,0,0,HARD_SAFETY_CAP_DEGREES);

 /** @param bodyForward this vehicle's own current forward axis, world-frame (the fixed roll
  *  axis torque/damping act along).
  * @param bodyUp this vehicle's own current up axis, world-frame.
  * @param bodyRight this vehicle's own current right axis, world-frame. */
 public static Vector3d torque(Config config, Vector3d bodyForward, Vector3d bodyUp, Vector3d bodyRight, Vector3d angularVelocity, boolean occupied, boolean grounded) {
  if (config.strength()==0 && config.damping()==0) return new Vector3d(0,0,0);
  if (!occupied || !grounded) return new Vector3d(0,0,0);
  // Signed roll about bodyForward alone, independent of pitch - see class doc for the
  // algebraic justification. Well-defined (never degenerates) at any bank including exactly 0.
  double rollRadians=Math.atan2(bodyRight.y, bodyUp.y);
  double rollDegrees=Math.toDegrees(rollRadians);
  double absRollDegrees=Math.abs(rollDegrees);
  if (absRollDegrees>=HARD_SAFETY_CAP_DEGREES || absRollDegrees>=config.fadeEndDegrees()) return new Vector3d(0,0,0);
  double fade = absRollDegrees<=config.fadeStartDegrees() ? 1.0
    : 1.0 - (absRollDegrees-config.fadeStartDegrees())/(config.fadeEndDegrees()-config.fadeStartDegrees());
  Vector3d forwardUnit=new Vector3d(bodyForward).normalize();
  double restoringMagnitude=-config.strength()*fade*Math.sin(rollRadians); // negative sign: drives rollRadians toward zero
  double rollRate=angularVelocity.dot(forwardUnit); // continuous through zero bank - forwardUnit never degenerates
  double dampingMagnitude=-config.damping()*fade*rollRate;
  Vector3d total=new Vector3d(forwardUnit).mul(restoringMagnitude+dampingMagnitude);
  double totalLength=total.length();
  if (totalLength>config.maxTorque() && totalLength>0) total.mul(config.maxTorque()/totalLength);
  return total;
 }
}
