package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-539: production spring/damper calculations (see {@code PhysicsBridge.stepGround}'s
 * own {@code axleSpringMultipliers}/{@code axleDampingRatios} wiring) consume
 * {@link AxleSuspensionDistribution}'s resolved per-wheel value, not the flat vehicle-wide
 * {@code suspensionSpringMultiplier}/{@code suspensionDampingRatio}, once an axle-specific field
 * is configured. Covers the ticket's own named acceptance shape at the production-formula level:
 * asymmetric loads, common heave (both wheels of one axle), both axles configured together, and
 * missing-settings/default equivalence.
 */
class PhysicsBridgeAxleSuspensionTuningTest {
 private static final double TRAVEL = 0.3;
 private static final double[] Z4 = {1.5, 1.5, -1.5, -1.5}; // front axle (0,1), rear axle (2,3)

 /** Mirrors {@code PhysicsBridgeSuspensionSpringMultiplierTest#settleTrace}. */
 private static double[] settleTrace(double mass, double loadShare, double gravity, double spring, double damper, int ticks) {
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

 @Test void missingAxleSettingsReproduceTheExactBaselineOnEveryWheel() {
  double mass=1500, gravity=11, baseMultiplier=1.0, baseRatio=0.7, loadShare=0.25;
  double[] multipliers = AxleSuspensionDistribution.springMultipliers(Z4, baseMultiplier, null, null);
  double[] ratios = AxleSuspensionDistribution.dampingRatios(Z4, baseRatio, null, null);
  double baselineSpring = PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity, baseMultiplier);
  double baselineDamper = PhysicsBridge.suspensionDamperForShare(mass, loadShare, baselineSpring, baseRatio);
  for (int i=0;i<4;i++) {
   double spring=PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity, multipliers[i]);
   double damper=PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring, ratios[i]);
   assertEquals(baselineSpring, spring, 1e-9, "wheel "+i+": unconfigured axle fields must reproduce the exact baseline spring");
   assertEquals(baselineDamper, damper, 1e-9, "wheel "+i+": unconfigured axle fields must reproduce the exact baseline damper");
  }
 }

 @Test void bothAxlesConfiguredTogetherSettleAtTheirOwnIndependentEquilibria() {
  double mass=1500, gravity=11, loadShare=0.25;
  double frontMultiplier=1.6, rearMultiplier=0.7, dampingRatio=1.0; // critical damping, no overshoot expected
  double[] multipliers = AxleSuspensionDistribution.springMultipliers(Z4, 1.0, frontMultiplier, rearMultiplier);
  double[] ratios = AxleSuspensionDistribution.dampingRatios(Z4, 0.7, dampingRatio, dampingRatio);

  double frontSpring=PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity, multipliers[0]);
  double frontDamper=PhysicsBridge.suspensionDamperForShare(mass, loadShare, frontSpring, ratios[0]);
  double[] frontTrace=settleTrace(mass, loadShare, gravity, frontSpring, frontDamper, 3000);
  double frontEquilibrium=(TRAVEL*.5)/frontMultiplier;
  assertEquals(frontEquilibrium, frontTrace[frontTrace.length-1], 1e-3, "front axle must settle at its OWN configured multiplier's equilibrium");

  double rearSpring=PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity, multipliers[2]);
  double rearDamper=PhysicsBridge.suspensionDamperForShare(mass, loadShare, rearSpring, ratios[2]);
  double[] rearTrace=settleTrace(mass, loadShare, gravity, rearSpring, rearDamper, 3000);
  double rearEquilibrium=(TRAVEL*.5)/rearMultiplier;
  assertEquals(rearEquilibrium, rearTrace[rearTrace.length-1], 1e-3, "rear axle must settle at its OWN independently-configured multiplier's equilibrium");

  assertNotEquals(frontEquilibrium, rearEquilibrium, 1e-6, "test setup sanity: the two axles must actually have distinct equilibria");
 }

 @Test void asymmetricLoadAndAxleOverrideComposeIndependently() {
  // An asymmetric COM offset (unequal loadShare) and an axle-specific multiplier are orthogonal
  // inputs - each wheel's own loadShare still scales its absolute spring rate, while the AXLE
  // grouping alone decides which multiplier applies; this must match for every (loadShare,
  // axle-multiplier) combination, not just the symmetric 0.25 case.
  double mass=1500, gravity=11;
  double[] multipliers = AxleSuspensionDistribution.springMultipliers(Z4, 1.0, 2.0, 0.6);
  double heavierFrontShare=0.35, lighterFrontShare=0.15;
  double equilibriumFront=(TRAVEL*.5)/2.0;
  for (double share : new double[]{heavierFrontShare, lighterFrontShare}) {
   double spring=PhysicsBridge.suspensionSpringForShare(mass, share, TRAVEL, gravity, multipliers[0]);
   double damper=PhysicsBridge.suspensionDamperForShare(mass, share, spring, 1.0);
   double[] trace=settleTrace(mass, share, gravity, spring, damper, 3000);
   assertEquals(equilibriumFront, trace[trace.length-1], 1e-3,
     "both the heavier and lighter front wheel must settle at the SAME front-axle-configured equilibrium regardless of their own loadShare, share="+share);
  }
 }

 @Test void commonHeaveSettlesIdenticallyForBothWheelsOnTheSameAxle() {
  // "Common heave": both wheels on one axle, same load share, must settle identically when
  // moving together - direct evidence the per-axle resolution does not introduce any spurious
  // left/right asymmetry (only front/rear differs).
  double mass=1500, gravity=11, loadShare=0.25;
  double[] multipliers = AxleSuspensionDistribution.springMultipliers(Z4, 1.0, 1.5, 0.8);
  double[] ratios = AxleSuspensionDistribution.dampingRatios(Z4, 0.7, 1.2, 0.9);
  double spring0=PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity, multipliers[0]);
  double damper0=PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring0, ratios[0]);
  double spring1=PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity, multipliers[1]);
  double damper1=PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring1, ratios[1]);
  assertEquals(spring0, spring1, 0.0, "both front wheels must resolve the exact same spring rate");
  assertEquals(damper0, damper1, 0.0, "both front wheels must resolve the exact same damper");
  double[] trace0=settleTrace(mass, loadShare, gravity, spring0, damper0, 2000);
  double[] trace1=settleTrace(mass, loadShare, gravity, spring1, damper1, 2000);
  assertArrayEquals(trace0, trace1, 0.0, "both front wheels must settle through an identical trace");
 }
}
