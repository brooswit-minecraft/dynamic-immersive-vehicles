package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-519: pure-math coverage for {@link AntiRoll} - the geometric left/right axle pairing
 * ({@link AntiRoll#pairPartners}) and the bounded 0-1 load-redistribution blend
 * ({@link AntiRoll#blendedNormalA}). Covers exactly the ticket's own acceptance shape:
 * conservation, sign, zero/default equivalence, and asymmetric/unsupported geometry.
 */
class AntiRollTest {
 // ---- pairPartners: geometric axle/side identification ----
 @Test void symmetricFourWheelLayoutPairsEachAxleLeftToRight() {
  double[] x = {1.0, -1.0, 1.0, -1.0};
  double[] z = {1.5, 1.5, -1.5, -1.5};
  int[] partner = AntiRoll.pairPartners(x, z);
  assertArrayEquals(new int[]{1, 0, 3, 2}, partner);
 }
 @Test void asymmetricTrackWidthStillPairsByZAndOppositeSignX() {
  // Unequal track width per side (x magnitudes differ) - pairing depends only on sharing z and
  // opposite sign, never on magnitude symmetry.
  double[] x = {1.2, -0.8};
  double[] z = {1.5, 1.5};
  int[] partner = AntiRoll.pairPartners(x, z);
  assertArrayEquals(new int[]{1, 0}, partner);
 }
 @Test void singleWheelAxleHasNoPartner() {
  double[] x = {0.0};
  double[] z = {2.0};
  assertArrayEquals(new int[]{-1}, AntiRoll.pairPartners(x, z));
 }
 @Test void threeWheelsSharingAnAxleIsUnsupportedAndLeftUncoupled() {
  double[] x = {-1.0, 0.0, 1.0};
  double[] z = {1.0, 1.0, 1.0};
  assertArrayEquals(new int[]{-1, -1, -1}, AntiRoll.pairPartners(x, z));
 }
 @Test void centeredSkidSteerWheelIsNeverPaired() {
  // x==0 (a centered rail/track wheel) has no "side" to pair against, even if another wheel
  // shares its z exactly.
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
 // ---- blendedNormalA: bounded, conserved redistribution ----
 @Test void zeroStiffnessIsExactZeroDefaultEquivalence() {
  assertEquals(500.0, AntiRoll.blendedNormalA(500.0, 1200.0, 0.0), 0.0);
  assertEquals(500.0, AntiRoll.blendedNormalA(500.0, 1200.0, Double.NaN), 0.0, "non-finite stiffness must behave exactly as off, never propagate NaN");
 }
 @Test void fullStiffnessFullyEqualizesThePair() {
  double a = AntiRoll.blendedNormalA(500.0, 1200.0, 1.0);
  double b = AntiRoll.blendedNormalA(1200.0, 500.0, 1.0);
  assertEquals(850.0, a, 1e-9);
  assertEquals(850.0, b, 1e-9);
 }
 @Test void partialStiffnessConservesTheExactPairSum() {
  double normalA = 300.0, normalB = 1100.0;
  for (double stiffness : new double[]{0.1, 0.25, 0.5, 0.75, 0.9}) {
   double a = AntiRoll.blendedNormalA(normalA, normalB, stiffness);
   double b = AntiRoll.blendedNormalA(normalB, normalA, stiffness);
   assertEquals(normalA + normalB, a + b, 1e-9, "stiffness="+stiffness+" must conserve the pair's total load exactly");
  }
 }
 @Test void transferDirectionMovesTheMoreLoadedSideTowardTheLessLoadedSide() {
  // Sign check: the more-loaded side must end up with LESS, the less-loaded side with MORE -
  // never the reverse (which would amplify imbalance instead of coupling it).
  double a = AntiRoll.blendedNormalA(1000.0, 200.0, 0.4);
  double b = AntiRoll.blendedNormalA(200.0, 1000.0, 0.4);
  assertTrue(a < 1000.0, "the more-loaded side must lose load toward its partner");
  assertTrue(b > 200.0, "the less-loaded side must gain load from its partner");
 }
 @Test void resultIsNeverNegativeAndNeverExceedsTheOriginalPairBounds() {
  java.util.Random rng = new java.util.Random(519);
  for (int trial = 0; trial < 500; trial++) {
   double normalA = rng.nextDouble() * 5000;
   double normalB = rng.nextDouble() * 5000;
   double stiffness = rng.nextDouble();
   double a = AntiRoll.blendedNormalA(normalA, normalB, stiffness);
   double b = AntiRoll.blendedNormalA(normalB, normalA, stiffness);
   double lo = Math.min(normalA, normalB), hi = Math.max(normalA, normalB);
   assertTrue(a >= lo - 1e-9 && a <= hi + 1e-9, "trial "+trial+": result must stay within the pair's own original bounds");
   assertTrue(b >= lo - 1e-9 && b <= hi + 1e-9);
   assertTrue(a >= 0 && b >= 0, "trial "+trial+": blended normal must never go negative");
   assertEquals(normalA + normalB, a + b, 1e-6, "trial "+trial+": sum must always be conserved exactly");
  }
 }
 @Test void outOfRangeStiffnessIsClampedNotExtrapolated() {
  double atOne = AntiRoll.blendedNormalA(500.0, 1200.0, 1.0);
  double beyondOne = AntiRoll.blendedNormalA(500.0, 1200.0, 5.0);
  assertEquals(atOne, beyondOne, 1e-9, "stiffness above 1.0 must clamp to the fully-rigid (1.0) result, never extrapolate past it");
 }
 @Test void negativeStiffnessBehavesAsOff() {
  assertEquals(500.0, AntiRoll.blendedNormalA(500.0, 1200.0, -0.5), 0.0);
 }
}
