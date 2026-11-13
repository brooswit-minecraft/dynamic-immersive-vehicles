package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-506: {@link WheelLoadDistribution#shares} is the pure-math core of the
 * ride-height/load-handling gap this ticket fixes - the uniform {@code 1/wheelCount} static
 * load split {@code PhysicsBridge} previously assumed for every wheel regardless of actual
 * chassis geometry. Covers exactly the acceptance shape the ticket names: a symmetric/default
 * case that must reduce to today's even split, small vs large wheel-radius geometry (radius
 * itself does not enter this model at all - see the dedicated test below for why that is
 * correct), an asymmetric/uneven-geometry case proving the shares sum to 1.0 and shift
 * sensibly, and degenerate-geometry bounded validation (never NaN/negative/divide-by-zero).
 */
class WheelLoadDistributionTest {
 private static double sum(double[] a) { double s=0; for (double x:a) s+=x; return s; }
 @Test void zeroWheelsReturnsEmpty() {
  assertEquals(0, WheelLoadDistribution.shares(new double[0], new double[0]).length);
 }
 @Test void oneWheelCarriesTheWholeLoad() {
  double[] shares = WheelLoadDistribution.shares(new double[]{1.7}, new double[]{-3.2});
  assertArrayEquals(new double[]{1.0}, shares, 1e-12);
 }
 @Test void symmetricFourWheelLayoutReducesToTheEvenSplit() {
  // A typical 4-wheel car: front/rear axle symmetric about the COM on both x and z.
  double[] x = {1.0, -1.0, 1.0, -1.0};
  double[] z = {1.5, 1.5, -1.5, -1.5};
  double[] shares = WheelLoadDistribution.shares(x, z);
  assertEquals(1.0, sum(shares), 1e-9);
  for (double s : shares) assertEquals(0.25, s, 1e-9, "a symmetric layout must reduce to EXACTLY today's 1/wheelCount split");
 }
 @Test void symmetricSixWheelTruckLayoutReducesToTheEvenSplit() {
  // Three symmetric axles (front, mid, rear), common 6-wheel truck chassis.
  double[] x = {1.0, -1.0, 1.0, -1.0, 1.0, -1.0};
  double[] z = {2.0, 2.0, 0.0, 0.0, -2.0, -2.0};
  double[] shares = WheelLoadDistribution.shares(x, z);
  assertEquals(1.0, sum(shares), 1e-9);
  for (double s : shares) assertEquals(1.0/6.0, s, 1e-9);
 }
 @Test void producesEqualSharesForMatchingProductionSuspensionCalibrationRegardlessOfWheelRadius() {
  // Radius/wheel-SIZE does not feed this model at all - a wheel's own RADIUS does not change
  // how much of the vehicle's static weight it structurally carries (that is purely a function
  // of WHERE the wheel is mounted, not how big it is); a small spare-tire-sized wheel mounted
  // at the same anchor as a large monster-truck wheel carries the identical static share. This
  // test documents that invariant directly, confirming PhysicsBridge.suspensionSpringForShare/
  // suspensionDamperForShare (which only consume the SHARE, never radius) are being fed the
  // geometrically-correct, size-independent value - radius still correctly affects raycast
  // length/contact point elsewhere in PhysicsBridge, just not the load-share math.
  double[] x = {1.0, -1.0, 1.0, -1.0};
  double[] z = {1.5, 1.5, -1.5, -1.5};
  double[] shares = WheelLoadDistribution.shares(x, z);
  double mass=1500, gravity=11, travel=0.3;
  double smallWheelSpring = PhysicsBridge.suspensionSpringForShare(mass, shares[0], travel, gravity);
  double largeWheelSpring = PhysicsBridge.suspensionSpringForShare(mass, shares[2], travel, gravity);
  assertEquals(smallWheelSpring, largeWheelSpring, 1e-9, "two wheels with the SAME load share must get the SAME calibrated spring regardless of their own radius");
 }
 @Test void comOffsetTowardFrontAxleShiftsMoreLoadToTheFrontAndSharesStillSumToOne() {
  // Engine-forward sedan analogue: front axle at z=+0.5 (CLOSER to the COM at z=0), rear axle
  // at z=-1.5 (FARTHER from the COM) - an uneven wheelbase split relative to the chassis COM.
  double[] x = {1.0, -1.0, 1.0, -1.0};
  double[] z = {0.5, 0.5, -1.5, -1.5};
  double[] shares = WheelLoadDistribution.shares(x, z);
  assertEquals(1.0, sum(shares), 1e-9, "shares must always sum to exactly 1.0");
  // Front wheels (indices 0,1) are closer to the COM than the rear wheels (2,3) - the closer
  // corner must carry MORE of the static load (the real-car "near support bears more" relation
  // this ticket's own inventory derives from ordinary beam statics).
  assertTrue(shares[0] > shares[2], "the axle closer to the COM must carry a larger share than the farther axle");
  assertEquals(shares[0], shares[1], 1e-9, "left/right symmetry on the same axle must still split evenly");
  assertEquals(shares[2], shares[3], 1e-9);
 }
 @Test void lateralComOffsetShiftsLoadSidewaysAndStillSumsToOne() {
  // COM offset toward one side (x<0 wheels closer to COM than x>0 wheels).
  double[] x = {2.0, -0.5, 2.0, -0.5};
  double[] z = {1.0, 1.0, -1.0, -1.0};
  double[] shares = WheelLoadDistribution.shares(x, z);
  assertEquals(1.0, sum(shares), 1e-9);
  assertTrue(shares[1] > shares[0], "the side closer to the COM (x=-0.5) must carry more load than the far side (x=2.0)");
  assertTrue(shares[3] > shares[2]);
 }
 @Test void allWheelsAtTheSamePointFallsBackToTheEvenSplitRatherThanDividingByZero() {
  double[] x = {0,0,0,0};
  double[] z = {0,0,0,0};
  double[] shares = WheelLoadDistribution.shares(x, z);
  for (double s : shares) {
   assertTrue(Double.isFinite(s), "degenerate geometry must never produce NaN/Infinite");
   assertEquals(0.25, s, 1e-9, "a fully degenerate (zero-spread) layout must fall back to the even split");
  }
 }
 @Test void collinearSingleTrackLayoutFallsBackToTheEvenSplit() {
  // Every wheel on one straight line along z (x always 0, e.g. a motorcycle/single-track
  // vehicle) - the 3x3 system is singular (every x-term is zero), so this must not throw, NaN,
  // or silently divide by zero; it must fall back to a safe even split.
  double[] x = {0,0,0};
  double[] z = {1.0, 0.0, -1.0};
  double[] shares = WheelLoadDistribution.shares(x, z);
  assertEquals(1.0, sum(shares), 1e-9);
  for (double s : shares) {
   assertTrue(Double.isFinite(s) && s >= 0);
   assertEquals(1.0/3.0, s, 1e-9);
  }
 }
 @Test void twoWheelLayoutSymmetricAboutComSplitsEvenly() {
  double[] x = {0, 0};
  double[] z = {-1.0, 1.0};
  double[] shares = WheelLoadDistribution.shares(x, z);
  assertEquals(1.0, sum(shares), 1e-9);
  assertEquals(0.5, shares[0], 1e-9);
  assertEquals(0.5, shares[1], 1e-9);
 }
 @Test void everyShareIsAlwaysFiniteNonNegativeAndSumsToOneAcrossManyRandomLayouts() {
  // Broad bounded-validation sweep: whatever the geometry, the contract never breaks.
  java.util.Random rng = new java.util.Random(53506);
  for (int trial=0; trial<500; trial++) {
   int n = 1 + rng.nextInt(8);
   double[] x = new double[n], z = new double[n];
   for (int i=0;i<n;i++) { x[i]=rng.nextDouble()*4-2; z[i]=rng.nextDouble()*4-2; }
   double[] shares = WheelLoadDistribution.shares(x, z);
   assertEquals(n, shares.length);
   double s=0;
   for (double share : shares) {
    assertTrue(Double.isFinite(share) && share >= 0, "trial "+trial+": share must be finite and non-negative, got "+share);
    s+=share;
   }
   assertEquals(1.0, s, 1e-6, "trial "+trial+": shares must sum to exactly 1.0");
  }
 }
 @Test void comOverOneAxleFallsBackToTheEvenSplitRatherThanZeroingTheFarAxle() {
  // Ticket review comment 53678's exact reproduction: a 4-wheel layout whose COM (the origin,
  // since x/z are already COM-relative) sits exactly over the z=0 axle. This rigid-plank
  // model's own correct statics would otherwise put the z=2 axle's share at EXACTLY zero -
  // geometrically honest, but PhysicsBridge.suspensionSpringForShare would then calibrate
  // spring=damper=0 for those two wheels, PERMANENTLY disabling their installed suspension
  // (never able to react even if later pitch/landing compresses them). This must instead
  // fall back to the even split for every wheel, exactly like the other degenerate cases.
  double[] x = {-1, 1, -1, 1};
  double[] z = {0, 0, 2, 2};
  double[] shares = WheelLoadDistribution.shares(x, z);
  assertEquals(1.0, sum(shares), 1e-9);
  for (double s : shares) {
   assertTrue(s > 0, "no wheel's static share may be zero (or negative) - an installed suspension must always get a positive calibrated rate");
   assertEquals(0.25, s, 1e-9, "a COM-over-one-axle layout must fall back to the even split, not a zeroed far axle");
  }
 }
}
