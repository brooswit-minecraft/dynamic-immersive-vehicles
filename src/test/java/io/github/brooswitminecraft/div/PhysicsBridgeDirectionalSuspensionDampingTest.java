package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-551: {@code PhysicsBridge.directionalDampingRatio} (the pure-math per-wheel
 * resolution step {@code stepGround} wires into {@code TireForces.suspension}'s 5-arg overload -
 * see that call site's own doc) and the production formula-level evidence for the ticket's own
 * named acceptance shape: default/equal-value equivalence, compression/rebound selection and
 * zero, axle-distribution preservation, and inheritance.
 */
class PhysicsBridgeDirectionalSuspensionDampingTest {
 private static final double TRAVEL = 0.3;
 private static final double[] Z4 = {1.5, 1.5, -1.5, -1.5}; // front axle (0,1), rear axle (2,3)

 @Test void unconfiguredDirectionInheritsExactlyTheAxleResolvedBase() {
  assertEquals(0.7, PhysicsBridge.directionalDampingRatio(null, 0.7), 0.0,
    "an unconfigured direction must inherit exactly the axle-resolved base ratio, never a second default");
  assertEquals(1.2, PhysicsBridge.directionalDampingRatio(null, 1.2), 0.0);
 }

 @Test void configuredDirectionOverridesTheAxleResolvedBase() {
  assertEquals(1.5, PhysicsBridge.directionalDampingRatio(1.5, 0.7), 0.0,
    "a configured directional override must win over the axle-resolved base");
 }

 /** Mirrors {@code PhysicsBridgeSuspensionDampingRatioTest#settleTrace}, generalized to the new
  * 5-arg directional overload - production's own per-wheel compression/rebound selection is this
  * same formula fed into {@code TireForces.suspension(compression, velocity, spring, damperCompression,
  * damperRebound)}. */
 private static double[] settleTrace(double mass, double loadShare, double gravity, double spring, double damperCompression, double damperRebound, int ticks) {
  double effectiveMass = mass*loadShare;
  double compression=0, velocity=0;
  double[] trace=new double[ticks];
  for (int t=0;t<ticks;t++) {
   double normal=Math.min(mass*gravity, TireForces.suspension(compression, velocity, spring, damperCompression, damperRebound));
   double netAcceleration = -gravity + normal/effectiveMass;
   velocity = velocity + PhysicsBridge.DT*netAcceleration;
   compression = compression - PhysicsBridge.DT*velocity;
   trace[t]=compression;
  }
  return trace;
 }
 /** Same simulation shape, but through the existing 4-arg symmetric overload - the independent
  * oracle equalCompressionAndReboundDampersSettleIdenticallyToTheExistingSymmetricFormula below
  * compares against. */
 private static double[] settleTraceSymmetric(double mass, double loadShare, double gravity, double spring, double damper, int ticks) {
  double effectiveMass = mass*loadShare;
  double compression=0, velocity=0;
  double[] trace=new double[ticks];
  for (int t=0;t<ticks;t++) {
   double normal=Math.min(mass*gravity, TireForces.suspension(compression, velocity, spring, damper));
   double netAcceleration = -gravity + normal/effectiveMass;
   velocity = velocity + PhysicsBridge.DT*netAcceleration;
   compression = compression - PhysicsBridge.DT*velocity;
   trace[t]=compression;
  }
  return trace;
 }

 @Test void equalCompressionAndReboundDampersSettleIdenticallyToTheExistingSymmetricFormula() {
  double mass=1500, gravity=11, loadShare=0.25;
  double spring=PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity);
  double symmetricDamper=PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring, 1.0);
  double[] symmetricOracleTrace=settleTraceSymmetric(mass, loadShare, gravity, spring, symmetricDamper, 1000);
  double[] directionalTrace=settleTrace(mass, loadShare, gravity, spring, symmetricDamper, symmetricDamper, 1000);
  assertArrayEquals(symmetricOracleTrace, directionalTrace, 0.0,
    "equal compression/rebound dampers must settle through a byte-identical trace to the existing symmetric 4-arg formula");
 }

 /** Counts distinct excursions of {@code trace} above {@code threshold} (a rising edge followed
  * eventually by a falling edge back to/below it) - a robust, peak-finding-free way to measure
  * "how many times did this oscillator swing back past equilibrium", without depending on exact
  * sample-level local-maximum detection. */
 private static int excursionsAbove(double[] trace, double threshold) {
  int count=0; boolean above=false;
  for (double v : trace) {
   if (v>threshold && !above) { count++; above=true; }
   else if (v<=threshold) { above=false; }
  }
  return count;
 }

 /**
  * Core "separate compression and rebound" evidence: a soft compression ratio (lets the wheel
  * dive quickly into a bump) combined with a stiff rebound ratio (resists the wheel springing
  * back out again) must visibly change the settling shape versus the symmetric all-soft case -
  * direct proof the two coefficients are actually independent in production's own formula, not
  * merely two names for the same number. The INITIAL dive (the very first excursion past
  * equilibrium) is driven entirely by the shared soft compression damper, so it is identical
  * between the two cases by construction; what must differ is what happens AFTER the anchor
  * starts moving back up (normalSpeed>0, selecting damperRebound) - the stiffer rebound damper
  * removes enough energy on that first bounce-back that the asymmetric vehicle never swings past
  * equilibrium again, while the all-soft vehicle (underdamped in BOTH directions) keeps
  * oscillating past it several more times before settling.
  */
 @Test void asymmetricCompressionAndReboundRatiosProduceADifferentSettlingShapeThanTheAllSoftSymmetricCase() {
  double mass=1500, gravity=11, loadShare=0.25;
  double spring=PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity);
  double equilibrium=TRAVEL*.5, overshootTolerance=1e-4;
  double softRatio=0.3, stiffRatio=1.6;
  double softDamper=PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring, softRatio);
  double stiffDamper=PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring, stiffRatio);

  double[] allSoft=settleTrace(mass, loadShare, gravity, spring, softDamper, softDamper, 600);
  double[] softCompressionStiffRebound=settleTrace(mass, loadShare, gravity, spring, softDamper, stiffDamper, 600);

  assertFalse(java.util.Arrays.equals(allSoft, softCompressionStiffRebound),
    "a soft-compression/stiff-rebound vehicle must settle differently than an all-soft vehicle");
  // The very first excursion (the initial dive) must be identical in both - same soft
  // compression damper, same compression-phase physics, before normalSpeed ever turns positive.
  assertEquals(allSoft[5], softCompressionStiffRebound[5], 1e-9,
    "the initial compression-phase dive must be identical - both use the same soft compression damper until normalSpeed turns positive");

  int allSoftExcursions=excursionsAbove(allSoft, equilibrium+overshootTolerance);
  int asymmetricExcursions=excursionsAbove(softCompressionStiffRebound, equilibrium+overshootTolerance);
  assertTrue(allSoftExcursions>1,
    "test setup sanity: the all-soft (underdamped both ways) vehicle must actually oscillate past equilibrium more than once - observed "+allSoftExcursions);
  assertTrue(asymmetricExcursions < allSoftExcursions,
    "a stiffer rebound damper must cut off the bounce-back oscillation sooner than the all-soft case - allSoft excursions="+allSoftExcursions+" asymmetric excursions="+asymmetricExcursions);
 }

 @Test void zeroVelocityEquilibriumIsUnaffectedByEitherDirectionalRatio() {
  // At the settled equilibrium (normalSpeed==0) neither damper coefficient matters - both a
  // soft/stiff and a stiff/soft asymmetric vehicle must settle at the exact same TRAVEL*.5
  // equilibrium as the symmetric baseline, since damping supplies no static support.
  double mass=1500, gravity=11, loadShare=0.25;
  double spring=PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity);
  double equilibrium=TRAVEL*.5;
  double[] softStiff=settleTrace(mass, loadShare, gravity, spring,
    PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring, 0.3),
    PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring, 1.6), 3000);
  double[] stiffSoft=settleTrace(mass, loadShare, gravity, spring,
    PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring, 1.6),
    PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring, 0.3), 3000);
  assertEquals(equilibrium, softStiff[softStiff.length-1], 1e-3);
  assertEquals(equilibrium, stiffSoft[stiffSoft.length-1], 1e-3);
 }

 @Test void axleDistributionIsPreservedWhenComposedWithDirectionalRatios() {
  // The axle-resolved base (AxleSuspensionDistribution.dampingRatios) still differs between
  // front and rear exactly as before this ticket; directionalDampingRatio only ever substitutes
  // a VEHICLE-WIDE override on top of that per-wheel base, never changing which axle a wheel
  // belongs to.
  double[] axleRatios=AxleSuspensionDistribution.dampingRatios(Z4, 0.7, 1.2, 0.5);
  assertEquals(1.2, axleRatios[0], 0.0); assertEquals(1.2, axleRatios[1], 0.0);
  assertEquals(0.5, axleRatios[2], 0.0); assertEquals(0.5, axleRatios[3], 0.0);
  // Unconfigured directions inherit each wheel's own distinct axle value unchanged.
  for (int i=0;i<4;i++) {
   assertEquals(axleRatios[i], PhysicsBridge.directionalDampingRatio(null, axleRatios[i]), 0.0,
     "wheel "+i+": an unconfigured direction must inherit this wheel's own axle-resolved value, preserving the front/rear split");
  }
  // A configured vehicle-wide directional override applies identically to every wheel regardless
  // of axle - a deliberate, documented precedence (see Overrides.Tuning#suspensionDampingRatioCompression's doc).
  for (int i=0;i<4;i++) {
   assertEquals(0.9, PhysicsBridge.directionalDampingRatio(0.9, axleRatios[i]), 0.0,
     "wheel "+i+": a configured vehicle-wide directional override must apply uniformly regardless of axle");
  }
 }
}
