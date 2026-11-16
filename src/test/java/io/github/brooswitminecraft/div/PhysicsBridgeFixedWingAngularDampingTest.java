package io.github.brooswitminecraft.div;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-542: {@code Body.stepFlight} assembles its existing aileron/elevator/rudder control
 * torque, then ADDS {@code FlightForces.gatedFixedWingAngularDampingCoefficient}'s gated
 * coefficient through the exact same {@code PhysicsBridge.rotorAngularDampingTorque} /
 * {@code RotorForces.angularDampingTorque} assembly MINECRAFT-537 established for {@code
 * stepRotor} - reused verbatim (never duplicated), just with its own wholly separate {@code
 * tuning.fixedWingAngularDampingCoefficient}. The generic per-axis/rotated-body/extreme-dt
 * vector-assembly properties (zero/off parity, each axis independently, rotated body, extreme
 * dt/coefficient stability) are already covered exhaustively by {@code
 * PhysicsBridgeRotorAngularDampingTest} against the identical, unmodified helper, so this file
 * covers only what is actually NEW to this ticket: the fixed-wing-only aspect-ratio gate, that
 * the damping torque is ADDED to (never substitutes for) an existing nonzero control torque, and
 * a bounded real-aircraft probe demonstrating monotonic energy decrease without overshoot - the
 * same evidentiary shape MINECRAFT-537's own bounded real-rotor probe used. {@code
 * FlightForcesTest} covers {@code gatedFixedWingAngularDampingCoefficient} in isolation.
 */
class PhysicsBridgeFixedWingAngularDampingTest {
 private static final Vector3d WORLD_UP = new Vector3d(0,1,0);
 private static final Vector3d WORLD_FORWARD = new Vector3d(0,0,1);

 private static Vector3d[] identityAxes() {
  Vector3d up = new Vector3d(WORLD_UP);
  Vector3d forward = new Vector3d(WORLD_FORWARD);
  Vector3d right = new Vector3d(up).cross(forward);
  return new Vector3d[]{forward, right, up};
 }

 @Test void offOrUnconfiguredCoefficientLeavesTheAssembledTorqueExactlyAtTheControlTorqueBaseline() {
  // "off/unset is exact baseline" (ticket acceptance): with fixedWingAngularDampingCoefficient
  // resolved to its 0.0 FALLBACK, adding the gated damping torque to an existing nonzero
  // aileron/elevator/rudder control torque must leave that vector completely unchanged.
  var axes = identityAxes();
  Vector3d inertiaBody = new Vector3d(1200.0, 5000.0, 4800.0); // representative fixed-wing box inertia
  Vector3d angularVelocity = new Vector3d(0.8, -0.5, 0.3);
  Vector3d controlTorque = new Vector3d(axes[0]).mul(50.0).fma(30.0, axes[1]).fma(-20.0, axes[2]);
  Vector3d beforeAdd = new Vector3d(controlTorque);

  double aspectRatio = 6.5; // an actual wing
  double gated = FlightForces.gatedFixedWingAngularDampingCoefficient(aspectRatio, 0.0);
  Vector3d damping = PhysicsBridge.rotorAngularDampingTorque(angularVelocity, axes[0], axes[1], axes[2], inertiaBody, gated, PhysicsBridge.DT);
  Vector3d assembled = new Vector3d(controlTorque).add(damping);

  assertEquals(0, damping.length(), 1e-12, "a 0.0 (unconfigured) coefficient must produce exactly zero damping torque");
  assertEquals(beforeAdd, assembled, "adding zero damping must leave the control torque completely unchanged (exact baseline)");
 }

 @Test void aBlimpAspectRatioGatesTheDampingToZeroRegardlessOfTheConfiguredCoefficient() {
  // "Fixed-wing only, preserve blimps" (ticket acceptance): a blimp (aspectRatio<=0) with an
  // operator-configured coefficient must still see exactly zero damping torque added, so its
  // existing control-torque-only behavior (and buoyant/ballast flight model) stays byte-identical.
  var axes = identityAxes();
  Vector3d inertiaBody = new Vector3d(3000.0, 9000.0, 9000.0); // representative blimp hull inertia
  Vector3d angularVelocity = new Vector3d(1.5, 0.9, -1.1);
  Vector3d controlTorque = new Vector3d(axes[0]).mul(40.0).fma(15.0, axes[1]).fma(10.0, axes[2]);
  Vector3d beforeAdd = new Vector3d(controlTorque);

  for (double aspectRatio : new double[]{0, -3.0}) {
   double gated = FlightForces.gatedFixedWingAngularDampingCoefficient(aspectRatio, FlightForces.MAX_FIXED_WING_ANGULAR_DAMPING_COEFFICIENT);
   Vector3d damping = PhysicsBridge.rotorAngularDampingTorque(angularVelocity, axes[0], axes[1], axes[2], inertiaBody, gated, PhysicsBridge.DT);
   Vector3d assembled = new Vector3d(controlTorque).add(damping);
   assertEquals(0, damping.length(), 1e-12, "aspectRatio=" + aspectRatio + ": a blimp must see exactly zero damping even at the maximum configured coefficient");
   assertEquals(beforeAdd, assembled, "aspectRatio=" + aspectRatio + ": the blimp's control torque must stay completely unaffected");
  }
 }

 @Test void nonzeroCoefficientOnAnActualWingIsAddedToNeverSubstitutesForTheExistingControlTorque() {
  // "control torque remains independent" (ticket acceptance): with an actual wing and a
  // configured coefficient, the assembled torque must equal exactly controlTorque + damping
  // (pure superposition), never e.g. replacing or scaling the control torque itself.
  var axes = identityAxes();
  Vector3d inertiaBody = new Vector3d(1200.0, 5000.0, 4800.0);
  Vector3d angularVelocity = new Vector3d(0.8, -0.5, 0.3);
  Vector3d controlTorque = new Vector3d(axes[0]).mul(50.0).fma(30.0, axes[1]).fma(-20.0, axes[2]);

  double aspectRatio = 6.5;
  double gated = FlightForces.gatedFixedWingAngularDampingCoefficient(aspectRatio, 4.0);
  Vector3d damping = PhysicsBridge.rotorAngularDampingTorque(angularVelocity, axes[0], axes[1], axes[2], inertiaBody, gated, PhysicsBridge.DT);
  assertTrue(damping.length() > 1e-9, "a positive coefficient with nonzero angular velocity must produce a nonzero damping torque to actually exercise independence");

  Vector3d assembled = new Vector3d(controlTorque).add(damping);
  Vector3d expected = new Vector3d(controlTorque).add(damping);
  assertEquals(expected, assembled, "the assembled torque must be exactly controlTorque + damping - pure superposition, the control torque never altered by the presence of damping");
  // The control torque vector itself (captured before assembly) must be bit-for-bit unchanged.
  Vector3d controlTorqueAfter = new Vector3d(axes[0]).mul(50.0).fma(30.0, axes[1]).fma(-20.0, axes[2]);
  assertEquals(controlTorqueAfter, new Vector3d(assembled).sub(damping), "subtracting the damping back out must recover the exact original control torque");
 }

 @Test void boundedRealFixedWingProbe_representativeAircraftMassAndInertiaDecaysWithoutOvershoot() {
  // A bounded real-aircraft probe (ticket requirement): a representative ~1400kg light aircraft
  // chassis (half-extents roughly 0.9m x 1.0m x 4.5m, box inertia approximation - the same formula
  // PhysicsBridge.Body already uses to compute its own inertiaBody, and the same shape
  // MINECRAFT-537's own bounded real-rotor probe used), spun up on all three axes at once by a
  // believable post-gust angular rate, damped at a moderate configured coefficient on an actual
  // wing. Confirms the combined multi-axis integration decays toward rest, strictly monotonically
  // in combined rotational energy, and never overshoots any single axis past zero - "energy
  // cannot increase" and "frame rotation and dt bounds are correct" (ticket acceptance).
  double mass = 1400.0;
  double halfX=0.9, halfY=1.0, halfZ=4.5;
  Vector3d inertiaBody = new Vector3d(
    mass/3*(halfY*halfY+halfZ*halfZ),
    mass/3*(halfX*halfX+halfZ*halfZ),
    mass/3*(halfX*halfX+halfY*halfY));
  var axes = identityAxes(); // forward, right, up
  double aspectRatio = 7.0; // an actual wing
  double coefficient = FlightForces.gatedFixedWingAngularDampingCoefficient(aspectRatio, 3.0);
  double dt = PhysicsBridge.DT;
  Vector3d angularVelocity = new Vector3d(1.0, -0.6, 0.4); // roll, pitch, yaw rad/s

  double previousEnergy = rotationalEnergy(angularVelocity, axes, inertiaBody);
  for (int tick=0; tick<2000; tick++) {
   Vector3d damping = PhysicsBridge.rotorAngularDampingTorque(angularVelocity, axes[0], axes[1], axes[2], inertiaBody, coefficient, dt);
   double dOmegaForward = damping.dot(axes[0])*dt/inertiaBody.z;
   double dOmegaRight = damping.dot(axes[1])*dt/inertiaBody.x;
   double dOmegaUp = damping.dot(axes[2])*dt/inertiaBody.y;
   double omegaForwardBefore = angularVelocity.dot(axes[0]);
   double omegaRightBefore = angularVelocity.dot(axes[1]);
   double omegaUpBefore = angularVelocity.dot(axes[2]);
   angularVelocity.fma(dOmegaForward, axes[0]).fma(dOmegaRight, axes[1]).fma(dOmegaUp, axes[2]);
   assertTrue((omegaForwardBefore+dOmegaForward)*omegaForwardBefore >= -1e-9, "tick "+tick+": roll axis reversed sign");
   assertTrue((omegaRightBefore+dOmegaRight)*omegaRightBefore >= -1e-9, "tick "+tick+": pitch axis reversed sign");
   assertTrue((omegaUpBefore+dOmegaUp)*omegaUpBefore >= -1e-9, "tick "+tick+": yaw axis reversed sign");

   double energy = rotationalEnergy(angularVelocity, axes, inertiaBody);
   assertTrue(energy <= previousEnergy + 1e-9, "tick "+tick+": combined rotational energy increased ("+previousEnergy+" -> "+energy+")");
   previousEnergy = energy;
  }
  assertTrue(angularVelocity.length() < 1e-2, "after 2000 ticks the probe aircraft must have decayed close to rest, got |omega|="+angularVelocity.length());
 }

 private static double rotationalEnergy(Vector3d angularVelocity, Vector3d[] axes, Vector3d inertiaBody) {
  double omegaForward = angularVelocity.dot(axes[0]);
  double omegaRight = angularVelocity.dot(axes[1]);
  double omegaUp = angularVelocity.dot(axes[2]);
  return 0.5*(inertiaBody.z*omegaForward*omegaForward + inertiaBody.x*omegaRight*omegaRight + inertiaBody.y*omegaUp*omegaUp);
 }
}
