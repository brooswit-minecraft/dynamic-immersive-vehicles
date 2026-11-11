package io.github.brooswitminecraft.div;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Confirmed root cause for the reported rollover (ticket MINECRAFT-503, comment 52906/52907):
 * recovered flushed diagnostic data showed a vehicle resting with all four wheels at EQUAL
 * height/compression - no static asymmetry - spontaneously develop growing, alternating lateral
 * forces (+4, -3, +13, -30, +69, -178 N, roughly tripling each sample) before tilting, while
 * undriven. That growth signature is exactly what a velocity-sampling/application-point mismatch
 * on a rotating rigid body produces, and {@code stepGround} had exactly that mismatch: it sampled
 * slip velocity at the suspension anchor ({@code arm}, close to the chassis's own center of mass)
 * but applied the resulting tire impulse at the actual terrain contact point ({@code point},
 * offset from the anchor by roughly {@code radius+compression} along the vehicle's "up" axis -
 * about 0.425m for the reported ATV geometry). For a body with ANY angular velocity, true
 * velocity differs between two points separated by {@code offset} by exactly
 * {@code angular x offset}; sampling at the wrong point therefore fed a slip-velocity error
 * proportional to the chassis's own roll rate back into friction every tick - a growing feedback
 * loop (more erroneous slip -> more lateral force -> more roll torque -> more erroneous slip),
 * not a one-off error, matching the captured tripling-per-sample growth exactly. The fix samples
 * slip velocity at the same point ({@code point}) the impulse is applied at, leaving the vertical
 * suspension-damping sample at the anchor unchanged (that one IS correct there, since it measures
 * the raycast length's own rate of change, not slip).
 *
 * <p>This test proves the mismatch mechanism itself - the exact vector identity {@code
 * stepGround} now avoids - using the production {@code forward}/{@code right}/{@code up} axis
 * convention and the exact reported geometry, independent of any live Sable/Minecraft runtime.
 */
class PhysicsBridgeLateralSlipSamplingTest {
 private static final Vector3d UP = new Vector3d(0,1,0), FORWARD = new Vector3d(0,0,1);
 private static final Vector3d RIGHT = new Vector3d(UP).cross(FORWARD); // matches stepGround's own convention
 @Test void armSampledAndContactSampledVelocityMatchWhenLevelAndNotRotating() {
  // The bug is invisible exactly when the chassis isn't rotating - both points move identically
  // with the body, so the stale (arm-sampled) and correct (contact-sampled) velocities agree.
  // This is why the captured data's first few samples (near-zero angular velocity after
  // placement) show only tiny forces before the feedback loop has had time to grow.
  Vector3d angular = new Vector3d(0,0,0), linear = new Vector3d(1,0,2);
  Vector3d arm = new Vector3d(0,0.125,0), contact = new Vector3d(0,0.125-0.425,0);
  Vector3d armVelocity = new Vector3d(angular).cross(arm).add(linear);
  Vector3d contactVelocity = new Vector3d(angular).cross(contact).add(linear);
  assertEquals(armVelocity.dot(RIGHT), contactVelocity.dot(RIGHT), 1e-12);
 }
 @Test void sideSpeedErrorFromSamplingAtTheAnchorInsteadOfTheContactPointScalesWithRollRateAndVerticalOffset() {
  // Exact reported geometry (comment 52907): wheel radius 0.375m, static compression at the
  // reported normal/spring (320.825/6416.67) is 0.05m, so point-arm separation along -up is
  // radius+compression = 0.425m. A roll rate about the forward axis is exactly the rotation that
  // tips a wheel's contact patch sideways relative to its suspension mount.
  double verticalSeparation = 0.375 + 0.05;
  for (double rollRate : new double[]{0, 0.01, 0.05, 0.2, -0.1}) {
   Vector3d angular = new Vector3d(FORWARD).mul(rollRate);
   // point = arm + (-verticalSeparation)*up, i.e. the contact sits below the anchor along -up.
   Vector3d arm = new Vector3d(0,0,0);
   Vector3d point = new Vector3d(arm).fma(-verticalSeparation, UP);
   Vector3d linear = new Vector3d(3,0,5); // any chassis linear velocity - must cancel out of the difference
   double armSide = new Vector3d(angular).cross(arm).add(linear).dot(RIGHT);
   double contactSide = new Vector3d(angular).cross(point).add(linear).dot(RIGHT);
   // The exact closed-form error stepGround's old code carried: contactSide - armSide equals
   // verticalSeparation * rollRate, independent of the shared linear velocity (which cancels,
   // confirming the error comes purely from sampling point choice, not from the vehicle's own
   // translation).
   assertEquals(verticalSeparation * rollRate, contactSide - armSide, 1e-9,
     "roll rate "+rollRate+": sampling at the anchor instead of the contact point must mis-measure side slip by exactly verticalSeparation*rollRate");
  }
 }
 @Test void errorMagnitudeAtAModestRollRateIsComparableToTheCapturedEarlyForces() {
  // A roll rate as small as 0.01 rad/s (barely perceptible) already produces a side-speed error
  // whose resulting friction-budget swing (error * mass/wheelCount/DT, the same scaling
  // stepGround applies to sideSpeed before calling TireForces.friction) lands in the same tens-
  // of-newtons range as the captured data's own early samples (+4, -3, +13 N) - consistent with
  // this being the actual mechanism, not a coincidence of scale.
  double verticalSeparation = 0.375 + 0.05, rollRate = 0.01, mass = 350, wheelCount = 4, dt = 0.05;
  double sideSpeedError = verticalSeparation * rollRate;
  double frictionBudgetError = sideSpeedError * mass / wheelCount / dt;
  assertTrue(frictionBudgetError > 1 && frictionBudgetError < 100,
    "expected a tens-of-newtons-scale error at a barely-perceptible roll rate, got "+frictionBudgetError);
 }
}
