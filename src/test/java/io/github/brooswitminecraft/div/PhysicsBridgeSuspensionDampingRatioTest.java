package io.github.brooswitminecraft.div;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-523: {@code suspensionDamperForShare}/{@code suspensionDamper}/{@code
 * effectiveMassDamper} generalized from a hardcoded {@code .7} critical-damping-ratio multiplier
 * to an explicit, per-vehicle, finite-bounded {@code Overrides.Tuning#suspensionDampingRatio}.
 * Covers exactly the evidence the ticket names: default equivalence with the old hardcoded
 * constant, a changed (measurably different) settling response when the ratio is actually
 * reconfigured, correct behavior across an unequal-load (asymmetric axle) geometry, and the
 * explicit interaction with the opt-in experimental effective-mass damping candidate so a
 * configured ratio cannot silently disappear depending on whether that unrelated candidate is
 * enabled.
 */
class PhysicsBridgeSuspensionDampingRatioTest {
 private static final double TRAVEL = 0.3;

 /**
  * Review finding (ticket comment 53827): comparing the new ratio-explicit overload only
  * against the OLD overload - which itself just delegates to the new one with a hardcoded 0.7 -
  * never independently proves anything; both sides would move together even if the shared
  * implementation were wrong. This instead hardcodes a LITERAL copy of the exact original
  * formula (the same literal {@code 2*Math.sqrt(...)*.7} this class's own pre-ticket source once
  * had) as an independent oracle, so a real regression in the generalized implementation would
  * actually be caught.
  */
 @Test void defaultRatioOfPoint7MatchesALiteralOracleOfTheOriginalHardcodedFormula() {
  double mass=1500, spring=35750.0, loadShare=0.25;
  double literalOracleSuspensionDamper = 2*Math.sqrt(spring*mass/4)*.7;
  assertEquals(literalOracleSuspensionDamper, PhysicsBridge.suspensionDamper(mass, 4, spring, 0.7), 1e-12,
    "suspensionDamper at ratio 0.7 must match a literal, independently-written copy of the original hardcoded formula");
  double literalOracleSuspensionDamperForShare = 2*Math.sqrt(spring*mass*loadShare)*.7;
  assertEquals(literalOracleSuspensionDamperForShare, PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring, 0.7), 1e-12,
    "suspensionDamperForShare at ratio 0.7 must match a literal, independently-written copy of the original hardcoded formula");

  Vector3d point=new Vector3d(0.3,0.1,0.5), up=new Vector3d(0,1,0);
  Quaterniond identity=new Quaterniond();
  Vector3d inertia=new Vector3d(1200,1300,900);
  var candidate = PhysicsBridge.effectiveMassDamper(spring, mass, point, up, identity, inertia, 0.7, 123.0);
  double literalOracleEffectiveMassDamper = 2*Math.sqrt(spring*candidate.effectiveMass())*.7;
  assertEquals(literalOracleEffectiveMassDamper, candidate.damper(), 1e-9,
    "effectiveMassDamper's own candidate formula at ratio 0.7 must match a literal, independently-written copy of the original hardcoded formula");

  // The deprecated hardcoded-ratio overloads (kept only for other pre-existing test call sites)
  // must still agree with the same oracle, confirming they were not left behind by this fix.
  assertEquals(literalOracleSuspensionDamper, PhysicsBridge.suspensionDamper(mass, 4, spring), 1e-12);
  assertEquals(literalOracleSuspensionDamperForShare, PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring), 1e-12);
 }

 @Test void damperScalesLinearlyWithTheConfiguredRatioRegardlessOfLoadShare() {
  double mass=1500, gravity=11, spring=suspensionSpring(mass, gravity);
  for (double loadShare : new double[]{0.1, 0.25, 0.4}) {
   double wheelSpring = PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity);
   double atPoint7 = PhysicsBridge.suspensionDamperForShare(mass, loadShare, wheelSpring, 0.7);
   double atPoint35 = PhysicsBridge.suspensionDamperForShare(mass, loadShare, wheelSpring, 0.35);
   double atOnePoint4 = PhysicsBridge.suspensionDamperForShare(mass, loadShare, wheelSpring, 1.4);
   assertEquals(atPoint7, atPoint35*2, 1e-9, "halving the configured ratio must exactly halve the resulting damper for loadShare="+loadShare);
   assertEquals(atPoint7, atOnePoint4/2, 1e-9, "doubling the configured ratio must exactly double the resulting damper for loadShare="+loadShare);
  }
 }

 private static double suspensionSpring(double mass, double gravity) { return PhysicsBridge.suspensionSpring(mass, 4, TRAVEL, gravity); }

 /** Same semi-implicit-Euler single-wheel settling simulation shape as
  * {@code PhysicsBridgeRideHeightSimulationTest}/{@code PhysicsBridgeWheelLoadShareSimulationTest}
  * - starts fully extended (compression 0, at rest) and settles toward the calibrated
  * {@code TRAVEL*.5} equilibrium under gravity, the production {@link TireForces#suspension}
  * force law, and {@link PhysicsBridge#DT}. Unlike that multi-wheel test (which sums every
  * wheel's normal force against the FULL chassis mass, coupled into one shared acceleration),
  * this isolates a SINGLE wheel's own share-calibrated spring/damper against its own share of the
  * sprung mass ({@code mass*loadShare}) - the effective single-DOF inertia
  * {@code suspensionDamperForShare}'s own critical-damping-ratio formula is actually calibrated
  * against (see its doc: {@code 2*sqrt(spring*mass*loadShare)*ratio}). Dividing by the full
  * vehicle mass instead would desynchronize the simulated oscillator from the ratio the formula
  * assumes and was observed to produce spurious numerical blow-up unrelated to the ratio itself.
  * Returns the full compression trace so callers can check for overshoot past equilibrium (the
  * underdamped signature) rather than only the final value. */
 private static double[] settleTrace(double mass, double loadShare, double gravity, double spring, double damper, int ticks) {
  // The safety cap itself is deliberately against the FULL vehicle mass, exactly matching
  // PhysicsBridge.stepGround's own `Math.min(mass*gravity, ...)` clamp (see
  // suspensionSpringForShare's own doc: "the vehicle-wide mass*gravity safety cap is UNCHANGED
  // - it is documented as a safety cap, not a load-distribution model") - capping it at this
  // wheel's own share instead would be a materially different (and much tighter, easily-hit)
  // bound that does not match production and was observed to desynchronize this single-wheel
  // simulation into runaway compression.
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
  * Review finding (ticket comment 53827): the first revision of this test only checked a
  * "closest approach within the first 200 ticks" window for its overdamped case at ratio 2.5 -
  * which masked SUSTAINED long-run chatter that never actually converges (confirmed by an
  * independent 6000-tick sweep at this project's documented default example: ratio 2.5 and 3.0
  * both settle into a persistent, non-decaying ~0.015-0.045 compression spread, never reaching
  * the 0.15 equilibrium). That is a genuine numerical-stability failure of
  * Body.stepGround's explicit-damping-term integration at those ratios, not a cosmetic wobble -
  * see {@link Overrides.Tuning#suspensionDampingRatio}'s own doc for the measured ~2.2-2.3
  * instability onset. {@code suspensionDampingRatio}'s validated range is now capped at 2.0
  * specifically to stay below that onset; this test uses an overdamped ratio (1.8) INSIDE the
  * now-supported range and asserts genuine LATE-WINDOW convergence (the last 200 of 2000 ticks
  * all within a tight tolerance of equilibrium), not merely an early closest-approach.
  */
 @Test void changedSettlingResponseAnUnderdampedRatioOvershootsEquilibriumWhileASupportedOverdampedRatioConvergesAndStaysConverged() {
  double mass=1500, gravity=11;
  double spring=PhysicsBridge.suspensionSpringForShare(mass, 0.25, TRAVEL, gravity);
  double equilibrium=TRAVEL*.5;
  double overshootTolerance=1e-4;

  double underdampedDamper=PhysicsBridge.suspensionDamperForShare(mass, 0.25, spring, 0.15);
  double[] underdampedTrace=settleTrace(mass, 0.25, gravity, spring, underdampedDamper, 400);
  double underdampedMax=java.util.Arrays.stream(underdampedTrace).max().getAsDouble();
  assertTrue(underdampedMax > equilibrium + overshootTolerance,
    "a strongly underdamped ratio (0.15) must visibly overshoot the "+equilibrium+" equilibrium - max observed "+underdampedMax);

  double overdampedRatio=1.8; // inside the validated (0,2.0] range, with margin below the measured instability onset
  double overdampedDamper=PhysicsBridge.suspensionDamperForShare(mass, 0.25, spring, overdampedRatio);
  int ticks=2000;
  double[] overdampedTrace=settleTrace(mass, 0.25, gravity, spring, overdampedDamper, ticks);
  double overdampedMax=java.util.Arrays.stream(overdampedTrace).max().getAsDouble();
  assertTrue(overdampedMax <= equilibrium + overshootTolerance,
    "an overdamped ratio ("+overdampedRatio+") within the validated range must settle toward "+equilibrium+" without overshooting - max observed "+overdampedMax);
  double lateWindowMin=Double.MAX_VALUE, lateWindowMax=-Double.MAX_VALUE;
  for (int t=ticks-200;t<ticks;t++) { lateWindowMin=Math.min(lateWindowMin, overdampedTrace[t]); lateWindowMax=Math.max(lateWindowMax, overdampedTrace[t]); }
  assertEquals(equilibrium, lateWindowMin, 1e-3, "an overdamped ratio within the validated range must have actually CONVERGED by the late window, not just approached once early");
  assertEquals(equilibrium, lateWindowMax, 1e-3, "an overdamped ratio within the validated range must STAY converged through the late window, not drift back away from equilibrium");
 }

 /**
  * Direct evidence for the validated upper bound itself (ticket comment 53827's "establish a
  * stable supported range" ask): the documented 2.0 cap must actually be stable over a long
  * settling window at this project's documented default example, not merely "less unstable"
  * than the previously-allowed 3.0.
  */
 @Test void theValidatedUpperBoundOfTwoRemainsNumericallyStableOverALongSettlingWindow() {
  double mass=1500, gravity=11, loadShare=0.25;
  double spring=PhysicsBridge.suspensionSpringForShare(mass, loadShare, TRAVEL, gravity);
  double equilibrium=TRAVEL*.5;
  double damperAtCap=PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring, 2.0);
  int ticks=6000;
  double[] trace=settleTrace(mass, loadShare, gravity, spring, damperAtCap, ticks);
  double lateWindowMin=Double.MAX_VALUE, lateWindowMax=-Double.MAX_VALUE;
  for (int t=ticks-500;t<ticks;t++) { lateWindowMin=Math.min(lateWindowMin, trace[t]); lateWindowMax=Math.max(lateWindowMax, trace[t]); }
  assertEquals(equilibrium, lateWindowMin, 1e-6, "the validated upper bound (ratio 2.0) must remain exactly converged over a long settling window, not merely early on");
  assertEquals(equilibrium, lateWindowMax, 1e-6, "the validated upper bound (ratio 2.0) must show no sustained chatter/drift in the late window");
 }

 /**
  * Review finding (comments 53839/53840): the flat 2.0 ceiling alone only proves stable at this
  * project's documented default {@code suspensionTravel} (0.3) - at a SMALLER, independently
  * valid {@code suspensionTravel} (0.2, well inside suspensionTravel's own (0,1] range), ratio
  * 2.0 is numerically unstable. This reproduces the reviewer's own exact numbers as independent
  * confirmation, and demonstrates that {@code Overrides.Tuning}'s new travel-aware cross-field
  * check (see its own {@code maxStableSuspensionDampingRatio} doc) accepts a genuinely stable
  * ratio at that same smaller travel instead.
  */
 @Test void aSmallerValidSuspensionTravelLowersTheActuallyStableRatioBelowTheFlatTwoCeiling() {
  double mass=1500, gravity=11, loadShare=0.25, smallerTravel=0.2;
  double equilibrium=smallerTravel*.5;
  double springAtSmallerTravel=PhysicsBridge.suspensionSpringForShare(mass, loadShare, smallerTravel, gravity);
  int ticks=6000;

  // Reproduces the reviewer's own exact reported numbers: ratio 2.0 at travel 0.2 never
  // converges - a sustained ~0.0807-0.0991 late-window chatter against a 0.1 target.
  double damperAtFlatCeiling=PhysicsBridge.suspensionDamperForShare(mass, loadShare, springAtSmallerTravel, 2.0);
  double[] unstableTrace=settleTrace(mass, loadShare, gravity, springAtSmallerTravel, damperAtFlatCeiling, ticks);
  double unstableLateMin=Double.MAX_VALUE, unstableLateMax=-Double.MAX_VALUE;
  for (int t=ticks-500;t<ticks;t++) { unstableLateMin=Math.min(unstableLateMin, unstableTrace[t]); unstableLateMax=Math.max(unstableLateMax, unstableTrace[t]); }
  assertTrue(unstableLateMax-unstableLateMin > 0.01,
    "ratio 2.0 at travel 0.2 must show sustained, non-decaying late-window chatter, not mere early transient - observed spread "+(unstableLateMax-unstableLateMin));

  // A ratio actually within Overrides.Tuning's own newly-accepted cross-field bound for this
  // travel (confirmed stable at 1.6 < the ~1.776 computed bound) converges cleanly instead.
  double stableRatio=1.6;
  double damperAtStableRatio=PhysicsBridge.suspensionDamperForShare(mass, loadShare, springAtSmallerTravel, stableRatio);
  double[] stableTrace=settleTrace(mass, loadShare, gravity, springAtSmallerTravel, damperAtStableRatio, ticks);
  double stableLateMin=Double.MAX_VALUE, stableLateMax=-Double.MAX_VALUE;
  for (int t=ticks-500;t<ticks;t++) { stableLateMin=Math.min(stableLateMin, stableTrace[t]); stableLateMax=Math.max(stableLateMax, stableTrace[t]); }
  assertEquals(equilibrium, stableLateMin, 1e-6, "ratio "+stableRatio+" at travel "+smallerTravel+" (within Overrides' own accepted cross-field bound) must converge exactly, with no late-window drift");
  assertEquals(equilibrium, stableLateMax, 1e-6, "ratio "+stableRatio+" at travel "+smallerTravel+" (within Overrides' own accepted cross-field bound) must converge exactly, with no late-window drift");
 }

 @Test void sharedConfiguredRatioAppliesConsistentlyAcrossAnAsymmetricUnequalLoadAxle() {
  // MINECRAFT-506/519's own asymmetric-axle premise: a COM offset toward one side gives the two
  // wheels DIFFERENT loadShares (and therefore different calibrated spring/damper rates), but a
  // single operator-configured suspensionDampingRatio is still the dimensionless MULTIPLIER
  // shared by both - this must settle both sides to critical-damping-like (no overshoot)
  // behavior at ratio 1.0 despite their different absolute rates, not just the symmetric case.
  double mass=1500, gravity=11;
  double heavierShare=0.35, lighterShare=0.15; // deliberately unequal, unlike the 0.25/0.25 symmetric case
  double equilibrium=TRAVEL*.5, overshootTolerance=1e-4;
  double sharedRatio=1.0;
  for (double share : new double[]{heavierShare, lighterShare}) {
   double spring=PhysicsBridge.suspensionSpringForShare(mass, share, TRAVEL, gravity);
   double damper=PhysicsBridge.suspensionDamperForShare(mass, share, spring, sharedRatio);
   double[] trace=settleTrace(mass, share, gravity, spring, damper, 400);
   double max=java.util.Arrays.stream(trace).max().getAsDouble();
   assertTrue(max <= equilibrium + overshootTolerance,
     "critical damping ratio 1.0 must not overshoot for loadShare="+share+" - max observed "+max);
   assertEquals(equilibrium, trace[trace.length-1], 1e-3,
     "both the heavier and lighter wheel of an asymmetric axle must still settle at the same travel*.5 equilibrium, only the approach dynamics differ with loadShare");
  }
 }

 /**
  * MINECRAFT-523's explicit "does not silently disappear" requirement: whichever basis actually
  * computes the damper - the shared formula, or the opt-in experimental effective-mass candidate
  * - both must honor the SAME caller-supplied ratio, and a degenerate/guard-rejected contact
  * (the structural equivalent of "this tick's contact geometry could not be used") must fall back
  * to exactly the damper the caller already computed with that ratio, never silently substitute
  * a different one.
  */
 @Test void effectiveMassDamperCandidateHonorsTheSameConfiguredRatioAsTheSharedFormulaAndNeverSubstitutesItOnFallback() {
  double mass=350, spring=11430.0;
  Vector3d up=new Vector3d(0,1,0);
  Quaterniond identity=new Quaterniond();
  Vector3d inertia=new Vector3d(1200,1300,900);
  double configuredRatio=1.8;
  double fallbackDamperAtConfiguredRatio=PhysicsBridge.suspensionDamper(mass, 4, spring, configuredRatio);

  // A real, non-degenerate off-center contact: the candidate actually computes its own damper,
  // using the configured ratio - not .7.
  var realContact=PhysicsBridge.effectiveMassDamper(spring, mass, new Vector3d(0.3,0.1,0.5), up, identity, inertia, configuredRatio, fallbackDamperAtConfiguredRatio);
  assertEquals(2*Math.sqrt(spring*realContact.effectiveMass())*configuredRatio, realContact.damper(), 1e-6,
    "the candidate's own formula must use the caller-supplied ratio, not a second hardcoded 0.7");

  // A degenerate/guard-rejected contact (non-finite spring here stands in for any of the
  // documented guard failures) - must fall back to EXACTLY the caller-supplied fallback, which
  // was itself computed with the configured ratio; the candidate must never recompute its own
  // fallback at a different (e.g. hardcoded 0.7) ratio.
  var degenerateContact=PhysicsBridge.effectiveMassDamper(Double.NaN, mass, new Vector3d(0,0,0), up, identity, inertia, configuredRatio, fallbackDamperAtConfiguredRatio);
  assertEquals(fallbackDamperAtConfiguredRatio, degenerateContact.damper(),
    "a guard-rejected contact must fall back to exactly the caller's own already-ratio-correct fallback damper");
 }
}
