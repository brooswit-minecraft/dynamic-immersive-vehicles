package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-506: extends {@link PhysicsBridgeRideHeightSimulationTest}'s own per-wheel vertical
 * dynamics simulation (same semi-implicit Euler integration, same production
 * {@link TireForces#suspension} force law, same fixed {@code DT}) from the single shared
 * spring/damper rate it exercises to the actual PRODUCTION per-wheel path this ticket adds:
 * {@link PhysicsBridge#suspensionSpringForShare}/{@link PhysicsBridge#suspensionDamperForShare},
 * fed by {@link WheelLoadDistribution#shares}. {@code Body.stepGround} itself still cannot be
 * driven without a live Minecraft/IV/Sable runtime (see VERIFICATION.md); this is the same
 * pure-function substitute that class's own simulation test already uses, extended to cover
 * multiple wheels with DIFFERENT calibrated rates settling simultaneously - exactly the
 * uneven-load-handling scenario this ticket's gap-mapping names.
 */
class PhysicsBridgeWheelLoadShareSimulationTest {
 private static final double TRAVEL = 0.3, TOLERANCE = 1e-6;
 /** One semi-implicit-Euler tick for N wheels that may each have a DIFFERENT calibrated
  * spring/damper (unlike PhysicsBridgeRideHeightSimulationTest's single shared rate) - otherwise
  * identical integration order/structure to that class's own {@code tick}. */
 private static void tick(double[] compression, double[] velocity, double mass, double[] spring, double[] damper, double gravity) {
  int n = compression.length;
  double totalNormal = 0;
  double[] perWheelNormal = new double[n];
  for (int i=0;i<n;i++) {
   perWheelNormal[i] = Math.min(mass*gravity, TireForces.suspension(compression[i], velocity[i], spring[i], damper[i]));
   totalNormal += perWheelNormal[i];
  }
  double netAcceleration = -gravity + totalNormal/mass;
  // Every wheel shares the SAME rigid-body vertical acceleration (one chassis, N wheels) - only
  // each wheel's own compression/local velocity differ, exactly as stepGround's single shared
  // bodyPositionY does for every wheel's own raycast.
  for (int i=0;i<n;i++) {
   velocity[i] = velocity[i] + PhysicsBridge.DT*netAcceleration;
   compression[i] = compression[i] - PhysicsBridge.DT*velocity[i];
  }
 }
 private static double[] settle(double mass, double[] loadShares, double gravity, int ticks) {
  int n = loadShares.length;
  double[] compression = new double[n], velocity = new double[n];
  java.util.Arrays.fill(compression, TRAVEL*.5);
  double[] spring = new double[n], damper = new double[n];
  for (int i=0;i<n;i++) {
   spring[i] = PhysicsBridge.suspensionSpringForShare(mass, loadShares[i], TRAVEL, gravity);
   damper[i] = PhysicsBridge.suspensionDamperForShare(mass, loadShares[i], spring[i]);
  }
  for (int t=0;t<ticks;t++) tick(compression, velocity, mass, spring, damper, gravity);
  return compression;
 }
 @Test void symmetricFourWheelShareBasedCalibrationMatchesTheOriginalEvenSplitFormulaWithinFloatingPointTolerance() {
  double mass=1500, gravity=11;
  double[] x = {1.0,-1.0,1.0,-1.0}, z = {1.5,1.5,-1.5,-1.5};
  double[] shares = WheelLoadDistribution.shares(x, z);
  for (double s : shares) assertEquals(0.25, s, 1e-9);
  double oldSpring = PhysicsBridge.suspensionSpring(mass, 4, TRAVEL, gravity);
  double oldDamper = PhysicsBridge.suspensionDamper(mass, 4, oldSpring);
  for (double share : shares) {
   double newSpring = PhysicsBridge.suspensionSpringForShare(mass, share, TRAVEL, gravity);
   double newDamper = PhysicsBridge.suspensionDamperForShare(mass, share, newSpring);
   assertEquals(oldSpring, newSpring, 1e-9, "share-based spring must match the original even-split formula for a symmetric default vehicle");
   assertEquals(oldDamper, newDamper, 1e-9, "share-based damper must match the original even-split formula for a symmetric default vehicle");
  }
 }
 @Test void symmetricLayoutSettlesAtTheSameEquilibriumAsTheOriginalUniformFormulaForBothSmallAndLargeWheelFleets() {
  // Radius itself never enters the share/spring/damper math (confirmed directly by
  // WheelLoadDistributionTest); this exercises the full settling simulation for a small-wheel
  // ATV-like vehicle and a large-wheel truck-like vehicle, both symmetric, to confirm each
  // settles at exactly the SAME predicted ride-height equilibrium the original uniform formula
  // already guarantees (PhysicsBridgeRideHeightSimulationTest) - wheel size is irrelevant to
  // this particular settling value by design, only mass/gravity/travel are.
  for (var vehicle : new double[][]{{300,4},{8000,6}}) { // {mass, wheelCount} - "small"/"large" wheel-fleet stand-ins
   double mass=vehicle[0]; int wheelCount=(int)vehicle[1];
   double[] x=new double[wheelCount], z=new double[wheelCount];
   for (int i=0;i<wheelCount;i++) { x[i]=(i%2==0)?1.0:-1.0; z[i]=(i<wheelCount/2)?1.5:-1.5; }
   double[] shares=WheelLoadDistribution.shares(x,z);
   double[] settled=settle(mass, shares, 11, 400);
   for (double c : settled) assertEquals(TRAVEL*.5, c, 1e-3, "mass="+mass+" wheels="+wheelCount+" must settle at the predicted ride-height equilibrium regardless of load-share calibration");
  }
 }
 @Test void asymmetricComOffsetTowardFrontSettlesEveryWheelAtTheSameRideHeightDespiteDifferentCalibratedRates() {
  // The actual load-handling gap this ticket fixes: front axle closer to the COM than the rear
  // (engine-forward analogue) gets a STIFFER spring (more of the real static load), the rear a
  // softer one - but BOTH must still settle at exactly the same travel*.5 ride-height
  // equilibrium, because each wheel's own rate is calibrated for exactly its own share.
  double mass=1500, gravity=11;
  double[] x={1.0,-1.0,1.0,-1.0}, z={0.5,0.5,-1.5,-1.5};
  double[] shares=WheelLoadDistribution.shares(x,z);
  assertTrue(shares[0] > shares[2], "front (closer to COM) must carry a larger share than rear");
  double frontSpring=PhysicsBridge.suspensionSpringForShare(mass, shares[0], TRAVEL, gravity);
  double rearSpring=PhysicsBridge.suspensionSpringForShare(mass, shares[2], TRAVEL, gravity);
  assertTrue(frontSpring > rearSpring, "the wheel carrying more static load must get a stiffer calibrated spring");
  double[] settled=settle(mass, shares, gravity, 400);
  for (int i=0;i<settled.length;i++) {
   assertEquals(TRAVEL*.5, settled[i], 1e-3, "wheel "+i+" (share="+shares[i]+") must still settle at the same predicted ride-height equilibrium as every other wheel");
  }
 }
 @Test void hardLandingConvergesToTheSameEquilibriumAcrossUnevenLoadShares() {
  double mass=1500, gravity=11;
  double[] x={1.0,-1.0,1.0,-1.0}, z={0.5,0.5,-1.5,-1.5};
  double[] shares=WheelLoadDistribution.shares(x,z);
  double[] spring=new double[4], damper=new double[4];
  for (int i=0;i<4;i++) { spring[i]=PhysicsBridge.suspensionSpringForShare(mass,shares[i],TRAVEL,gravity); damper[i]=PhysicsBridge.suspensionDamperForShare(mass,shares[i],spring[i]); }
  double[] compression=new double[]{0,0,0,0};
  double[] velocity=new double[]{-3,-3,-3,-3};
  for (int t=0;t<800;t++) tick(compression, velocity, mass, spring, damper, gravity);
  for (int i=0;i<4;i++) assertEquals(TRAVEL*.5, compression[i], 1e-3, "wheel "+i+" must converge to the predicted equilibrium after a hard landing despite its uneven load share");
 }
}
