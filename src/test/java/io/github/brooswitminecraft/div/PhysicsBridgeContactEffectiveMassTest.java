package io.github.brooswitminecraft.div;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for the lateral-slip-budget denominator (ticket MINECRAFT-503, comments 53010/53011):
 * real captured production data (two separate placements, flat ground, fully undriven) showed
 * measured slip (sideSpeed) growing every single tick from spawn - never decaying - with the
 * lateral tire force saturated at exactly friction*normal (ratio 1.000) by tick 5 and alternating
 * sign tick-to-tick, which is what was actually pumping rotational energy into the chassis. The
 * mechanism, isolated by a full rigid-body impulse/energy accounting against that real data (not
 * a guess), is {@code stepGround}'s one-step lateral slip-cancellation target using
 * {@code mass/wheelCount} as the "mass this contact must accelerate" - correct only when the
 * chassis isn't rotating, but once it is, the real contact-point effective mass (accounting for
 * the body's actual rotational coupling at that specific point) can be substantially SMALLER,
 * so mass/wheelCount requests far more force than the rigid body actually needs to arrest slip
 * there, saturating friction and overshooting every tick.
 *
 * <p>This test replays the exact geometry/orientation/inertia from the real capture (entity
 * 4b381e31, tick 1, wheel localOffset=[0.35,0.125,-0.0625] - the wheel whose sideSpeed/target the
 * ticket comments walked through by hand) through {@link PhysicsBridge#contactLateralEffectiveMass}
 * and confirms: (1) the real effective mass at that point/orientation is substantially less than
 * the naive mass/wheelCount figure the pre-fix code used (so the fix changes behavior in the
 * intended direction on real data, not just in theory), and (2) the resulting lateral slip-budget
 * target is correspondingly and proportionally smaller - exactly the "smaller, not arbitrarily
 * damped" correction the evidence calls for, independently cross-checked against a from-scratch
 * Python rigid-body computation over the same real numbers (not re-derived from this same code).
 */
class PhysicsBridgeContactEffectiveMassTest {
 // Real chassisReceipt geometry for mtsofficialpack:quad (ticket comment 52984/52985).
 private static final double MASS = 350.0;
 private static final Vector3d HALF_EXTENTS = new Vector3d(0.42500001192092896, 0.4410937510430813, 1.050000011920929);
 private static Vector3d inertiaBody() {
  double a = HALF_EXTENTS.x, b = HALF_EXTENTS.y, c = HALF_EXTENTS.z;
  return new Vector3d(MASS/3*(b*b+c*c), MASS/3*(a*a+c*c), MASS/3*(a*a+b*b));
 }
 // Real tick1 orientation (x,y,z,w) and wheel1 contact point/right axis, entity 4b381e31.
 private static final Quaterniond ORIENTATION = new Quaterniond(4.464627636480145E-5,0.7544234991073608,-4.003042995464057E-4,0.656387984752655);
 private static final Vector3d POINT = new Vector3d(-0.7298336949302637,-0.8986282348632812,-0.2510581121814539);
 private static final double SIDE_SPEED = -0.008068773057869273;
 private static final double DT = 0.05;

 private static Vector3d right() {
  Vector3d up = ORIENTATION.transform(new Vector3d(0,1,0));
  Vector3d forward = ORIENTATION.transform(new Vector3d(0,0,1));
  return new Vector3d(up).cross(forward);
 }

 @Test void realContactEffectiveMassIsSubstantiallyLessThanNaiveMassOverWheelCount() {
  Quaterniond inverse = new Quaterniond(ORIENTATION).conjugate();
  double effectiveMass = PhysicsBridge.contactLateralEffectiveMass(MASS, POINT, right(), inverse, inertiaBody());
  double naiveMassOverWheelCount = MASS/4;
  // Independently computed (not from this code) via a from-scratch rigid-body calculation over
  // the same real numbers - see ticket comment history for the Python cross-check.
  assertEquals(40.8575197, effectiveMass, 1e-4,
    "real effective mass at this point/orientation should match the independent cross-check");
  assertTrue(effectiveMass < naiveMassOverWheelCount * 0.6,
    "at this real rotating-chassis moment, the correct contact effective mass ("+effectiveMass
    +") should be well below the naive mass/wheelCount approximation ("+naiveMassOverWheelCount
    +") that the pre-fix code used - that gap is exactly what was causing the one-step target to overshoot");
 }

 @Test void correctedLateralSlipBudgetTargetIsProportionallySmallerOnRealData() {
  Quaterniond inverse = new Quaterniond(ORIENTATION).conjugate();
  double effectiveMass = PhysicsBridge.contactLateralEffectiveMass(MASS, POINT, right(), inverse, inertiaBody());
  double oldTarget = -SIDE_SPEED*(MASS/4)/DT;
  double newTarget = -SIDE_SPEED*effectiveMass/DT;
  assertEquals(14.1204, oldTarget, 1e-3, "pre-fix target, from the real tick1 wheel1 data walked through in comment 52986");
  assertEquals(6.5934, newTarget, 1e-3, "corrected target on the same real data");
  assertTrue(Math.abs(newTarget) < Math.abs(oldTarget),
    "the corrected target must request less force than the naive one on this real, rotating-chassis sample");
 }
}
