package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-532: {@link AxleDriveForceSharing#shareRatios} is the pure-math core of the opt-in
 * contact-aware axle drive-force sharing - covers the ticket's own named acceptance shape: equal
 * contacts (no change from load-weighting since every grounded wheel carries the same load), an
 * asymmetric-contact split (weighted exactly by real load), one wheel airborne (its share moves
 * to its grounded same-axle mate(s), never to the other axle), zero/all wheels airborne on one
 * axle (fallback - left untouched, matching the disabled case), and conservation (an axle's own
 * total assigned ratio is preserved exactly across the redistribution). Also covers disabled
 * (default) producing a byte-identical, independently-allocated copy.
 */
class AxleDriveForceSharingTest {
 private static final double[] Z4 = {1.5, 1.5, -1.5, -1.5}; // front axle (0,1), rear axle (2,3)
 private static double sum(double[] a, int from, int to) { double s=0; for (int i=from;i<to;i++) s+=a[i]; return s; }
 @Test void disabledReturnsAByteIdenticalIndependentCopyOfTheAssignedRatios() {
  double[] assigned = {1.3, 0.7, 1.1, 0.9};
  boolean[] grounded = {true, false, true, true};
  double[] load = {500, 0, 400, 600};
  double[] result = AxleDriveForceSharing.shareRatios(Z4, assigned, grounded, load, false);
  assertArrayEquals(assigned, result, 0.0, "disabled must reproduce the exact assigned ratios byte-for-byte");
  assertNotSame(assigned, result, "disabled must still return a defensive copy, never the same array reference");
 }
 @Test void zeroWheelsReturnsEmpty() {
  assertEquals(0, AxleDriveForceSharing.shareRatios(new double[0], new double[0], new boolean[0], new double[0], true).length);
 }
 @Test void equalContactsOnBothAxlesLeavesRatiosUnchanged() {
  // Every wheel grounded with identical load - redistribution weighted by (equal) load produces
  // exactly the same split the axle already had, same reasoning WheelLoadDistributionTest's own
  // symmetric-equivalence case uses.
  double[] assigned = {1.0, 1.0, 1.0, 1.0};
  boolean[] grounded = {true, true, true, true};
  double[] load = {1000, 1000, 1000, 1000};
  double[] result = AxleDriveForceSharing.shareRatios(Z4, assigned, grounded, load, true);
  assertArrayEquals(assigned, result, 1e-9, "equal contacts must leave an already-even allocation unchanged");
 }
 @Test void asymmetricContactsSplitExactlyByRealMeasuredLoad() {
  // Front axle: both grounded, 3:1 load split. Rear axle: both grounded, equal load.
  double[] assigned = {1.0, 1.0, 1.0, 1.0};
  boolean[] grounded = {true, true, true, true};
  double[] load = {750, 250, 400, 400};
  double[] result = AxleDriveForceSharing.shareRatios(Z4, assigned, grounded, load, true);
  assertEquals(1.5, result[0], 1e-9, "front wheel bearing 3x the load must receive 3x the front axle's redistributed share");
  assertEquals(0.5, result[1], 1e-9);
  assertEquals(1.0, result[2], 1e-9, "rear axle's own equal load must still split evenly");
  assertEquals(1.0, result[3], 1e-9);
  assertEquals(2.0, sum(result,0,2), 1e-9, "front axle's own total assigned ratio must be exactly preserved");
  assertEquals(2.0, sum(result,2,4), 1e-9, "rear axle's own total assigned ratio must be exactly preserved");
 }
 @Test void oneAirborneWheelLosesItsOwnRatioToItsGroundedSameAxleMateOnly() {
  double[] assigned = {1.0, 1.0, 1.0, 1.0};
  boolean[] grounded = {true, false, true, true}; // front-right airborne
  double[] load = {500, 0, 400, 400};
  double[] result = AxleDriveForceSharing.shareRatios(Z4, assigned, grounded, load, true);
  assertEquals(0.0, result[1], 1e-12, "the airborne wheel itself must receive zero ratio");
  assertEquals(2.0, result[0], 1e-9, "the front axle's whole 2.0 total must move to the one remaining grounded front wheel");
  assertEquals(1.0, result[2], 1e-9, "the rear axle must be completely untouched by the front axle's own airborne wheel");
  assertEquals(1.0, result[3], 1e-9);
 }
 @Test void everyWheelOnOneAxleAirborneLeavesThatAxleUntouchedRatherThanFabricatingASplit() {
  double[] assigned = {1.0, 1.0, 1.0, 1.0};
  boolean[] grounded = {false, false, true, true}; // whole front axle airborne
  double[] load = {0, 0, 400, 400};
  double[] result = AxleDriveForceSharing.shareRatios(Z4, assigned, grounded, load, true);
  assertEquals(1.0, result[0], 1e-12, "no grounded wheel on the front axle to weight a split by - must stay exactly as assigned");
  assertEquals(1.0, result[1], 1e-12);
  assertEquals(1.0, result[2], 1e-9, "rear axle (fully grounded, equal load) must still split evenly as before");
  assertEquals(1.0, result[3], 1e-9);
 }
 @Test void everyWheelOnOneAxleReportingZeroLoadAlsoFallsBackRatherThanDividingByZero() {
  // A real but degenerate tick: grounded, but with no measurable contact load at all.
  double[] assigned = {1.0, 1.0, 1.0, 1.0};
  boolean[] grounded = {true, true, true, true};
  double[] load = {0, 0, 400, 400};
  double[] result = AxleDriveForceSharing.shareRatios(Z4, assigned, grounded, load, true);
  assertEquals(1.0, result[0], 1e-12);
  assertEquals(1.0, result[1], 1e-12);
  assertEquals(1.0, result[2], 1e-9);
  assertEquals(1.0, result[3], 1e-9);
 }
 @Test void allWheelsAirborneLeavesEveryRatioExactlyAsAssigned() {
  double[] assigned = {1.0, 1.0, 1.0, 1.0};
  boolean[] grounded = {false, false, false, false};
  double[] load = {0, 0, 0, 0};
  double[] result = AxleDriveForceSharing.shareRatios(Z4, assigned, grounded, load, true);
  assertArrayEquals(assigned, result, 1e-12, "no wheel anywhere is grounded - nothing to redistribute, same as disabled");
 }
 @Test void asymmetricAxleWheelCountsConserveEachAxlesOwnTotalWithOneWheelAirborne() {
  // One front wheel (trike-like), two rear wheels, one rear wheel airborne.
  double[] z = {2.0, -1.0, -1.0};
  double[] assigned = {0.7*3, 0.15*3, 0.15*3}; // DriveBiasDistribution.shareRatios(z, 0.7) shape
  boolean[] grounded = {true, true, false};
  double[] load = {900, 500, 0};
  double[] result = AxleDriveForceSharing.shareRatios(z, assigned, grounded, load, true);
  assertEquals(assigned[0], result[0], 1e-9, "the lone front wheel is unaffected by the rear axle's own airborne wheel");
  assertEquals(assigned[1]+assigned[2], result[1], 1e-9, "the remaining grounded rear wheel must receive the whole rear axle's total");
  assertEquals(0.0, result[2], 1e-12);
 }
 @Test void everyResultIsAlwaysFiniteNonNegativeAndConservesEachAxlesOwnTotalAcrossManyRandomTrials() {
  java.util.Random rng = new java.util.Random(53200);
  for (int trial = 0; trial < 500; trial++) {
   int n = 2 + rng.nextInt(7);
   double[] z = new double[n]; double[] assigned = new double[n]; boolean[] grounded = new boolean[n]; double[] load = new double[n];
   for (int i = 0; i < n; i++) {
    z[i] = rng.nextDouble()*4-2;
    assigned[i] = rng.nextDouble()*2;
    grounded[i] = rng.nextBoolean();
    load[i] = grounded[i] ? rng.nextDouble()*1000 : 0;
   }
   double[] result = AxleDriveForceSharing.shareRatios(z, assigned, grounded, load, true);
   assertEquals(n, result.length);
   double maxZ = Double.NEGATIVE_INFINITY; for (double v : z) maxZ = Math.max(maxZ, v);
   double frontAssigned=0, frontResult=0, rearAssigned=0, rearResult=0;
   for (int i = 0; i < n; i++) {
    assertTrue(Double.isFinite(result[i]) && result[i] >= 0, "trial "+trial+": ratio must be finite and non-negative, got "+result[i]);
    boolean isFront = z[i] >= maxZ - 1e-6;
    if (isFront) { frontAssigned += assigned[i]; frontResult += result[i]; }
    else { rearAssigned += assigned[i]; rearResult += result[i]; }
   }
   assertEquals(frontAssigned, frontResult, 1e-9, "trial "+trial+": front axle's own total must be conserved");
   assertEquals(rearAssigned, rearResult, 1e-9, "trial "+trial+": rear axle's own total must be conserved");
  }
 }
}
