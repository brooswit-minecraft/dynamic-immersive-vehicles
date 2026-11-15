package io.github.brooswitminecraft.div;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-537: {@code PhysicsBridge.rotorAngularDampingTorque} assembles {@code
 * RotorForces.angularDampingTorque}'s per-axis result into one WORLD-frame torque vector, the
 * same shape {@code Body.stepRotor} already assembles its cyclic/yaw control torque in, so it can
 * be directly added to that existing torque before the single shared {@code
 * handle.applyTorqueImpulse(torque.mul(DT))} call. Covers exactly the evidence the ticket names
 * at the vector/frame-assembly level: zero/off parity, each axis independently, a rotated body
 * (non-identity orientation), monotonic energy reduction simulated through this exact assembly,
 * extreme dt/coefficient stability, and a bounded real-rotor probe using a representative
 * helicopter-scale mass/inertia. {@code RotorForcesTest} covers the underlying per-axis scalar
 * function in isolation.
 */
class PhysicsBridgeRotorAngularDampingTest {
 private static final Vector3d WORLD_UP = new Vector3d(0,1,0);
 private static final Vector3d WORLD_FORWARD = new Vector3d(0,0,1);

 /** Body axes for an IDENTITY orientation - the simple, unrotated case every other test here
  * starts from, matching {@code Body.stepRotor}'s own {@code forward=q.transform(0,0,1)},
  * {@code up=q.transform(0,1,0)}, {@code right=up.cross(forward)}. */
 private static Vector3d[] identityAxes() {
  Vector3d up = new Vector3d(WORLD_UP);
  Vector3d forward = new Vector3d(WORLD_FORWARD);
  Vector3d right = new Vector3d(up).cross(forward);
  return new Vector3d[]{forward, right, up};
 }

 @Test void zeroCoefficientProducesExactlyZeroTorqueVector_zeroOffParity() {
  var axes = identityAxes();
  Vector3d angularVelocity = new Vector3d(1.5, -2.0, 3.0);
  Vector3d inertiaBody = new Vector3d(40.0, 55.0, 30.0);
  Vector3d damping = PhysicsBridge.rotorAngularDampingTorque(angularVelocity, axes[0], axes[1], axes[2], inertiaBody, 0.0, PhysicsBridge.DT);
  assertEquals(0, damping.x, 1e-12); assertEquals(0, damping.y, 1e-12); assertEquals(0, damping.z, 1e-12);
 }

 @Test void eachAxisContributesIndependently() {
  // Angular velocity along only ONE world axis at a time must produce a damping torque purely
  // along that same axis (at identity orientation, world axes == body axes) - roll/pitch/yaw
  // each damped on their own, never leaking into another axis.
  var axes = identityAxes(); // forward, right, up
  Vector3d inertiaBody = new Vector3d(40.0, 55.0, 30.0);

  Vector3d rollOnly = PhysicsBridge.rotorAngularDampingTorque(new Vector3d(axes[0]).mul(4.0), axes[0], axes[1], axes[2], inertiaBody, 2.0, PhysicsBridge.DT);
  assertTrue(rollOnly.length() > 1e-9, "a nonzero roll-axis rate must produce a nonzero damping torque");
  assertEquals(0, new Vector3d(rollOnly).cross(axes[0]).length(), 1e-9, "roll-only angular velocity must produce torque purely along the forward (roll) axis");

  Vector3d pitchOnly = PhysicsBridge.rotorAngularDampingTorque(new Vector3d(axes[1]).mul(-3.0), axes[0], axes[1], axes[2], inertiaBody, 2.0, PhysicsBridge.DT);
  assertTrue(pitchOnly.length() > 1e-9);
  assertEquals(0, new Vector3d(pitchOnly).cross(axes[1]).length(), 1e-9, "pitch-only angular velocity must produce torque purely along the right (pitch) axis");

  Vector3d yawOnly = PhysicsBridge.rotorAngularDampingTorque(new Vector3d(axes[2]).mul(2.5), axes[0], axes[1], axes[2], inertiaBody, 2.0, PhysicsBridge.DT);
  assertTrue(yawOnly.length() > 1e-9);
  assertEquals(0, new Vector3d(yawOnly).cross(axes[2]).length(), 1e-9, "yaw-only angular velocity must produce torque purely along the up (yaw) axis");
 }

 @Test void rotatedBodyProducesTheSameMagnitudeDampingRegardlessOfWorldOrientation() {
  // "rotated bodies": a body tumbled to an arbitrary orientation, spinning about its OWN local
  // roll axis at the same rate as the identity case, must get the exact same magnitude damping
  // torque (just pointed along the rotated world-space axis instead) - correctness depends only
  // on the per-axis dot-product projection, not on the body's absolute world orientation.
  Vector3d inertiaBody = new Vector3d(40.0, 55.0, 30.0);
  double rollRate = 4.0;

  var identity = identityAxes();
  Vector3d angularVelocityIdentity = new Vector3d(identity[0]).mul(rollRate);
  Vector3d dampingIdentity = PhysicsBridge.rotorAngularDampingTorque(angularVelocityIdentity, identity[0], identity[1], identity[2], inertiaBody, 2.0, PhysicsBridge.DT);

  // An arbitrary, non-trivial rotation (not axis-aligned) standing in for a "rotated body".
  Quaterniond q = new Quaterniond().rotateXYZ(Math.toRadians(37), Math.toRadians(-52), Math.toRadians(81));
  Vector3d forward = q.transform(new Vector3d(WORLD_FORWARD));
  Vector3d up = q.transform(new Vector3d(WORLD_UP));
  Vector3d right = new Vector3d(up).cross(forward);
  Vector3d angularVelocityRotated = new Vector3d(forward).mul(rollRate); // same LOCAL roll rate, rotated into world
  Vector3d dampingRotated = PhysicsBridge.rotorAngularDampingTorque(angularVelocityRotated, forward, right, up, inertiaBody, 2.0, PhysicsBridge.DT);

  assertEquals(dampingIdentity.length(), dampingRotated.length(), 1e-9,
    "the same local roll rate must produce the same-magnitude damping torque regardless of the body's world orientation");
  // And it must point along the ROTATED forward axis, not the original world forward.
  assertEquals(0, new Vector3d(dampingRotated).cross(forward).length(), 1e-9,
    "the rotated body's damping torque must point along its own (rotated) roll axis");
 }

 @Test void extremeDtAndCoefficientStayBoundedInTheAssembledVector() {
  var axes = identityAxes();
  Vector3d angularVelocity = new Vector3d(2.0, -3.0, 1.0);
  Vector3d inertiaBody = new Vector3d(40.0, 55.0, 30.0);
  double maxPossibleImpulseMagnitude = new Vector3d(
    inertiaBody.x*Math.abs(angularVelocity.dot(axes[1])),
    inertiaBody.y*Math.abs(angularVelocity.dot(axes[2])),
    inertiaBody.z*Math.abs(angularVelocity.dot(axes[0]))).length();
  for (double dt : new double[]{1e-6, 0.05, 10.0}) {
   for (double coeff : new double[]{RotorForces.MAX_ANGULAR_DAMPING_COEFFICIENT, 1e9}) {
    Vector3d damping = PhysicsBridge.rotorAngularDampingTorque(angularVelocity, axes[0], axes[1], axes[2], inertiaBody, coeff, dt);
    assertTrue(Double.isFinite(damping.x) && Double.isFinite(damping.y) && Double.isFinite(damping.z),
      "dt="+dt+" coeff="+coeff+" produced a non-finite damping vector");
    double impulseMagnitude = damping.length()*dt;
    assertTrue(impulseMagnitude <= maxPossibleImpulseMagnitude + 1e-6,
      "dt="+dt+" coeff="+coeff+" produced an impulse magnitude ("+impulseMagnitude+") exceeding the combined per-axis angular-momentum bound ("+maxPossibleImpulseMagnitude+")");
   }
  }
 }

 @Test void boundedRealRotorProbe_representativeHelicopterMassAndInertiaDecaysWithoutOvershoot() {
  // A bounded real-rotor probe (ticket requirement): a representative ~2200kg light helicopter
  // chassis (half-extents roughly 1.2m x 1.1m x 4.0m, box inertia approximation - the same formula
  // PhysicsBridge.Body already uses to compute its own inertiaBody), spun up on all three axes at
  // once by a believable post-gust angular rate, damped at a moderate configured coefficient.
  // Confirms the combined multi-axis integration (not just one axis in isolation) decays toward
  // rest, strictly monotonically in combined rotational energy, and never overshoots any single
  // axis past zero.
  double mass = 2200.0;
  double halfX=1.2, halfY=1.1, halfZ=4.0;
  Vector3d inertiaBody = new Vector3d(
    mass/3*(halfY*halfY+halfZ*halfZ),
    mass/3*(halfX*halfX+halfZ*halfZ),
    mass/3*(halfX*halfX+halfY*halfY));
  var axes = identityAxes(); // forward, right, up
  double coefficient = 4.0, dt = PhysicsBridge.DT;
  Vector3d angularVelocity = new Vector3d(1.2, -0.8, 0.5); // roll, pitch, yaw rad/s

  double previousEnergy = rotationalEnergy(angularVelocity, axes, inertiaBody);
  for (int tick=0; tick<2000; tick++) {
   Vector3d damping = PhysicsBridge.rotorAngularDampingTorque(angularVelocity, axes[0], axes[1], axes[2], inertiaBody, coefficient, dt);
   // Integrate exactly the way Body.stepRotor applies its own torque: impulse = torque*dt, then
   // (for this isolated probe, standing in for the engine's own angular-velocity update)
   // deltaOmega_axis = impulse_axis / inertia_axis along each body axis.
   double dOmegaForward = damping.dot(axes[0])*dt/inertiaBody.z;
   double dOmegaRight = damping.dot(axes[1])*dt/inertiaBody.x;
   double dOmegaUp = damping.dot(axes[2])*dt/inertiaBody.y;
   double omegaForwardBefore = angularVelocity.dot(axes[0]);
   double omegaRightBefore = angularVelocity.dot(axes[1]);
   double omegaUpBefore = angularVelocity.dot(axes[2]);
   angularVelocity.fma(dOmegaForward, axes[0]).fma(dOmegaRight, axes[1]).fma(dOmegaUp, axes[2]);
   // No-reversal, per axis, every tick.
   assertTrue((omegaForwardBefore+dOmegaForward)*omegaForwardBefore >= -1e-9, "tick "+tick+": roll axis reversed sign");
   assertTrue((omegaRightBefore+dOmegaRight)*omegaRightBefore >= -1e-9, "tick "+tick+": pitch axis reversed sign");
   assertTrue((omegaUpBefore+dOmegaUp)*omegaUpBefore >= -1e-9, "tick "+tick+": yaw axis reversed sign");

   double energy = rotationalEnergy(angularVelocity, axes, inertiaBody);
   assertTrue(energy <= previousEnergy + 1e-9, "tick "+tick+": combined rotational energy increased ("+previousEnergy+" -> "+energy+")");
   previousEnergy = energy;
  }
  assertTrue(angularVelocity.length() < 1e-2, "after 2000 ticks the probe rotorcraft must have decayed close to rest, got |omega|="+angularVelocity.length());
 }

 private static double rotationalEnergy(Vector3d angularVelocity, Vector3d[] axes, Vector3d inertiaBody) {
  double omegaForward = angularVelocity.dot(axes[0]);
  double omegaRight = angularVelocity.dot(axes[1]);
  double omegaUp = angularVelocity.dot(axes[2]);
  return 0.5*(inertiaBody.z*omegaForward*omegaForward + inertiaBody.x*omegaRight*omegaRight + inertiaBody.y*omegaUp*omegaUp);
 }
}
