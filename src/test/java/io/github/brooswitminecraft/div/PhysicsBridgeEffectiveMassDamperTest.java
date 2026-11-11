package io.github.brooswitminecraft.div;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@code PhysicsBridge.effectiveMassDamper} (ticket comments 53214/53219) - the opt-
 * in per-contact damping-basis candidate now wired into {@code Body.stepGround} behind
 * {@code -Ddiv.experimentalEffectiveMassDamping}. Focuses on exactly what comment 53219 asked be
 * verified: the guard/fallback behavior for degenerate inputs, and that a real (non-degenerate)
 * contact produces the same formula shape {@code PhysicsBridge.suspensionDamper} already uses,
 * just with a different (per-contact) inertial basis.
 *
 * <p>Does not re-litigate whether the candidate is a good idea - that measurement already exists
 * (EffectiveMassDampingCandidateTest/ExtendedValidationTest/SeedSweepTest/BoundedSweepTest in the
 * experimental package) and found no robust benefit. This class only verifies the production
 * wiring's own correctness/safety, independent of whether the physics idea pans out.
 */
class PhysicsBridgeEffectiveMassDamperTest {
 private static final double MASS=350.0;
 private static final Vector3d INERTIA_BODY=new Vector3d(
   MASS/3*(0.4410937510430813*0.4410937510430813+1.050000011920929*1.050000011920929),
   MASS/3*(0.42500001192092896*0.42500001192092896+1.050000011920929*1.050000011920929),
   MASS/3*(0.42500001192092896*0.42500001192092896+0.4410937510430813*0.4410937510430813));
 private static final Quaterniond IDENTITY=new Quaterniond();
 private static final Vector3d UP=new Vector3d(0,1,0);
 private static final double SPRING=11430.0, FALLBACK_DAMPER=987.0;

 @Test void aContactAtTheComFallsBackToFullBodyMassExactlyMatchingWhatTheSharedFormulaWouldGiveAtWheelCountOne() {
  var result=PhysicsBridge.effectiveMassDamper(SPRING, MASS, new Vector3d(0,0,0), UP, IDENTITY, INERTIA_BODY, FALLBACK_DAMPER);
  assertEquals(MASS, result.effectiveMass(), 1e-6, "a contact exactly at the COM has no lever arm - effective mass must equal the full body mass");
  assertEquals(2*Math.sqrt(SPRING*MASS)*.7, result.damper(), 1e-6, "damper must use the identical critical-damping-ratio formula shape as suspensionDamper, just with this effective mass as the inertial input");
 }

 @Test void aRealOffCenterWheelAnchorProducesADamperBetweenTheComAndNaiveWheelCountBasis() {
  Vector3d frontRightAnchor=new Vector3d(0.375,0.125+0.15,1.25).sub(new Vector3d(0,0.553593748062849,0.625));
  var result=PhysicsBridge.effectiveMassDamper(SPRING, MASS, frontRightAnchor, UP, IDENTITY, INERTIA_BODY, FALLBACK_DAMPER);
  assertTrue(result.effectiveMass()>0 && result.effectiveMass()<MASS, "an off-center contact must reduce effective mass below the full body mass but stay positive - got "+result.effectiveMass());
  assertTrue(Double.isFinite(result.damper()) && result.damper()>0, "damper must stay a finite positive number for a real off-center contact");
 }

 @Test void nonFiniteSpringFallsBackSafely() {
  var result=PhysicsBridge.effectiveMassDamper(Double.NaN, MASS, new Vector3d(0,0,0), UP, IDENTITY, INERTIA_BODY, FALLBACK_DAMPER);
  assertEquals(FALLBACK_DAMPER, result.damper());
  assertTrue(Double.isNaN(result.effectiveMass()), "bypassed-before-attempting the solver call entirely - effectiveMass must log as NaN, not a stale/partial number");
 }

 @Test void zeroOrNegativeSpringFallsBackSafely() {
  assertEquals(FALLBACK_DAMPER, PhysicsBridge.effectiveMassDamper(0, MASS, new Vector3d(0,0,0), UP, IDENTITY, INERTIA_BODY, FALLBACK_DAMPER).damper());
  assertEquals(FALLBACK_DAMPER, PhysicsBridge.effectiveMassDamper(-5, MASS, new Vector3d(0,0,0), UP, IDENTITY, INERTIA_BODY, FALLBACK_DAMPER).damper());
 }

 @Test void nonFiniteOrNonPositiveMassFallsBackSafely() {
  assertEquals(FALLBACK_DAMPER, PhysicsBridge.effectiveMassDamper(SPRING, Double.NaN, new Vector3d(0,0,0), UP, IDENTITY, INERTIA_BODY, FALLBACK_DAMPER).damper());
  assertEquals(FALLBACK_DAMPER, PhysicsBridge.effectiveMassDamper(SPRING, 0, new Vector3d(0,0,0), UP, IDENTITY, INERTIA_BODY, FALLBACK_DAMPER).damper());
  assertEquals(FALLBACK_DAMPER, PhysicsBridge.effectiveMassDamper(SPRING, -10, new Vector3d(0,0,0), UP, IDENTITY, INERTIA_BODY, FALLBACK_DAMPER).damper());
 }

 @Test void nonFiniteOrNonPositiveInertiaComponentFallsBackSafely() {
  assertEquals(FALLBACK_DAMPER, PhysicsBridge.effectiveMassDamper(SPRING, MASS, new Vector3d(0,0,0), UP, IDENTITY, new Vector3d(Double.NaN,1,1), FALLBACK_DAMPER).damper());
  assertEquals(FALLBACK_DAMPER, PhysicsBridge.effectiveMassDamper(SPRING, MASS, new Vector3d(0,0,0), UP, IDENTITY, new Vector3d(0,1,1), FALLBACK_DAMPER).damper());
  assertEquals(FALLBACK_DAMPER, PhysicsBridge.effectiveMassDamper(SPRING, MASS, new Vector3d(0,0,0), UP, IDENTITY, new Vector3d(1,-1,1), FALLBACK_DAMPER).damper());
 }

 @Test void nonFinitePointOrDirectionFallsBackSafely() {
  assertEquals(FALLBACK_DAMPER, PhysicsBridge.effectiveMassDamper(SPRING, MASS, new Vector3d(Double.NaN,0,0), UP, IDENTITY, INERTIA_BODY, FALLBACK_DAMPER).damper());
  assertEquals(FALLBACK_DAMPER, PhysicsBridge.effectiveMassDamper(SPRING, MASS, new Vector3d(0,0,0), new Vector3d(Double.NaN,1,0), IDENTITY, INERTIA_BODY, FALLBACK_DAMPER).damper());
 }

 /** Comment 53219's explicit "verify opt-in off matches baseline" - this is the production call
  * site's own contract: when the flag is off, Body.stepGround never calls this method at all and
  * uses {@code new EffectiveMassDamperResult(damper, Double.NaN)} directly, which is trivially
  * byte-identical to the shared damper. This test instead documents what happens if the method
  * WERE called with the exact real production inputs at true equilibrium (COM-relative wheel
  * anchors), as a sanity check that the candidate's own math is well-behaved at the real
  * geometry this ticket's vehicle actually uses - not a substitute for the off-path guarantee,
  * which is structural (no call at all), not a computed one. */
 @Test void realAtvWheelAnchorsAllProduceFiniteSensibleDampersAtEquilibrium() {
  Vector3d center=new Vector3d(0,0.553593748062849,0.625);
  Vector3d[] offsets={
    new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
    new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
  for (Vector3d offset : offsets) {
   Vector3d anchor=new Vector3d(offset.x, offset.y+0.15, offset.z).sub(center);
   var result=PhysicsBridge.effectiveMassDamper(SPRING, MASS, anchor, UP, IDENTITY, INERTIA_BODY, FALLBACK_DAMPER);
   assertTrue(Double.isFinite(result.damper()) && result.damper()>0, "wheel anchor "+offset+" must produce a finite positive damper, not a fallback - got "+result.damper());
   assertTrue(result.damper() != FALLBACK_DAMPER, "wheel anchor "+offset+" is a real, non-degenerate contact and must not have fallen back");
  }
 }
}
