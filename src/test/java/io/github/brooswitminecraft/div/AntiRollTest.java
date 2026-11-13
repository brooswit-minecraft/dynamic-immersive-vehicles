package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-519: pure-math coverage for {@link AntiRoll} - the geometric left/right axle pairing
 * ({@link AntiRoll#pairPartners}) and the bounded, baseline-relative RESTORING transfer
 * ({@link AntiRoll#transfer}). Covers the ticket's own acceptance shape plus the two review
 * corrections (comments 53750/53752): conservation, sign (amplifying, not equalizing, the
 * roll-induced differential), the neutral-asymmetric-baseline equilibrium case, bounds,
 * zero/default equivalence, and unsupported geometry.
 */
class AntiRollTest {
 // ---- pairPartners: geometric axle/side identification (unchanged by the review fix) ----
 @Test void symmetricFourWheelLayoutPairsEachAxleLeftToRight() {
  double[] x = {1.0, -1.0, 1.0, -1.0};
  double[] z = {1.5, 1.5, -1.5, -1.5};
  int[] partner = AntiRoll.pairPartners(x, z);
  assertArrayEquals(new int[]{1, 0, 3, 2}, partner);
 }
 @Test void asymmetricTrackWidthStillPairsByZAndOppositeSignX() {
  double[] x = {1.2, -0.8};
  double[] z = {1.5, 1.5};
  int[] partner = AntiRoll.pairPartners(x, z);
  assertArrayEquals(new int[]{1, 0}, partner);
 }
 @Test void singleWheelAxleHasNoPartner() {
  assertArrayEquals(new int[]{-1}, AntiRoll.pairPartners(new double[]{0.0}, new double[]{2.0}));
 }
 @Test void threeWheelsSharingAnAxleIsUnsupportedAndLeftUncoupled() {
  double[] x = {-1.0, 0.0, 1.0};
  double[] z = {1.0, 1.0, 1.0};
  assertArrayEquals(new int[]{-1, -1, -1}, AntiRoll.pairPartners(x, z));
 }
 @Test void centeredSkidSteerWheelIsNeverPaired() {
  double[] x = {0.0, 0.0};
  double[] z = {1.0, 1.0};
  assertArrayEquals(new int[]{-1, -1}, AntiRoll.pairPartners(x, z));
 }
 @Test void twoWheelsOnTheSameSideAreNeverPaired() {
  double[] x = {1.0, 2.0};
  double[] z = {1.0, 1.0};
  assertArrayEquals(new int[]{-1, -1}, AntiRoll.pairPartners(x, z));
 }
 @Test void sixWheelTruckPairsEachOfThreeAxlesIndependently() {
  double[] x = {1.0, -1.0, 1.0, -1.0, 1.0, -1.0};
  double[] z = {2.0, 2.0, 0.0, 0.0, -2.0, -2.0};
  int[] partner = AntiRoll.pairPartners(x, z);
  assertArrayEquals(new int[]{1, 0, 3, 2, 5, 4}, partner);
 }
 // ---- transfer: bounded, baseline-relative, conserved, RESTORING (amplifying) coupling ----
 @Test void zeroStiffnessIsExactZeroDefaultEquivalence() {
  assertEquals(0.0, AntiRoll.transfer(450.0, 400.0, 650.0, 600.0, 0.0), 0.0);
  assertEquals(0.0, AntiRoll.transfer(450.0, 400.0, 650.0, 600.0, Double.NaN), 0.0, "non-finite stiffness must behave exactly as off, never propagate NaN");
  assertEquals(0.0, AntiRoll.transfer(450.0, 400.0, 650.0, 600.0, -0.5), 0.0, "negative stiffness must also behave as off");
 }
 @Test void neutralAsymmetricStaticBaselineGetsExactlyZeroTransferRegardlessOfStiffness() {
  // Review comment 53752's exact reproduction: contacts at x=+1.2/-0.8 with geometrically
  // correct, UNEQUAL M506 static loads (400N/600N) and NO actual roll (each side sitting
  // exactly at its own baseline) must see ZERO transfer no matter how different those two
  // baselines are from each other - equalizing them would manufacture an unsolicited roll
  // torque that was never really there.
  for (double stiffness : new double[]{0.1, 0.5, 1.0, 5.0}) {
   assertEquals(0.0, AntiRoll.transfer(400.0, 400.0, 600.0, 600.0, stiffness), 1e-12, "stiffness="+stiffness);
  }
 }
 @Test void transferAmplifiesTheRollInducedDifferentialBeyondTheUncoupledBaseline() {
  // A genuine roll disturbance: both sides deviate from their own (equal, here) baseline by
  // equal and opposite amounts. The coupled differential must end up STRICTLY LARGER than the
  // uncoupled one - a restoring bar, not an equalizing one (the defect comment 53750 found).
  double baselineA = 500.0, baselineB = 500.0;
  double normalA = 650.0, normalB = 350.0; // +150/-150 roll-induced deviation
  double uncoupledDiff = normalA - normalB;
  for (double stiffness : new double[]{0.2, 0.5, 1.0}) {
   double t = AntiRoll.transfer(normalA, baselineA, normalB, baselineB, stiffness);
   double coupledDiff = (normalA + t) - (normalB - t);
   assertTrue(Math.abs(coupledDiff) > Math.abs(uncoupledDiff), "stiffness="+stiffness+": coupled diff "+coupledDiff+" must exceed uncoupled diff "+uncoupledDiff);
   assertTrue(t > 0, "stiffness="+stiffness+": transfer must flow TOWARD the already-more-loaded side A, not away from it");
  }
 }
 @Test void asymmetricBaselinePlusRollOnlyAmplifiesTheRollComponentNotTheStaticSplit() {
  // Same worked example as the neutral case, but now WITH a roll disturbance on top of the
  // legitimate 400/600 static split: both sides deviate from their OWN baseline by +/-50. The
  // RAW normal difference conflates the (unchanged) static split with the (amplified) roll
  // component, so it is the ISOLATED deviation-from-baseline differential - not the raw
  // normal difference - that is guaranteed to grow; that is exactly what AntiRoll is meant to
  // amplify, deliberately leaving the static split itself alone (see
  // neutralAsymmetricStaticBaselineGetsExactlyZeroTransferRegardlessOfStiffness above).
  double baselineA = 400.0, baselineB = 600.0;
  double normalA = 450.0, normalB = 550.0; // deviation +50 / -50 relative to each side's own baseline
  double uncoupledDeviationDiff = (normalA - baselineA) - (normalB - baselineB); // 50 - (-50) = 100
  for (double stiffness : new double[]{0.2, 0.5, 1.0}) {
   double t = AntiRoll.transfer(normalA, baselineA, normalB, baselineB, stiffness);
   double newA = normalA + t, newB = normalB - t;
   double coupledDeviationDiff = (newA - baselineA) - (newB - baselineB);
   assertTrue(Math.abs(coupledDeviationDiff) > Math.abs(uncoupledDeviationDiff), "stiffness="+stiffness+": the roll-induced deviation differential must be amplified even on top of an unequal static baseline");
  }
 }
 @Test void totalIsExactlyConservedAcrossEveryStiffnessAndBaselineCombination() {
  java.util.Random rng = new java.util.Random(519);
  for (int trial = 0; trial < 500; trial++) {
   double normalA = rng.nextDouble() * 2000;
   double normalB = rng.nextDouble() * 2000;
   double baselineA = rng.nextDouble() * 2000;
   double baselineB = rng.nextDouble() * 2000;
   double stiffness = rng.nextDouble() * 3;
   double t = AntiRoll.transfer(normalA, baselineA, normalB, baselineB, stiffness);
   double newA = normalA + t, newB = normalB - t;
   assertEquals(normalA + normalB, newA + newB, 1e-6, "trial "+trial+": pair total must always be exactly conserved");
   assertTrue(newA >= -1e-9 && newB >= -1e-9, "trial "+trial+": neither side may go negative (newA="+newA+", newB="+newB+")");
  }
 }
 @Test void transferNeverDrivesTheDonorSideBelowZero() {
  // A huge stiffness must still be bounded by the actual donor side's own current load - never
  // an independent per-side clamp, just the one shared transfer amount capped against reality.
  double t = AntiRoll.transfer(1000.0, 0.0, 10.0, 0.0, 1000.0);
  double newB = 10.0 - t;
  assertTrue(newB >= -1e-9, "side B (the donor here) must never be driven negative, got "+newB);
 }
 @Test void swappingSidesProducesTheExactNegatedTransfer() {
  // transfer(A,B) and transfer(B,A) must be exact negatives - the same physical coupling,
  // described from either side.
  double t1 = AntiRoll.transfer(620.0, 500.0, 480.0, 500.0, 0.6);
  double t2 = AntiRoll.transfer(480.0, 500.0, 620.0, 500.0, 0.6);
  assertEquals(t1, -t2, 1e-9);
 }
}
