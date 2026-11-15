package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-528: {@code suspensionSpring}/{@code suspensionSpringForShare} generalized from an
 * implicit, unconfigurable {@code 1.0} spring-stiffness multiplier to an explicit, per-vehicle,
 * finite-bounded {@code Overrides.Tuning#suspensionSpringMultiplier}. Covers exactly the
 * evidence the ticket names: default equivalence with the original formula, a measurably
 * different (and honestly documented) ride-height equilibrium when the multiplier is actually
 * reconfigured, correct behavior across an unequal-load (asymmetric axle) geometry, and a
 * bounded production settling response that still actually converges with the damper basis the
 * new spring rate feeds.
 */
class PhysicsBridgeSuspensionSpringMultiplierTest {
 private static final double TRAVEL = 0.3;

 /**
  * Independent oracle, same rationale as {@code PhysicsBridgeSuspensionDampingRatioTest}'s own
  * "never compare only against the old overload" finding: a literal, independently-written copy
  * of the original formula, not the new overload's own delegation target.
  */
 @Test void defaultMultiplierOfOnePointZeroMatchesALiteralOracleOfTheOriginalFormula() {
  double mass=1500, gravity=11, wheelCount=4, loadShare=0.25;
  double literalOracleSuspensionSpring = mass*gravity/(wheelCount*TRAVEL*.5);
  assertEquals(literalOracleSuspensionSpring, PhysicsBridge.suspensionSpring(mass, 4, TRAVEL, gravity, 1.0), 1e-9,
    "suspensionSpring at multiplier 1.0 must match a literal, independently-written copy of the original formula");
  double literalOracleSuspensionSpringForShare = mass*loadShare*gravity/(TRAVEL*.5);
  assertEquals(literalOracleSuspensionSpringForShare, PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity, 1.0), 1e-9,
    "suspensionSpringForShare at multiplier 1.0 must match a literal, independently-written copy of the original formula");

  // The deprecated no-multiplier overloads (kept only for other pre-existing test call sites)
  // must still agree with the same oracle, confirming they were not left behind by this fix.
  assertEquals(literalOracleSuspensionSpring, PhysicsBridge.suspensionSpring(mass, 4, TRAVEL, gravity), 1e-9);
  assertEquals(literalOracleSuspensionSpringForShare, PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity), 1e-9);
 }

 @Test void springScalesLinearlyWithTheConfiguredMultiplierRegardlessOfLoadShare() {
  double mass=1500, gravity=11;
  for (double loadShare : new double[]{0.1, 0.25, 0.4}) {
   double atOne = PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity, 1.0);
   double atHalf = PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity, 0.5);
   double atDouble = PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity, 2.0);
   assertEquals(atOne, atHalf*2, 1e-9, "halving the configured multiplier must exactly halve the resulting spring for loadShare="+loadShare);
   assertEquals(atOne, atDouble/2, 1e-9, "doubling the configured multiplier must exactly double the resulting spring for loadShare="+loadShare);
  }
 }

 /** Same single-wheel settling simulation shape as
  * {@code PhysicsBridgeSuspensionDampingRatioTest#settleTrace} - isolates one wheel's own
  * share-calibrated spring/damper against its own share of the sprung mass. The damper here is
  * always derived from the SAME (possibly multiplier-scaled) spring via
  * {@code suspensionDamperForShare}, exactly as production {@code PhysicsBridge.stepGround}
  * computes {@code wheelDamper} from {@code wheelSpring} - so a configured spring multiplier
  * never leaves the damping basis stale or mismatched with the new spring rate. */
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

 /**
  * The ticket's own "document effect on equilibrium/ride height honestly" requirement, made
  * executable: UNLIKE suspensionDampingRatio (a pure settling-behavior knob that never moves the
  * equilibrium), the spring rate itself sets where the suspension settles. A stiffer spring
  * (multiplier &gt; 1) must settle to a SMALLER compression (ride HIGHER); a softer spring
  * (multiplier &lt; 1) must settle to a LARGER compression (ride LOWER) - in each case at
  * exactly {@code (TRAVEL*.5)/multiplier}, the algebraic consequence of scaling the rate that
  * balances the same static load.
  */
 @Test void aStifferSpringSettlesHigherAndASofterSpringSettlesLowerAtExactlyTheDocumentedEquilibrium() {
  double mass=1500, gravity=11, loadShare=0.25;
  int ticks=2000;

  double stifferMultiplier=1.5;
  double stifferSpring=PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity, stifferMultiplier);
  double stifferDamper=PhysicsBridge.suspensionDamperForShare(mass, loadShare, stifferSpring, 0.7);
  double[] stifferTrace=settleTrace(mass, loadShare, gravity, stifferSpring, stifferDamper, ticks);
  double stifferEquilibrium=(TRAVEL*.5)/stifferMultiplier;
  assertEquals(stifferEquilibrium, stifferTrace[ticks-1], 1e-3,
    "a 1.5x stiffer spring must settle at exactly (travel*.5)/1.5 - a SMALLER compression, i.e. riding HIGHER than the unconfigured default");
  assertTrue(stifferEquilibrium < TRAVEL*.5, "the stiffer equilibrium must sit above (less compressed than) the unconfigured travel*.5 default");

  double softerMultiplier=0.6;
  double softerSpring=PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity, softerMultiplier);
  double softerDamper=PhysicsBridge.suspensionDamperForShare(mass, loadShare, softerSpring, 0.7);
  double[] softerTrace=settleTrace(mass, loadShare, gravity, softerSpring, softerDamper, ticks);
  double softerEquilibrium=(TRAVEL*.5)/softerMultiplier;
  assertEquals(softerEquilibrium, softerTrace[ticks-1], 1e-3,
    "a 0.6x softer spring must settle at exactly (travel*.5)/0.6 - a LARGER compression, i.e. riding LOWER than the unconfigured default");
  assertTrue(softerEquilibrium > TRAVEL*.5, "the softer equilibrium must sit below (more compressed than) the unconfigured travel*.5 default");

  // The unconfigured multiplier (1.0) must remain exactly the original travel*.5 equilibrium.
  double defaultSpring=PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity, 1.0);
  double defaultDamper=PhysicsBridge.suspensionDamperForShare(mass, loadShare, defaultSpring, 0.7);
  double[] defaultTrace=settleTrace(mass, loadShare, gravity, defaultSpring, defaultDamper, ticks);
  assertEquals(TRAVEL*.5, defaultTrace[ticks-1], 1e-6,
    "the unconfigured multiplier (1.0) must settle at exactly the original travel*.5 equilibrium, unchanged");
 }

 @Test void sharedConfiguredMultiplierAppliesConsistentlyAcrossAnAsymmetricUnequalLoadAxle() {
  // Same asymmetric-axle premise PhysicsBridgeSuspensionDampingRatioTest's own equivalent test
  // uses: unequal loadShares give different absolute spring/damper rates, but a single
  // operator-configured multiplier is the dimensionless scale shared by both - each side must
  // still settle to the SAME multiplier-scaled equilibrium, only the approach dynamics differ.
  double mass=1500, gravity=11;
  double heavierShare=0.35, lighterShare=0.15;
  double multiplier=1.4;
  double equilibrium=(TRAVEL*.5)/multiplier;
  for (double share : new double[]{heavierShare, lighterShare}) {
   double spring=PhysicsBridge.suspensionSpringForShare(mass, share, TRAVEL, gravity, multiplier);
   double damper=PhysicsBridge.suspensionDamperForShare(mass, share, spring, 0.7);
   double[] trace=settleTrace(mass, share, gravity, spring, damper, 2000);
   assertEquals(equilibrium, trace[trace.length-1], 1e-3,
     "both the heavier and lighter wheel of an asymmetric axle must settle at the SAME multiplier-scaled equilibrium, loadShare="+share);
  }
 }

 /**
  * Direct evidence that a multiplier within {@code Overrides.Tuning}'s own validated range (see
  * {@code OverridesTest#suspensionSpringMultiplierOutOfRangeFailsValidation} and the combined
  * cross-field stability tests) produces a genuinely BOUNDED production settling response: no
  * overshoot, and converged by the late window, not merely approached early - the same shape
  * {@code PhysicsBridgeSuspensionDampingRatioTest}'s own upper-bound evidence test uses.
  */
 @Test void aValidatedInRangeMultiplierProducesABoundedNonOscillatingSettlingResponse() {
  double mass=1500, gravity=11, loadShare=0.25, multiplier=1.8, dampingRatio=0.7;
  double spring=PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity, multiplier);
  double damper=PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring, dampingRatio);
  double equilibrium=(TRAVEL*.5)/multiplier;
  int ticks=3000;
  double[] trace=settleTrace(mass, loadShare, gravity, spring, damper, ticks);
  double max=java.util.Arrays.stream(trace).max().getAsDouble();
  assertTrue(max <= equilibrium + 1e-4,
    "a validated in-range multiplier paired with the default damping ratio must not overshoot its own (multiplier-scaled) equilibrium - max observed "+max);
  double lateWindowMin=Double.MAX_VALUE, lateWindowMax=-Double.MAX_VALUE;
  for (int t=ticks-300;t<ticks;t++) { lateWindowMin=Math.min(lateWindowMin, trace[t]); lateWindowMax=Math.max(lateWindowMax, trace[t]); }
  assertEquals(equilibrium, lateWindowMin, 1e-3, "a validated in-range multiplier must have actually CONVERGED by the late window, not just approached once early");
  assertEquals(equilibrium, lateWindowMax, 1e-3, "a validated in-range multiplier must STAY converged through the late window, not drift back away from equilibrium");
 }

 /**
  * Review finding (comment 53996): the only production settling coverage exercised a single
  * stiff-side multiplier (1.8); the lower (soft) end - where {@code Overrides.Tuning}'s own
  * {@code suspensionSpringMultiplier} doc now derives a hard {@code 0.5} floor from the SAME
  * equilibrium formula this test exercises - had none. Covers a genuinely bounded, non-
  * oscillating settling response just above that floor, across more than one
  * {@code suspensionTravel}/{@code suspensionDampingRatio} combination. Every ratio below is
  * AT OR ABOVE 1.0 (critical damping or overdamped) deliberately - a ratio below 1.0 (including
  * the unconfigured 0.7 default) is UNDERDAMPED BY DESIGN (see suspensionDampingRatio's own
  * doc) and is EXPECTED to overshoot; "must not overshoot" is only ever a valid assertion at or
  * above critical damping, the same choice {@code PhysicsBridgeSuspensionDampingRatioTest}'s own
  * no-overshoot evidence test makes (ratio 1.8, never the 0.7 default).
  */
 @Test void aMultiplierJustAboveTheReachableEquilibriumLowerBoundStillProducesABoundedSettlingResponseAcrossTravelAndRatioCombinations() {
  double mass=1500, gravity=11, loadShare=0.25;
  int ticks=3000;
  // (travel, multiplier, dampingRatio) triples, each comfortably inside Overrides.Tuning's own
  // validated range for that exact pairing (confirmed against maxStableSuspensionDampingRatio's
  // own formula before writing this test) - the soft end (multiplier just above 0.5) combined
  // with both critical damping and a materially overdamped ratio, at more than one travel.
  double[][] cases = {
    {0.3, 0.6, 1.0},
    {0.3, 0.55, 1.5},
    {0.2, 0.55, 1.0},
  };
  for (double[] c : cases) {
   double travel=c[0], multiplier=c[1], dampingRatio=c[2];
   double spring=PhysicsBridge.suspensionSpringForShare(mass, loadShare, travel, gravity, multiplier);
   double damper=PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring, dampingRatio);
   double equilibrium=(travel*.5)/multiplier;
   assertTrue(equilibrium < travel, "test setup sanity: this case's own equilibrium must sit strictly below its own travel - travel="+travel+" multiplier="+multiplier);
   double[] trace=settleTrace(mass, loadShare, gravity, spring, damper, ticks);
   double max=java.util.Arrays.stream(trace).max().getAsDouble();
   assertTrue(max <= equilibrium + 1e-4,
     "travel="+travel+" multiplier="+multiplier+" ratio="+dampingRatio+" must not overshoot its own equilibrium - max observed "+max);
   double lateWindowMin=Double.MAX_VALUE, lateWindowMax=-Double.MAX_VALUE;
   for (int t=ticks-300;t<ticks;t++) { lateWindowMin=Math.min(lateWindowMin, trace[t]); lateWindowMax=Math.max(lateWindowMax, trace[t]); }
   assertEquals(equilibrium, lateWindowMin, 1e-3, "travel="+travel+" multiplier="+multiplier+" ratio="+dampingRatio+" must have actually CONVERGED by the late window");
   assertEquals(equilibrium, lateWindowMax, 1e-3, "travel="+travel+" multiplier="+multiplier+" ratio="+dampingRatio+" must STAY converged through the late window");
  }
 }

 /**
  * Review finding (comment 53996), the physical justification itself, independent of
  * {@code Overrides.Tuning#validate}'s own rejection: AT OR BELOW the {@code 0.5} boundary, the
  * formula's own claimed equilibrium ({@code (travel*.5)/multiplier}) is AT OR PAST
  * {@code suspensionTravel} itself. This single-wheel settling harness (unlike real production
  * play) has no raycast/contact-loss ceiling built in, so it mathematically settles AT that
  * claimed value regardless of whether it exceeds {@code suspensionTravel} - which is exactly
  * the point: a real wheel's raycast length is hard-capped at {@code radius+suspensionTravel}
  * (see {@code PhysicsBridge.stepGround}), so a real wheel can never actually reach a
  * compression past {@code suspensionTravel} the way this harness's own idealized point-mass
  * oscillator can - it loses ground contact entirely first. The claimed equilibrium settling
  * PAST {@code suspensionTravel} here is therefore direct evidence that it is physically
  * unreachable in the real, travel-limited model, not merely a large-but-attainable number.
  */
 @Test void atOrBelowTheLowerBoundTheClaimedEquilibriumExceedsSuspensionTravelItself() {
  double mass=1500, gravity=11, loadShare=0.25, travel=0.3;
  int ticks=2000;

  double atBoundary=0.5;
  double claimedEquilibriumAtBoundary=(travel*.5)/atBoundary;
  assertEquals(travel, claimedEquilibriumAtBoundary, 1e-12,
    "the formula's own claimed equilibrium AT exactly the 0.5 boundary must equal suspensionTravel itself - the exact 'fully bottomed out' case, zero remaining travel");

  double belowBoundary=0.3;
  double claimedEquilibriumBelowBoundary=(travel*.5)/belowBoundary;
  assertTrue(claimedEquilibriumBelowBoundary > travel,
    "below the boundary the formula's own claimed equilibrium must exceed suspensionTravel itself");
  double spring=PhysicsBridge.suspensionSpringForShare(mass, loadShare, travel, gravity, belowBoundary);
  double damper=PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring, 0.7);
  double[] trace=settleTrace(mass, loadShare, gravity, spring, damper, ticks);
  double lateWindowMin=Double.MAX_VALUE, lateWindowMax=-Double.MAX_VALUE;
  for (int t=ticks-300;t<ticks;t++) { lateWindowMin=Math.min(lateWindowMin, trace[t]); lateWindowMax=Math.max(lateWindowMax, trace[t]); }
  assertEquals(claimedEquilibriumBelowBoundary, lateWindowMin, 1e-3,
    "below the 0.5 boundary, this idealized point-mass harness settles EXACTLY at the formula's own claimed equilibrium - which this test's own setup sanity above already proved exceeds suspensionTravel, confirming a real (travel-limited) wheel could never actually reach it");
  assertEquals(claimedEquilibriumBelowBoundary, lateWindowMax, 1e-3);
 }
}
