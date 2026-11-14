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

 @Test void defaultRatioOfPoint7MatchesTheDeprecatedHardcodedOverloadsByteIdentically() {
  double mass=1500, spring=35750.0, loadShare=0.25;
  assertEquals(PhysicsBridge.suspensionDamper(mass, 4, spring), PhysicsBridge.suspensionDamper(mass, 4, spring, 0.7),
    "the old 3-arg overload must be byte-identical to the new one at the exact calibrated 0.7 default");
  assertEquals(PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring), PhysicsBridge.suspensionDamperForShare(mass, loadShare, spring, 0.7),
    "the old 3-arg overload must be byte-identical to the new one at the exact calibrated 0.7 default");
  Vector3d point=new Vector3d(0,0,0), up=new Vector3d(0,1,0);
  Quaterniond identity=new Quaterniond();
  Vector3d inertia=new Vector3d(100,100,100);
  var legacy = PhysicsBridge.effectiveMassDamper(spring, mass, point, up, identity, inertia, 123.0);
  var explicit = PhysicsBridge.effectiveMassDamper(spring, mass, point, up, identity, inertia, 0.7, 123.0);
  assertEquals(legacy.damper(), explicit.damper(), "the old 7-arg overload must be byte-identical to the new one at the exact calibrated 0.7 default");
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

 @Test void changedSettlingResponseAnUnderdampedRatioOvershootsEquilibriumWhileAnOverdampedRatioDoesNot() {
  double mass=1500, gravity=11;
  double spring=PhysicsBridge.suspensionSpringForShare(mass, 0.25, TRAVEL, gravity);
  double equilibrium=TRAVEL*.5;
  double overshootTolerance=1e-4;

  double underdampedDamper=PhysicsBridge.suspensionDamperForShare(mass, 0.25, spring, 0.15);
  double[] underdampedTrace=settleTrace(mass, 0.25, gravity, spring, underdampedDamper, 400);
  double underdampedMax=java.util.Arrays.stream(underdampedTrace).max().getAsDouble();
  assertTrue(underdampedMax > equilibrium + overshootTolerance,
    "a strongly underdamped ratio (0.15) must visibly overshoot the "+equilibrium+" equilibrium - max observed "+underdampedMax);

  double overdampedDamper=PhysicsBridge.suspensionDamperForShare(mass, 0.25, spring, 2.5);
  double[] overdampedTrace=settleTrace(mass, 0.25, gravity, spring, overdampedDamper, 400);
  double overdampedMax=java.util.Arrays.stream(overdampedTrace).max().getAsDouble();
  assertTrue(overdampedMax <= equilibrium + overshootTolerance,
    "a strongly overdamped ratio (2.5) must settle toward "+equilibrium+" without overshooting - max observed "+overdampedMax);
  // TireForces.suspension's own one-sided (never-negative) force law means a wheel resting
  // almost exactly at equilibrium can still show small, bounded long-run chatter as the clamp
  // repeatedly re-engages (a real, pre-existing property of that one-sided law under explicit
  // integration, not something this ticket's damping ratio change introduces or needs to
  // eliminate) - so this checks only that the approach actually GETS close to equilibrium at
  // some point within a generous early window, not that every later tick stays exactly there.
  double closestApproach=java.util.Arrays.stream(overdampedTrace, 0, 200).map(c -> Math.abs(c-equilibrium)).min().getAsDouble();
  assertTrue(closestApproach < 0.02,
    "an overdamped wheel must actually approach close to "+equilibrium+" within the first 200 ticks - closest approach was off by "+closestApproach);
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
