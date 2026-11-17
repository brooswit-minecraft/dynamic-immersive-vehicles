package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TireForcesTest {
 @Test void airborneHasNoTraction() { assertEquals(new TireForces.Force(0,0), TireForces.friction(100,100,0,1)); }
 @Test void combinedForcesStayInsideCircle() { var f=TireForces.friction(300,400,100,1); assertEquals(100,Math.hypot(f.longitudinal(),f.lateral()),1e-9); }
 @Test void reboundNeverPullsGround() { assertEquals(0,TireForces.suspension(0.1,10,1000,1000)); }
 @Test void springSupportsLoad() { assertEquals(2000,TireForces.suspension(.2,0,10000,1000)); }

 // MINECRAFT-551: the 5-arg directional overload.
 @Test void negativeNormalSpeedSelectsTheCompressionDamper() {
  // normalSpeed<0 (anchor moving toward the ground - the suspension compressing further) must
  // use damperCompression, never damperRebound.
  double withMatchingCompression=TireForces.suspension(.2,-5,10000,500,999999);
  double withMismatchedCompression=TireForces.suspension(.2,-5,10000,999999,500);
  assertEquals(10000*.2-(-5)*500, withMatchingCompression, 1e-9, "a negative normalSpeed must use damperCompression, not damperRebound");
  assertNotEquals(withMatchingCompression, withMismatchedCompression, 1e-9);
 }
 @Test void positiveNormalSpeedSelectsTheReboundDamper() {
  double withMatchingRebound=TireForces.suspension(.5,5,10000,999999,500);
  assertEquals(10000*.5-5*500, withMatchingRebound, 1e-9, "a positive normalSpeed must use damperRebound, not damperCompression");
 }
 @Test void zeroNormalSpeedIsUnaffectedByEitherDamperCoefficient() {
  // At normalSpeed==0 the damping term vanishes regardless of which coefficient is nominally
  // selected - the pure spring force (compression*spring) must be the entire result either way.
  assertEquals(10000*.2, TireForces.suspension(.2,0,10000,1,999999), 1e-9);
  assertEquals(10000*.2, TireForces.suspension(.2,0,10000,999999,1), 1e-9);
 }
 @Test void equalCompressionAndReboundDampersReproduceTheExistingSymmetricFormula() {
  java.util.Random rng=new java.util.Random(55100);
  for (int trial=0;trial<200;trial++) {
   double compression=rng.nextDouble()*0.3, normalSpeed=rng.nextDouble()*20-10, spring=1000+rng.nextDouble()*40000, damper=rng.nextDouble()*5000;
   assertEquals(TireForces.suspension(compression,normalSpeed,spring,damper),
     TireForces.suspension(compression,normalSpeed,spring,damper,damper), 0.0,
     "equal compression/rebound dampers must be byte-identical to the existing 4-arg symmetric formula, trial "+trial);
  }
 }
 @Test void directionalSuspensionForceIsNeverNegativeRegardlessOfWhichDamperDominates() {
  // Same dissipative/no-tensile-support guarantee as the 4-arg overload's own reboundNeverPullsGround.
  assertEquals(0, TireForces.suspension(0.05, 50, 1000, 2000, 2000));
  assertTrue(TireForces.suspension(0.05, -50, 1000, 2000, 2000) >= 0);
 }
}
