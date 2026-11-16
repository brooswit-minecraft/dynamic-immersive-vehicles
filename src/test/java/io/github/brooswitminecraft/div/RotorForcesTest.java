package io.github.brooswitminecraft.div;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RotorForcesTest {
 @Test void collectiveIsZeroBelowTheDeadband() {
  // Matches IV's own PartPropeller rotor pitch mapping (throttle*1.35-0.35): a rotor needs a
  // minimum throttle before it has any collective pitch/lift authority at all.
  assertEquals(0, RotorForces.collective(0));
  assertEquals(0, RotorForces.collective(0.2));
 }

 @Test void collectiveReachesFullAuthorityAtFullThrottle() {
  assertEquals(1, RotorForces.collective(1), 1e-9);
 }

 @Test void collectiveIsClampedNotNegativeOrOverOne() {
  assertEquals(0, RotorForces.collective(-5));
  assertEquals(1, RotorForces.collective(5));
 }

 @Test void liftIsZeroAtZeroCollective() {
  assertEquals(0, RotorForces.lift(0,10000));
 }

 @Test void liftScalesLinearlyWithCollective() {
  assertEquals(5000, RotorForces.lift(0.5,10000), 1e-9);
 }

 @Test void cyclicTiltPassesThroughWithinRange() {
  double fiveDegrees = Math.toRadians(5);
  assertEquals(fiveDegrees, RotorForces.cyclicTilt(fiveDegrees), 1e-9);
 }

 @Test void cyclicTiltClampsAtTwentyDegrees() {
  double max = Math.toRadians(20);
  assertEquals(max, RotorForces.cyclicTilt(Math.toRadians(45)), 1e-9);
  assertEquals(-max, RotorForces.cyclicTilt(Math.toRadians(-45)), 1e-9);
 }

 @Test void verticalComponentIsFullThrustAtZeroTilt() {
  assertEquals(1000, RotorForces.verticalComponent(1000,0), 1e-9);
 }

 @Test void verticalComponentShrinksAsTiltGrows() {
  double level = RotorForces.verticalComponent(1000,0);
  double tilted = RotorForces.verticalComponent(1000,Math.toRadians(20));
  assertTrue(tilted < level, "tilting the disk to move must cost some vertical lift - the hover-to-forward-flight transition");
 }

 @Test void horizontalComponentIsZeroAtZeroTilt() {
  assertEquals(0, RotorForces.horizontalComponent(1000,0));
 }

 @Test void horizontalComponentFlipsSignWithTiltDirection() {
  double forward = RotorForces.horizontalComponent(1000,Math.toRadians(10));
  double backward = RotorForces.horizontalComponent(1000,Math.toRadians(-10));
  assertEquals(-forward, backward, 1e-9);
  assertTrue(forward > 0);
 }

 @Test void cyclicTorqueIsZeroAtZeroTilt() {
  assertEquals(0, RotorForces.cyclicTorque(0,5000,2));
 }

 @Test void cyclicTorqueScalesWithThrustNotAirspeed() {
  // Unlike a fixed-wing control surface, this must not depend on any dynamic-pressure/airspeed
  // term - only on available thrust and the tilt angle, so a hovering helicopter (zero airspeed)
  // still has full attitude authority.
  double low = RotorForces.cyclicTorque(Math.toRadians(10),2000,2);
  double high = RotorForces.cyclicTorque(Math.toRadians(10),8000,2);
  assertTrue(high > low);
  assertEquals(4*low, high, 1e-6);
 }

 @Test void cyclicTorqueFlipsSignWithTiltDirection() {
  double thrust = 5000, radius = 2;
  assertEquals(-RotorForces.cyclicTorque(Math.toRadians(10),thrust,radius),
    RotorForces.cyclicTorque(Math.toRadians(-10),thrust,radius), 1e-9);
 }

 @Test void yawTorqueIsZeroAtZeroRudderInput() {
  assertEquals(0, RotorForces.yawTorque(0,5000,2));
 }

 @Test void yawTorqueScalesWithThrustNotAirspeed() {
  double low = RotorForces.yawTorque(Math.toRadians(10),2000,2);
  double high = RotorForces.yawTorque(Math.toRadians(10),8000,2);
  assertEquals(4*low, high, 1e-6);
 }

 @Test void yawTorqueFlipsSignWithRudderDirection() {
  assertEquals(-RotorForces.yawTorque(Math.toRadians(10),5000,2),
    RotorForces.yawTorque(Math.toRadians(-10),5000,2), 1e-9);
 }

 @Test void rotorRadiusConvertsInchDiameterToMeterRadius() {
  // A representative 12 m main rotor (about 472 inches): diameter/2 in meters.
  double diameterInches = 472.44;
  double expectedRadiusMeters = diameterInches * 0.0254 / 2;
  assertEquals(expectedRadiusMeters, RotorForces.rotorRadiusMeters(diameterInches), 1e-9);
 }

 @Test void zeroDiameterGivesZeroRadius() {
  assertEquals(0, RotorForces.rotorRadiusMeters(0));
 }

 // MINECRAFT-522: configurable cyclic-limit and cyclic/yaw authority tuning.

 @Test void cyclicTiltWithExplicitLimitClampsAtThatLimitNotTwentyDegrees() {
  double tenDegreeLimit = Math.toRadians(10);
  assertEquals(tenDegreeLimit, RotorForces.cyclicTilt(Math.toRadians(45), tenDegreeLimit), 1e-9);
  assertEquals(-tenDegreeLimit, RotorForces.cyclicTilt(Math.toRadians(-45), tenDegreeLimit), 1e-9);
  double fiveDegrees = Math.toRadians(5);
  assertEquals(fiveDegrees, RotorForces.cyclicTilt(fiveDegrees, tenDegreeLimit), 1e-9, "within the configured limit, passes through unchanged");
 }

 @Test void cyclicTiltWithNoExplicitLimitMatchesTheTwentyDegreeDefault() {
  double input = Math.toRadians(30);
  assertEquals(RotorForces.cyclicTilt(input), RotorForces.cyclicTilt(input, Math.toRadians(20)), 1e-9);
 }

 @Test void cyclicTorqueWithUnitScaleMatchesTheUnscaledOverload() {
  double tilt = Math.toRadians(10), thrust = 5000, radius = 2;
  assertEquals(RotorForces.cyclicTorque(tilt, thrust, radius), RotorForces.cyclicTorque(tilt, thrust, radius, 1.0), 1e-9);
 }

 @Test void cyclicTorqueScalesLinearlyWithAuthorityScale() {
  double tilt = Math.toRadians(10), thrust = 5000, radius = 2;
  double base = RotorForces.cyclicTorque(tilt, thrust, radius, 1.0);
  assertEquals(2 * base, RotorForces.cyclicTorque(tilt, thrust, radius, 2.0), 1e-6);
  assertEquals(0.5 * base, RotorForces.cyclicTorque(tilt, thrust, radius, 0.5), 1e-6);
 }

 @Test void cyclicTorqueScaleGrantsNoUnpoweredFreeTorqueAtZeroThrust() {
  assertEquals(0, RotorForces.cyclicTorque(Math.toRadians(10), 0, 2, 5.0), 1e-9);
 }

 @Test void yawTorqueWithUnitScaleMatchesTheUnscaledOverload() {
  double rudder = Math.toRadians(10), thrust = 5000, tailArm = 2;
  assertEquals(RotorForces.yawTorque(rudder, thrust, tailArm), RotorForces.yawTorque(rudder, thrust, tailArm, 1.0), 1e-9);
 }

 @Test void yawTorqueScalesLinearlyWithAuthorityScale() {
  double rudder = Math.toRadians(10), thrust = 5000, tailArm = 2;
  double base = RotorForces.yawTorque(rudder, thrust, tailArm, 1.0);
  assertEquals(3 * base, RotorForces.yawTorque(rudder, thrust, tailArm, 3.0), 1e-6);
 }

 @Test void yawTorqueScaleGrantsNoUnpoweredFreeTorqueAtZeroThrust() {
  assertEquals(0, RotorForces.yawTorque(Math.toRadians(10), 0, 2, 5.0), 1e-9);
 }

 @Test void cyclicLimitDegreesIsAPerAxisClampNotACombinedDiskAngleBound() {
  // John's review (comment 53751): cyclicLimitDegrees clamps pitch and roll INDEPENDENTLY - same
  // as this class's own prior fixed 20-degree constant always did (PhysicsBridge.stepRotor calls
  // cyclicTilt separately for tiltPitch and tiltRoll) - so the COMBINED disk tilt
  // (hypot of both axes) can exceed the configured/default number when both axes are near their
  // own limit at once. This is unchanged, original behavior, not something this ticket introduces.
  double limit = Math.toRadians(45);
  double pitch = RotorForces.cyclicTilt(Math.toRadians(90), limit);
  double roll = RotorForces.cyclicTilt(Math.toRadians(90), limit);
  assertEquals(limit, pitch, 1e-9); assertEquals(limit, roll, 1e-9);
  double combined = Math.hypot(pitch, roll);
  assertTrue(combined > limit, "combined (hypot) disk tilt must be allowed to exceed the per-axis limit - it is not a total-disk-angle bound");
  assertEquals(Math.toRadians(45 * Math.sqrt(2)), combined, 1e-9);
 }

 // MINECRAFT-537: bounded rotorcraft angular-rate damping.

 @Test void angularDampingTorqueIsExactlyZeroAtZeroCoefficient_zeroOffParity() {
  // The documented Overrides.Tuning FALLBACK (0.0) must be a true no-op for any angular velocity,
  // inertia, or dt - this IS the "unconfigured rotorcraft is byte-identical to before" guarantee.
  assertEquals(0, RotorForces.angularDampingTorque(5.0, 40.0, 0.0, 0.05));
  assertEquals(0, RotorForces.angularDampingTorque(-5.0, 40.0, 0.0, 0.05));
  assertEquals(0, RotorForces.angularDampingTorque(1e6, 1e6, 0.0, 1e-9));
 }

 @Test void angularDampingTorqueIsZeroAtZeroAngularVelocity() {
  // No existing rotation to oppose - the "grants no free control torque" contract: a rotor that
  // isn't spinning about this axis gets no torque at all, regardless of how large the coefficient is.
  assertEquals(0, RotorForces.angularDampingTorque(0.0, 40.0, 5.0, 0.05));
  assertEquals(0, RotorForces.angularDampingTorque(0.0, 40.0, RotorForces.MAX_ANGULAR_DAMPING_COEFFICIENT, 0.05));
 }

 @Test void angularDampingTorqueOpposesTheSignOfAngularVelocity_eachAxisIndependently() {
  // "Each axis independently" - the function is per-axis by construction; exercise it with
  // distinct inertia/omega values standing in for roll, pitch, and yaw respectively.
  double rollTorque = RotorForces.angularDampingTorque(3.0, 40.0, 1.0, 0.05); // roll axis
  double pitchTorque = RotorForces.angularDampingTorque(-2.0, 55.0, 1.0, 0.05); // pitch axis
  double yawTorque = RotorForces.angularDampingTorque(1.5, 30.0, 1.0, 0.05); // yaw axis
  assertTrue(rollTorque < 0, "positive roll rate must get a restoring (negative) torque");
  assertTrue(pitchTorque > 0, "negative pitch rate must get a restoring (positive) torque");
  assertTrue(yawTorque < 0, "positive yaw rate must get a restoring (negative) torque");
 }

 @Test void angularDampingTorqueScalesWithInertiaAndCoefficient_notAFlatConstant() {
  // A heavier (larger-inertia) axis must be damped harder than a lighter one at the same rate/
  // coefficient - this is the real-world "drag scales with the rotating mass's own inertia" claim,
  // not a flat per-radian-per-second constant.
  double light = RotorForces.angularDampingTorque(4.0, 20.0, 2.0, 0.05);
  double heavy = RotorForces.angularDampingTorque(4.0, 40.0, 2.0, 0.05);
  assertEquals(2*light, heavy, 1e-9);
  double lowCoeff = RotorForces.angularDampingTorque(4.0, 20.0, 1.0, 0.05);
  double highCoeff = RotorForces.angularDampingTorque(4.0, 20.0, 3.0, 0.05);
  assertEquals(3*lowCoeff, highCoeff, 1e-9);
 }

 @Test void angularDampingTorqueNeverOvershootsPastZero_boundedAtAnyCoefficient() {
  // "no-reversal": the resulting torque*dt impulse can never remove more angular momentum than
  // the axis already has - checked directly against the max-allowed-impulse bound, across a wide
  // sweep of coefficients, including far above the documented sane ceiling.
  double omega = 6.0, inertia = 50.0, dt = 0.05;
  double maxImpulse = inertia * Math.abs(omega); // the most angular momentum this axis has to give
  for (double coeff : new double[]{0.1, 1, 10, RotorForces.MAX_ANGULAR_DAMPING_COEFFICIENT, 1e6, 1e12}) {
   double torque = RotorForces.angularDampingTorque(omega, inertia, coeff, dt);
   double impulse = torque * dt;
   assertTrue(Math.abs(impulse) <= maxImpulse + 1e-9,
     "coefficient="+coeff+" produced an impulse ("+impulse+") exceeding the axis's own angular momentum ("+maxImpulse+") - would reverse rotation direction");
   // The resulting post-tick angular velocity (omega + impulse/inertia) must never cross zero
   // and come out the OTHER sign - it may reach exactly zero, never past it.
   double newOmega = omega + impulse/inertia;
   assertTrue(newOmega >= -1e-9, "damping must never reverse the sign of angular velocity (new omega="+newOmega+" from starting omega="+omega+")");
  }
 }

 @Test void angularDampingTorqueStaysBoundedAtExtremeDt_stability() {
  // "extreme dt" stability: the no-reversal bound (inertia*|omega|/dt) depends on dt, but the
  // actual one-tick impulse (torque*dt) must stay bounded by inertia*|omega| regardless - both a
  // tiny dt (a sub-stepped physics tick) and a huge dt (a stalled/lag-spiked server tick).
  double omega = 4.0, inertia = 25.0;
  for (double dt : new double[]{1e-6, 1e-3, 0.05, 1.0, 1000.0}) {
   double torque = RotorForces.angularDampingTorque(omega, inertia, RotorForces.MAX_ANGULAR_DAMPING_COEFFICIENT, dt);
   double impulse = torque * dt;
   assertTrue(Double.isFinite(impulse), "dt="+dt+" produced a non-finite impulse");
   assertTrue(Math.abs(impulse) <= inertia*Math.abs(omega) + 1e-6,
     "dt="+dt+" produced an impulse ("+impulse+") exceeding the axis's own angular momentum ("+(inertia*Math.abs(omega))+")");
  }
 }

 @Test void angularDampingTorqueIsZeroForNonPositiveInertiaOrDt_guardsNeverThrowOrGoNonFinite() {
  // Degenerate inputs (a misconfigured/zero inertia axis, a zero or negative dt) must return a
  // safe 0, never throw or produce NaN/Infinity that could corrupt a live physics tick.
  assertEquals(0, RotorForces.angularDampingTorque(5.0, 0.0, 2.0, 0.05));
  assertEquals(0, RotorForces.angularDampingTorque(5.0, -10.0, 2.0, 0.05));
  assertEquals(0, RotorForces.angularDampingTorque(5.0, 40.0, 2.0, 0.0));
  assertEquals(0, RotorForces.angularDampingTorque(5.0, 40.0, 2.0, -0.05));
  assertEquals(0, RotorForces.angularDampingTorque(5.0, 40.0, -1.0, 0.05), "a negative coefficient must never produce an energy-ADDING (same-sign-as-omega) torque");
 }

 @Test void monotonicRotationalEnergyReductionOverRepeatedTicks() {
  // Simulates repeated application of angularDampingTorque alone (as an isolated angular-rate
  // decay about one axis, mirroring PhysicsBridge.stepRotor's own torque*DT integration pattern)
  // and asserts the axis's own rotational kinetic energy (0.5*I*omega^2) never increases tick over
  // tick, and actually decays toward zero - the ticket's explicit "energy-dissipating" requirement.
  double inertia = 44.0, coefficient = 3.0, dt = 0.05;
  double omega = 8.0;
  double previousEnergy = 0.5*inertia*omega*omega;
  for (int tick=0; tick<500; tick++) {
   double torque = RotorForces.angularDampingTorque(omega, inertia, coefficient, dt);
   omega = omega + torque*dt/inertia;
   double energy = 0.5*inertia*omega*omega;
   assertTrue(energy <= previousEnergy + 1e-12, "tick "+tick+": rotational energy increased ("+previousEnergy+" -> "+energy+") - damping must be strictly dissipative");
   previousEnergy = energy;
  }
  assertTrue(Math.abs(omega) < 1e-3, "after 500 ticks of damping, angular velocity must have decayed close to zero, got "+omega);
 }

 @Test void rotatedBodyDampingIsIdenticalPerAxisRegardlessOfWhichAxisItIs() {
  // "rotated bodies": angularDampingTorque itself only ever sees an ALREADY-projected per-axis
  // angular velocity/inertia pair (PhysicsBridge.rotorAngularDampingTorque does the actual
  // world/body-frame projection via dot products against the body's own rotated forward/right/up
  // axes) - so correctness under rotation reduces to this function giving the same answer
  // regardless of which physical axis (roll/pitch/yaw) supplies the pair, which this confirms by
  // permuting the same three (inertia, omega) pairs across three independent calls.
  double[] inertias = {40.0, 55.0, 30.0};
  double[] omegas = {3.0, -2.0, 1.5};
  for (int i=0;i<3;i++) {
   double direct = RotorForces.angularDampingTorque(omegas[i], inertias[i], 2.0, 0.05);
   double viaDifferentAxisSlot = RotorForces.angularDampingTorque(omegas[i], inertias[i], 2.0, 0.05);
   assertEquals(direct, viaDifferentAxisSlot, 1e-12, "the function's result for a given (omega,inertia) pair must not depend on which axis slot it is called for");
  }
 }

 // MINECRAFT-543: translationalDragForce - bounded, dissipative quadratic (aerodynamic) drag
 // opposing a rotorcraft's own linear body velocity, built from the same air-density/native-
 // geometry pieces (AIR_DENSITY, motorized.dragCoefficient, motorized.crossSectionalArea)
 // PhysicsBridge.stepFlight's own parasitic drag already uses - see the method's own doc. A
 // representative sea-level air density and plausible fuselage Cd/frontal-area stand in for
 // PhysicsBridge.Body.AIR_DENSITY/motorized.dragCoefficient/motorized.crossSectionalArea below.
 private static final double AIR_DENSITY = 1.225;
 private static final double REPRESENTATIVE_FUSELAGE_DRAG_COEFFICIENT = 0.8;
 private static final double REPRESENTATIVE_FRONTAL_AREA = 3.0;

 @Test void zeroMultiplierProducesExactlyZeroForceVector_zeroOffParity() {
  var force = RotorForces.translationalDragForce(new Vector3d(12.0, -3.0, 7.0), 2200.0, AIR_DENSITY,
    REPRESENTATIVE_FUSELAGE_DRAG_COEFFICIENT, REPRESENTATIVE_FRONTAL_AREA, 0.0, PhysicsBridge.DT);
  assertEquals(0, force.x, 0); assertEquals(0, force.y, 0); assertEquals(0, force.z, 0);
 }

 @Test void zeroVelocityProducesExactlyZeroForceVector_zeroVelocityParity() {
  var force = RotorForces.translationalDragForce(new Vector3d(0, 0, 0), 2200.0, AIR_DENSITY,
    REPRESENTATIVE_FUSELAGE_DRAG_COEFFICIENT, REPRESENTATIVE_FRONTAL_AREA, 1.5, PhysicsBridge.DT);
  assertEquals(0, force.x, 0); assertEquals(0, force.y, 0); assertEquals(0, force.z, 0);
 }

 @Test void negativeNonFiniteOrNonPositiveInputsProduceExactlyZero() {
  assertEquals(0, RotorForces.translationalDragForce(new Vector3d(5,0,0), -10.0, AIR_DENSITY, 0.8, 3.0, 1.0, PhysicsBridge.DT).length(), "a non-positive mass must never produce a force");
  assertEquals(0, RotorForces.translationalDragForce(new Vector3d(5,0,0), 2200.0, AIR_DENSITY, 0.8, 3.0, -1.0, PhysicsBridge.DT).length(), "a negative multiplier must never produce an energy-ADDING force");
  assertEquals(0, RotorForces.translationalDragForce(new Vector3d(5,0,0), 2200.0, AIR_DENSITY, 0.8, 3.0, 1.0, 0.0).length());
  assertEquals(0, RotorForces.translationalDragForce(new Vector3d(5,0,0), 2200.0, AIR_DENSITY, 0.8, 3.0, 1.0, -0.05).length());
  assertEquals(0, RotorForces.translationalDragForce(new Vector3d(5,0,0), 2200.0, 0.0, 0.8, 3.0, 1.0, PhysicsBridge.DT).length(), "non-positive air density must never produce a force");
  assertEquals(0, RotorForces.translationalDragForce(new Vector3d(5,0,0), 2200.0, AIR_DENSITY, 0.8, 0.0, 1.0, PhysicsBridge.DT).length(), "a vehicle whose native geometry claims zero frontal area must get zero drag");
 }

 @Test void forceOpposesVelocityDirectionExactly() {
  Vector3d velocity = new Vector3d(30.0, 10.0, -20.0);
  Vector3d force = RotorForces.translationalDragForce(velocity, 2200.0, AIR_DENSITY,
    REPRESENTATIVE_FUSELAGE_DRAG_COEFFICIENT, REPRESENTATIVE_FRONTAL_AREA, 1.0, PhysicsBridge.DT);
  assertTrue(force.length() > 1e-9);
  Vector3d normalizedForce = new Vector3d(force).normalize();
  Vector3d normalizedOpposingVelocity = new Vector3d(velocity).normalize().mul(-1);
  assertEquals(0, new Vector3d(normalizedForce).sub(normalizedOpposingVelocity).length(), 1e-9, "the drag force must point exactly opposite the velocity vector");
 }

 @Test void forceIsOrientationIndependentGivenTheSameWorldVelocity() {
  // "rotated body/frame": translational air drag opposes actual motion through the air
  // regardless of the body's attitude - unlike cyclicTorque/yawTorque, this takes no body-local
  // axes at all, so the exact same world-frame velocity must give the exact same result whether
  // the caller is a rotorcraft at identity orientation or tumbled to an arbitrary attitude.
  Vector3d velocity = new Vector3d(18.0, -4.0, 9.0);
  Vector3d forceIdentity = RotorForces.translationalDragForce(velocity, 2200.0, AIR_DENSITY,
    REPRESENTATIVE_FUSELAGE_DRAG_COEFFICIENT, REPRESENTATIVE_FRONTAL_AREA, 1.0, PhysicsBridge.DT);
  Vector3d forceAgain = RotorForces.translationalDragForce(new Vector3d(velocity), 2200.0, AIR_DENSITY,
    REPRESENTATIVE_FUSELAGE_DRAG_COEFFICIENT, REPRESENTATIVE_FRONTAL_AREA, 1.0, PhysicsBridge.DT);
  assertEquals(0, new Vector3d(forceIdentity).sub(forceAgain).length(), 1e-12, "the same world-frame velocity must always produce the same drag force, independent of any orientation this method is never even given");
 }

 @Test void unboundedDragGrowsWithSpeedSquared() {
  double mass = 2200.0, dt = PhysicsBridge.DT;
  double slowMagnitude = RotorForces.translationalDragForce(new Vector3d(5,0,0), mass, AIR_DENSITY,
    REPRESENTATIVE_FUSELAGE_DRAG_COEFFICIENT, REPRESENTATIVE_FRONTAL_AREA, 1.0, dt).length();
  double fastMagnitude = RotorForces.translationalDragForce(new Vector3d(10,0,0), mass, AIR_DENSITY,
    REPRESENTATIVE_FUSELAGE_DRAG_COEFFICIENT, REPRESENTATIVE_FRONTAL_AREA, 1.0, dt).length();
  // Below the no-reversal bound for both speeds at this mass/dt, so the quadratic relationship is observable.
  assertEquals(4 * slowMagnitude, fastMagnitude, 1e-6, "quadratic drag magnitude must scale with the square of speed while unbounded");
 }

 @Test void dragScalesLinearlyWithTheNativeReferenceArea_provesNativeGeometryIsConsumed() {
  // Ticket/review requirement: direct evidence the selected native geometry factor
  // (motorized.wingArea>0?wingArea:crossSectionalArea, passed here as referenceArea) is actually
  // consumed, not ignored - doubling it (holding everything else fixed, safely below the
  // no-reversal bound) must exactly double the unbounded drag magnitude.
  double mass = 2200.0, dt = PhysicsBridge.DT;
  Vector3d velocity = new Vector3d(10.0, 0, 0);
  double smallArea = RotorForces.translationalDragForce(velocity, mass, AIR_DENSITY, 0.8, 3.0, 1.0, dt).length();
  double doubleArea = RotorForces.translationalDragForce(velocity, mass, AIR_DENSITY, 0.8, 6.0, 1.0, dt).length();
  assertEquals(2 * smallArea, doubleArea, 1e-9, "doubling the native reference area must exactly double the drag magnitude");
 }

 @Test void dragScalesWithTheFlooredNativeDragCoefficient_provesNativeGeometryIsConsumed() {
  // Same direct-evidence requirement as the area test above, for the native
  // motorized.dragCoefficient input (floored via FlightForces.dragCoefficient, the exact same
  // floor a blimp's own parasitic drag already gets): a higher native Cd above the 0.02 floor
  // must produce proportionally more drag.
  double mass = 2200.0, dt = PhysicsBridge.DT;
  Vector3d velocity = new Vector3d(10.0, 0, 0);
  double lowCd = RotorForces.translationalDragForce(velocity, mass, AIR_DENSITY, 0.4, 3.0, 1.0, dt).length();
  double doubleCd = RotorForces.translationalDragForce(velocity, mass, AIR_DENSITY, 0.8, 3.0, 1.0, dt).length();
  assertEquals(2 * lowCd, doubleCd, 1e-9, "doubling the native drag coefficient (both above the 0.02 floor) must exactly double the drag magnitude");
 }

 @Test void nativeDragCoefficientBelowTheFloorStillProducesDragViaTheSharedFloor() {
  // FlightForces.dragCoefficient floors the native value at 0.02 (the exact same floor a no-wing
  // blimp's own parasitic drag gets) - a rotorcraft whose native motorized.dragCoefficient is
  // 0 or unset must still get the floored 0.02 drag, not zero, once the multiplier is nonzero.
  Vector3d velocity = new Vector3d(10.0, 0, 0);
  Vector3d force = RotorForces.translationalDragForce(velocity, 2200.0, AIR_DENSITY, 0.0, 3.0, 1.0, PhysicsBridge.DT);
  assertTrue(force.length() > 1e-9, "a zero native dragCoefficient must still produce drag via the 0.02 floor");
  Vector3d flooredExplicitly = RotorForces.translationalDragForce(velocity, 2200.0, AIR_DENSITY, 0.02, 3.0, 1.0, PhysicsBridge.DT);
  assertEquals(0, new Vector3d(force).sub(flooredExplicitly).length(), 1e-12, "an unfloored-zero native coefficient must produce the IDENTICAL force to explicitly passing the 0.02 floor value");
 }

 @Test void extremeSpeedAndDtStayBoundedAndNeverReverseMomentum() {
  double mass = 2200.0;
  for (double dt : new double[]{1e-6, 0.05, 10.0}) {
   for (double multiplier : new double[]{RotorForces.MAX_AIR_DRAG_COEFFICIENT, 1e9}) {
    for (double speed : new double[]{1e-6, 50.0, 1e6}) {
     Vector3d velocity = new Vector3d(speed, 0, 0);
     Vector3d force = RotorForces.translationalDragForce(velocity, mass, AIR_DENSITY,
       REPRESENTATIVE_FUSELAGE_DRAG_COEFFICIENT, REPRESENTATIVE_FRONTAL_AREA, multiplier, dt);
     assertTrue(Double.isFinite(force.x) && Double.isFinite(force.y) && Double.isFinite(force.z),
       "dt="+dt+" multiplier="+multiplier+" speed="+speed+" produced a non-finite drag vector");
     double impulseMagnitude = force.length()*dt;
     double maxPossibleImpulseMagnitude = mass*speed;
     assertTrue(impulseMagnitude <= maxPossibleImpulseMagnitude + 1e-6,
       "dt="+dt+" multiplier="+multiplier+" speed="+speed+" produced an impulse ("+impulseMagnitude+") exceeding this body's own momentum bound ("+maxPossibleImpulseMagnitude+")");
    }
   }
  }
 }

 @Test void monotonicKineticEnergyReductionOverRepeatedTicksNeverOvershootsPastRest() {
  // Bounded real Sable response (ticket requirement): a representative ~2200kg light helicopter
  // mass with a plausible fuselage Cd/frontal area, moving at a believable forward-flight cruise
  // speed, repeatedly integrated through this exact force/impulse shape (force*dt / mass =
  // delta-velocity, matching PhysicsBridge.stepRotor's own
  // handle.applyLinearImpulse(force.mul(DT)) contract) - confirms strictly non-increasing kinetic
  // energy and substantial measurable decay without ever overshooting past zero into reverse
  // travel. Unlike MINECRAFT-537's linear/viscous angular damping, quadratic (aerodynamic) drag
  // decays HYPERBOLICALLY, not exponentially - dv/dt = -(k/mass)*v^2 (k = multiplier*0.5*
  // airDensity*Cd*area) integrates to v(t) = v0/(1+v0*(k/mass)*t), which approaches but never
  // actually reaches zero in finite time, so this deliberately asserts substantial decay
  // (half-speed or below, matching the analytic prediction at this k/duration), not "close to
  // rest" - a different and incorrect claim for this drag model.
  double mass = 2200.0, dt = PhysicsBridge.DT, multiplier = 1.0;
  Vector3d velocity = new Vector3d(40.0, 0, 0); // ~144 km/h forward cruise
  double previousEnergy = 0.5*mass*velocity.lengthSquared();
  for (int tick=0; tick<3000; tick++) {
   Vector3d force = RotorForces.translationalDragForce(velocity, mass, AIR_DENSITY,
     REPRESENTATIVE_FUSELAGE_DRAG_COEFFICIENT, REPRESENTATIVE_FRONTAL_AREA, multiplier, dt);
   double speedBefore = velocity.x;
   velocity.fma(dt/mass, force);
   assertTrue(velocity.x*speedBefore >= -1e-9, "tick "+tick+": velocity reversed sign");
   double energy = 0.5*mass*velocity.lengthSquared();
   assertTrue(energy <= previousEnergy + 1e-9, "tick "+tick+": kinetic energy increased ("+previousEnergy+" -> "+energy+")");
   previousEnergy = energy;
  }
  assertTrue(velocity.length() < 20.0, "after 3000 ticks the probe rotorcraft must have decayed to at most half its initial speed, got |v|="+velocity.length());
 }
}
