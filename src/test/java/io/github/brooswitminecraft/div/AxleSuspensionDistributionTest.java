package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-539: {@link AxleSuspensionDistribution} is the pure-math core of configurable
 * per-axle spring/damping tuning - covers the ticket's own named acceptance shape: asymmetric
 * loads (different per-wheel inputs still resolve per-axle correctly), common heave (the SAME
 * resolved per-axle value applies to every wheel on that axle regardless of its own individual
 * load share), both axles configured together, the ambiguous-layout fallback, and the
 * unset-field/base-inheritance no-op equivalence.
 */
class AxleSuspensionDistributionTest {
 private static final double[] Z4 = {1.5, 1.5, -1.5, -1.5}; // front axle (0,1), rear axle (2,3)
 @Test void zeroWheelsReturnsEmpty() {
  assertEquals(0, AxleSuspensionDistribution.springMultipliers(new double[0], 1.0, 2.0, 0.5).length);
 }
 @Test void bothAxleFieldsUnsetInheritsTheBaseValueOnEveryWheel() {
  double[] m = AxleSuspensionDistribution.springMultipliers(Z4, 1.3, null, null);
  for (double v : m) assertEquals(1.3, v, 0.0, "unset axle fields must reproduce the base value exactly");
  double[] r = AxleSuspensionDistribution.dampingRatios(Z4, 0.6, null, null);
  for (double v : r) assertEquals(0.6, v, 0.0);
 }
 @Test void frontOnlyConfiguredLeavesTheRearAxleInheritingTheBaseValue() {
  double[] m = AxleSuspensionDistribution.springMultipliers(Z4, 1.0, 2.5, null);
  assertEquals(2.5, m[0], 0.0); assertEquals(2.5, m[1], 0.0);
  assertEquals(1.0, m[2], 0.0, "unconfigured rear axle inherits the base multiplier, not a second default");
  assertEquals(1.0, m[3], 0.0);
 }
 @Test void rearOnlyConfiguredLeavesTheFrontAxleInheritingTheBaseValue() {
  double[] r = AxleSuspensionDistribution.dampingRatios(Z4, 0.7, null, 1.2);
  assertEquals(0.7, r[0], 0.0); assertEquals(0.7, r[1], 0.0);
  assertEquals(1.2, r[2], 0.0); assertEquals(1.2, r[3], 0.0);
 }
 @Test void bothAxlesConfiguredIndependently() {
  double[] m = AxleSuspensionDistribution.springMultipliers(Z4, 1.0, 2.0, 0.6);
  assertEquals(2.0, m[0], 0.0); assertEquals(2.0, m[1], 0.0);
  assertEquals(0.6, m[2], 0.0); assertEquals(0.6, m[3], 0.0);
 }
 @Test void asymmetricLoadDoesNotAffectWhichPerAxleValueAWheelResolvesTo() {
  // springMultipliers/dampingRatios only ever look at z (axle grouping), never loadShare - an
  // asymmetric COM offset (different loadShare per wheel, computed separately by
  // WheelLoadDistribution) must not change which axle value a given wheel resolves to.
  double[] m = AxleSuspensionDistribution.springMultipliers(Z4, 1.0, 1.8, 0.9);
  assertEquals(1.8, m[0], 0.0); assertEquals(1.8, m[1], 0.0);
  assertEquals(0.9, m[2], 0.0); assertEquals(0.9, m[3], 0.0);
 }
 @Test void commonHeaveAppliesTheSamePerAxleValueToEveryWheelOnThatAxle() {
  // "Common heave" (every wheel on one axle moving together) relies on both of that axle's
  // wheels sharing the exact same resolved rate/ratio - verified directly here.
  double[] r = AxleSuspensionDistribution.dampingRatios(Z4, 0.7, 1.4, 0.5);
  assertEquals(r[0], r[1], 0.0, "front axle wheels must share one resolved damping ratio");
  assertEquals(r[2], r[3], 0.0, "rear axle wheels must share one resolved damping ratio");
 }
 @Test void allWheelsAtTheSameZFallsBackToTheBaseValueRatherThanGuessingWhichAxleApplies() {
  double[] z = {0.3, 0.3, 0.3, 0.3}; // single axle / side-by-side-only layout - ambiguous
  double[] m = AxleSuspensionDistribution.springMultipliers(z, 1.0, 3.0, 3.0);
  for (double v : m) assertEquals(1.0, v, 0.0, "ambiguous layout must fall back to the base multiplier, not an axle-specific one");
 }
 @Test void aLoneWheelFallsBackToTheBaseValue() {
  double[] m = AxleSuspensionDistribution.springMultipliers(new double[]{1.0}, 1.0, 3.0, 3.0);
  assertEquals(1.0, m[0], 0.0);
 }
 @Test void reversedLayoutStillPicksTheGeometricallyForwardAxleAsFront() {
  double[] z = {-1.5, -1.5, 1.5, 1.5};
  double[] m = AxleSuspensionDistribution.springMultipliers(z, 1.0, 2.0, 0.5);
  assertEquals(0.5, m[0], 0.0); assertEquals(0.5, m[1], 0.0); // rear (negative z here)
  assertEquals(2.0, m[2], 0.0); assertEquals(2.0, m[3], 0.0); // front (max z here)
 }
 @Test void multiAxleLayoutLumpsEveryNonFrontRowIntoOneRearGroupToo() {
  double[] z = {2.0, 2.0, 0.0, 0.0, -2.0, -2.0};
  double[] m = AxleSuspensionDistribution.springMultipliers(z, 1.0, 2.0, 0.5);
  assertEquals(2.0, m[0], 0.0); assertEquals(2.0, m[1], 0.0);
  for (int i = 2; i < 6; i++) assertEquals(0.5, m[i], 0.0, "wheel " + i + " is part of the lumped rear group");
 }
 @Test void everyResolvedValueIsAlwaysFiniteAcrossManyRandomLayoutsAndInputs() {
  java.util.Random rng = new java.util.Random(53901);
  for (int trial = 0; trial < 500; trial++) {
   int n = 1 + rng.nextInt(8);
   double[] z = new double[n];
   for (int i = 0; i < n; i++) z[i] = rng.nextDouble() * 4 - 2;
   double base = 0.5 + rng.nextDouble() * 3.5;
   Double front = rng.nextBoolean() ? null : 0.5 + rng.nextDouble() * 3.5;
   Double rear = rng.nextBoolean() ? null : 0.5 + rng.nextDouble() * 3.5;
   double[] m = AxleSuspensionDistribution.springMultipliers(z, base, front, rear);
   assertEquals(n, m.length);
   for (double v : m) assertTrue(Double.isFinite(v), "trial " + trial + ": resolved value must be finite, got " + v);
  }
 }
}
