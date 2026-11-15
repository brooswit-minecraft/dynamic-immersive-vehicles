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
}
