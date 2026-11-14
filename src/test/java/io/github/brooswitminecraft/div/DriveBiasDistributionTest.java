package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-524: {@link DriveBiasDistribution#shares} is the pure-math core of the configurable
 * front/rear drive-force split - covers the ticket's own named acceptance shape: conservation
 * (shares always sum to exactly 1.0, so the real drive-force budget is preserved), front/rear
 * extremes (bias 0.0/1.0), odd/asymmetric wheel counts, and no change at all when the bias is
 * unset (byte-identical to the pre-ticket even split). Deliberately mirrors
 * {@link BrakeBiasDistributionTest} field-for-field, since {@link DriveBiasDistribution} itself
 * mirrors {@link BrakeBiasDistribution}.
 */
class DriveBiasDistributionTest {
 private static double sum(double[] a) { double s=0; for (double x:a) s+=x; return s; }
 @Test void zeroWheelsReturnsEmpty() {
  assertEquals(0, DriveBiasDistribution.shares(new double[0], 0.7).length);
  assertEquals(0, DriveBiasDistribution.shares(new double[0], null).length);
 }
 @Test void unsetBiasReproducesThePreTicketEvenSplitForAnyWheelCount() {
  for (int n = 1; n <= 7; n++) {
   double[] z = new double[n];
   for (int i = 0; i < n; i++) z[i] = i * 0.37 - 1.1; // arbitrary, including asymmetric spacing
   double[] shares = DriveBiasDistribution.shares(z, null);
   assertEquals(1.0, sum(shares), 1e-9);
   for (double s : shares) assertEquals(1.0 / n, s, 1e-12, "unset bias must be the exact even split, not merely close to it");
  }
 }
 @Test void oneWheelIsUnaffectedByAnyBiasValue() {
  // A single wheel has no "other axle" to bias against - must fall back to the whole budget,
  // regardless of the configured bias value.
  for (double bias : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
   double[] shares = DriveBiasDistribution.shares(new double[]{2.3}, bias);
   assertArrayEquals(new double[]{1.0}, shares, 1e-12);
  }
 }
 @Test void symmetricFourWheelNeutralBiasReducesToTheEvenSplit() {
  double[] z = {1.5, 1.5, -1.5, -1.5}; // front axle (indices 0,1), rear axle (2,3)
  double[] shares = DriveBiasDistribution.shares(z, 0.5);
  assertEquals(1.0, sum(shares), 1e-9);
  for (double s : shares) assertEquals(0.25, s, 1e-9, "neutral (0.5) bias on a symmetric axle layout must match today's even split");
 }
 @Test void fullFrontBiasSendsTheEntireBudgetToTheFrontAxleAndNoneToTheRear() {
  double[] z = {1.5, 1.5, -1.5, -1.5};
  double[] shares = DriveBiasDistribution.shares(z, 1.0);
  assertEquals(1.0, sum(shares));
  assertEquals(0.5, shares[0], 1e-12); assertEquals(0.5, shares[1], 1e-12);
  assertEquals(0.0, shares[2], 1e-12); assertEquals(0.0, shares[3], 1e-12);
 }
 @Test void fullRearBiasSendsTheEntireBudgetToTheRearAxleAndNoneToTheFront() {
  double[] z = {1.5, 1.5, -1.5, -1.5};
  double[] shares = DriveBiasDistribution.shares(z, 0.0);
  assertEquals(1.0, sum(shares));
  assertEquals(0.0, shares[0], 1e-12); assertEquals(0.0, shares[1], 1e-12);
  assertEquals(0.5, shares[2], 1e-12); assertEquals(0.5, shares[3], 1e-12);
 }
 @Test void asymmetricAxleWheelCountsStillConserveTotalBudget() {
  // One front wheel (a trike-like front end), two rear wheels.
  double[] z = {2.0, -1.0, -1.0};
  double[] shares = DriveBiasDistribution.shares(z, 0.7);
  assertEquals(1.0, sum(shares), 1e-9);
  assertEquals(0.7, shares[0], 1e-9, "the lone front wheel gets the WHOLE front share");
  assertEquals(0.15, shares[1], 1e-9); assertEquals(0.15, shares[2], 1e-9);
 }
 @Test void reversedLayoutStillPicksTheGeometricallyForwardAxleAsFront() {
  // The physically-forward wheels happen to sit at NEGATIVE z here - geometry, not a sign
  // convention or steering flag, must still correctly identify them as "front" (max z).
  double[] z = {-1.5, -1.5, 1.5, 1.5};
  double[] shares = DriveBiasDistribution.shares(z, 1.0);
  assertEquals(0.0, shares[0], 1e-12); assertEquals(0.0, shares[1], 1e-12);
  assertEquals(0.5, shares[2], 1e-12); assertEquals(0.5, shares[3], 1e-12);
 }
 @Test void multiAxleLayoutLumpsEveryNonFrontRowIntoOneRearGroup() {
  // Three axles (front/mid/rear) - mid+rear together form "rear" for this split.
  double[] z = {2.0, 2.0, 0.0, 0.0, -2.0, -2.0};
  double[] shares = DriveBiasDistribution.shares(z, 0.8);
  assertEquals(1.0, sum(shares), 1e-9);
  assertEquals(0.4, shares[0], 1e-9); assertEquals(0.4, shares[1], 1e-9); // front axle, 0.8 split across 2 wheels
  for (int i = 2; i < 6; i++) assertEquals(0.05, shares[i], 1e-9); // remaining 0.2 split across the other 4 wheels
 }
 @Test void allWheelsAtTheSameZFallsBackToTheEvenSplitRatherThanAFakeAxleSplit() {
  double[] z = {0.3, 0.3, 0.3};
  double[] shares = DriveBiasDistribution.shares(z, 1.0);
  for (double s : shares) assertEquals(1.0 / 3.0, s, 1e-9, "no distinguishable rear axle - must fall back to the even split, not pretend everything is 'front'");
 }
 @Test void everyShareIsAlwaysFiniteNonNegativeAndSumsToOneAcrossManyRandomLayoutsAndBiases() {
  java.util.Random rng = new java.util.Random(52449);
  for (int trial = 0; trial < 500; trial++) {
   int n = 1 + rng.nextInt(8);
   double[] z = new double[n];
   for (int i = 0; i < n; i++) z[i] = rng.nextDouble() * 4 - 2;
   Double bias = rng.nextBoolean() ? null : rng.nextDouble();
   double[] shares = DriveBiasDistribution.shares(z, bias);
   assertEquals(n, shares.length);
   double s = 0;
   for (double share : shares) {
    assertTrue(Double.isFinite(share) && share >= 0, "trial " + trial + ": share must be finite and non-negative, got " + share);
    s += share;
   }
   assertEquals(1.0, s, 1e-9, "trial " + trial + ": shares must sum to exactly 1.0");
  }
 }
}
