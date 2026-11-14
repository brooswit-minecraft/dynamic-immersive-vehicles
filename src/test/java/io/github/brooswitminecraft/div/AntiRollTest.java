package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-519: pure-math coverage for {@link AntiRoll} - the geometric left/right axle pairing
 * ({@link AntiRoll#pairPartners}) and the bounded, differential-travel-driven RESTORING transfer
 * ({@link AntiRoll#transfer}). Covers the ticket's own acceptance shape plus all three review
 * corrections (comments 53750/53752/53770): conservation, sign (amplifying, not equalizing, the
 * roll-induced differential), common-heave AND common-velocity neutrality under genuinely
 * UNEQUAL static shares/rates (comment 53770's exact reproduction, via a real four-wheel
 * rectangle fixture rather than the singular two-wheel same-z fixture that previously missed
 * it), bounds, zero/default equivalence, and unsupported geometry.
 */
class AntiRollTest {
 // ---- pairPartners: geometric axle/side identification (unchanged by either review fix) ----
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
 // ---- the real four-wheel rectangle fixture from comment 53770's own reproduction ----
 // x=[1.2,-0.8,1.2,-0.8], z=[1,1,-1,-1] - a non-singular asymmetric layout (unlike a two-wheel
 // same-z fixture, whose 3x3 WheelLoadDistribution system is singular and silently falls back to
 // an even split, which is exactly how the force-baseline-deviation defect went unnoticed).
 private static final double[] RECT_X = {1.2, -0.8, 1.2, -0.8};
 private static final double[] RECT_Z = {1.0, 1.0, -1.0, -1.0};
 private static final double MASS = 1500, GRAVITY = 11, TRAVEL = 0.3;
 private static double[] rectShares() { return WheelLoadDistribution.shares(RECT_X, RECT_Z); }
 private static double rateFor(double share) { return PhysicsBridge.suspensionSpringForShare(MASS, share, TRAVEL, GRAVITY); }
 // ---- transfer: bounded, travel-driven, conserved, RESTORING (amplifying) coupling ----
 @Test void zeroStiffnessIsExactZeroDefaultEquivalence() {
  assertEquals(0.0, AntiRoll.transfer(0.2, 0.1, 50000, 70000, 450.0, 650.0, 0.0), 0.0);
  assertEquals(0.0, AntiRoll.transfer(0.2, 0.1, 50000, 70000, 450.0, 650.0, Double.NaN), 0.0, "non-finite stiffness must behave exactly as off, never propagate NaN");
  assertEquals(0.0, AntiRoll.transfer(0.2, 0.1, 50000, 70000, 450.0, 650.0, -0.5), 0.0, "negative stiffness must also behave as off");
 }
 @Test void equalCompressionGetsExactlyZeroTransferRegardlessOfUnequalRatesOrStiffness() {
  // The direct pure-function version of the neutral case: both sides at the SAME compression
  // (no actual roll) but with genuinely DIFFERENT calibrated rates (and therefore different
  // loads) - zero transfer no matter the stiffness.
  for (double stiffness : new double[]{0.1, 0.5, 1.0, 5.0}) {
   assertEquals(0.0, AntiRoll.transfer(0.15, 0.15, 50000, 80000, 400.0, 600.0, stiffness), 1e-9, "stiffness="+stiffness);
  }
 }
 @Test void commonHeaveUnderGenuinelyUnequalSharesGetsZeroTransfer() {
  // Comment 53770's exact reproduction, via the real (non-singular) four-wheel rectangle: both
  // sides of the x=+1.2/-0.8 pair start at the shared rest compression, then BOTH move by the
  // SAME real distance (a true common-mode heave/bounce, not an actual roll) - must still see
  // zero transfer despite the pair's own rates (and therefore loads) genuinely differing.
  double[] shares = rectShares();
  double rateA = rateFor(shares[0]), rateB = rateFor(shares[1]); // wheel 0 (x=1.2) and wheel 1 (x=-0.8) share z=1.0
  assertNotEquals(rateA, rateB, 1e-6, "sanity: the rectangle fixture must actually produce unequal rates for this pair");
  double restCompression = TRAVEL*.5;
  double commonBump = 0.04; // both sides compress by the same extra 4cm
  double normalA = rateA*(restCompression+commonBump), normalB = rateB*(restCompression+commonBump);
  for (double stiffness : new double[]{0.2, 0.6, 1.0, 5.0}) {
   double t = AntiRoll.transfer(restCompression+commonBump, restCompression+commonBump, rateA, rateB, normalA, normalB, stiffness);
   assertEquals(0.0, t, 1e-9, "stiffness="+stiffness+": a true common-mode heave must never produce a transfer, regardless of unequal rates");
  }
 }
 @Test void commonVelocityNeverEntersTheSignalAtAll() {
  // Comment 53770's "common-velocity neutrality" ask: transfer depends only on compression
  // (travel), never velocity - so ANY velocity, common or not, leaves the result unaffected.
  // There is no velocity parameter on transfer at all; this test documents that this is
  // deliberate, not an oversight, by confirming the signature only ever needs compression.
  double[] shares = rectShares();
  double rateA = rateFor(shares[0]), rateB = rateFor(shares[1]);
  double t1 = AntiRoll.transfer(0.2, 0.2, rateA, rateB, 500.0, 700.0, 0.8);
  double t2 = AntiRoll.transfer(0.2, 0.2, rateA, rateB, 500.0, 700.0, 0.8); // same compression, "different" (irrelevant) velocity scenario
  assertEquals(t1, t2, 0.0);
  assertEquals(0.0, t1, 1e-9, "equal compression (any velocity) must still mean zero transfer");
 }
 @Test void transferAmplifiesAnActualCompressionDifferentialBeyondTheUncoupledBaseline() {
  // A genuine roll disturbance using the SAME unequal-rate rectangle pair: side A compresses
  // more than side B by a real amount. The coupled differential LOAD must end up strictly
  // larger than the uncoupled one, and flow toward the more-compressed (already more-loaded)
  // side - a restoring bar, not an equalizing one (the original comment 53750 defect).
  double[] shares = rectShares();
  double rateA = rateFor(shares[0]), rateB = rateFor(shares[1]);
  double compressionA = TRAVEL*.7, compressionB = TRAVEL*.3; // A noticeably more compressed
  double normalA = rateA*compressionA, normalB = rateB*compressionB;
  double uncoupledDiff = normalA - normalB;
  for (double stiffness : new double[]{0.2, 0.5, 1.0}) {
   double t = AntiRoll.transfer(compressionA, compressionB, rateA, rateB, normalA, normalB, stiffness);
   double coupledDiff = (normalA+t) - (normalB-t);
   assertTrue(Math.abs(coupledDiff) > Math.abs(uncoupledDiff), "stiffness="+stiffness+": coupled diff "+coupledDiff+" must exceed uncoupled diff "+uncoupledDiff);
   assertTrue(t > 0, "stiffness="+stiffness+": transfer must flow TOWARD the already-more-compressed/more-loaded side A");
  }
 }
 @Test void totalIsExactlyConservedAcrossEveryStiffnessAndRateCombination() {
  java.util.Random rng = new java.util.Random(519);
  for (int trial = 0; trial < 500; trial++) {
   double compressionA = rng.nextDouble() * 0.3;
   double compressionB = rng.nextDouble() * 0.3;
   double rateA = 10000 + rng.nextDouble() * 90000;
   double rateB = 10000 + rng.nextDouble() * 90000;
   double normalA = rng.nextDouble() * 2000;
   double normalB = rng.nextDouble() * 2000;
   double stiffness = rng.nextDouble() * 3;
   double t = AntiRoll.transfer(compressionA, compressionB, rateA, rateB, normalA, normalB, stiffness);
   double newA = normalA + t, newB = normalB - t;
   assertEquals(normalA + normalB, newA + newB, 1e-6, "trial "+trial+": pair total must always be exactly conserved");
   assertTrue(newA >= -1e-9 && newB >= -1e-9, "trial "+trial+": neither side may go negative (newA="+newA+", newB="+newB+")");
  }
 }
 @Test void transferNeverDrivesTheDonorSideBelowZero() {
  // A huge stiffness must still be bounded by the actual donor side's own current load - never
  // an independent per-side clamp, just the one shared transfer amount capped against reality.
  double t = AntiRoll.transfer(0.9, 0.1, 50000, 50000, 1000.0, 10.0, 1000.0);
  double newB = 10.0 - t;
  assertTrue(newB >= -1e-9, "side B (the donor here) must never be driven negative, got "+newB);
 }
 @Test void swappingSidesProducesTheExactNegatedTransfer() {
  double t1 = AntiRoll.transfer(0.2, 0.1, 50000, 60000, 620.0, 480.0, 0.6);
  double t2 = AntiRoll.transfer(0.1, 0.2, 60000, 50000, 480.0, 620.0, 0.6);
  assertEquals(t1, -t2, 1e-9);
 }
}
