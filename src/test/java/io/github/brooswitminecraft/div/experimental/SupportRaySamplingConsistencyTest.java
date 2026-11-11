package io.github.brooswitminecraft.div.experimental;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * First evidence-gathering step on the "support ray sampling/application consistency" Offroad
 * lesson named in comment 53217, after the effective-mass damping candidate was closed out as a
 * net non-benefit (90ed051/02f90a0). Root-cause evidence FIRST, same discipline as every other
 * investigation this session - this does not yet propose or implement a fix.
 *
 * <p>Production (PhysicsBridge.Body.stepGround, confirmed by reading the real code) casts exactly
 * ONE ray per wheel, from a single anchor point centered on the wheel's own localOffset (no
 * lateral/width sampling). The reference Offroad mod (WheelMountBlockEntity, pinned commit
 * 3705df2) casts THREE rays per wheel mount and averages their contribution. This test builds a
 * minimal real terrain discontinuity (a single block-height step edge, the simplest real case a
 * Minecraft world raycast actually produces) and measures whether DIV's single-ray sampling
 * produces a materially larger tick-to-tick jump in contact normal/point as a wheel crosses that
 * edge than a 3-ray lateral average would - i.e. whether this is a real, measurable problem at
 * all, not assumed from the reference mod's design alone.
 *
 * <p>Idealized: approximates a block step as a height function rather than a real voxel raycast
 * (consistent with every other suspension/raycast test in this investigation, which cannot run a
 * live Minecraft world) and samples the 3 rays laterally across the wheel's own width (the most
 * geometrically plausible reading of "per wheel mount" rays, not confirmed against the reference
 * mod's own exact ray placement - that specific detail was not re-verified here).
 */
class SupportRaySamplingConsistencyTest {
 private static final double WHEEL_RADIUS=0.375, SUSPENSION_TRAVEL=0.3, LENGTH=WHEEL_RADIUS+SUSPENSION_TRAVEL;
 private static final double WHEEL_HALF_WIDTH=0.2; // plausible real tire half-width for this ATV class - not re-derived from a specific vehicle definition here
 // This test's own minimal model tracks only a scalar ray-start height above a local ground
 // datum (no full body/anchor-offset geometry as the other harnesses use) - so the equilibrium
 // constant those other tests use (which bakes in the anchor's own offset from the body center)
 // does NOT apply directly here. Using half-travel compression above y=0 ground instead - the
 // same real rest point those other tests derive (compression=travel*.5 balances the spring
 // load at flat equilibrium), expressed directly as this test's own ray-start height.
 private static final double EQUILIBRIUM_BODY_POSITION_Y=WHEEL_RADIUS+SUSPENSION_TRAVEL*0.5;
 // A full 1.0m block step would exceed this body's own suspension reach (LENGTH=0.675m) on the
 // high side, making the ray miss everywhere and the scenario physically meaningless (not a
 // real driveable case). 0.5m (a real, common Minecraft half-slab step) stays within reach on
 // both sides at this body's own equilibrium height, confirmed below by the sanity assertion.
 private static final double STEP_X=0.0, STEP_HEIGHT=0.5;

 /** Flat ground at height 0 for x<STEP_X, one block higher for x>=STEP_X - models exactly the
  * discontinuity a real voxel raycast hits crossing a block boundary, nothing smoother. */
 private static double groundHeight(double x) { return x<STEP_X ? 0 : STEP_HEIGHT; }

 private record RayResult(boolean hit, double groundY) {}
 private static RayResult castAt(double worldX, double bodyPositionY) {
  double groundY=groundHeight(worldX);
  double startY=bodyPositionY;
  double tt=(startY-groundY)/LENGTH;
  if (!(tt>=0 && tt<=1) || !Double.isFinite(tt)) return new RayResult(false, groundY);
  return new RayResult(true, groundY);
 }

 /** Single-ray sampling exactly as production does: one ray at the wheel's own anchor x. */
 private static double singleRayContactY(double anchorX, double bodyPositionY) {
  var r=castAt(anchorX, bodyPositionY);
  return r.hit() ? r.groundY() : Double.NaN;
 }

 /** 3-ray lateral average (this test's own plausible reading of the Offroad approach): samples
  * at anchorX-halfWidth, anchorX, anchorX+halfWidth and averages the ones that hit. */
 private static double threeRayAverageContactY(double anchorX, double bodyPositionY) {
  double[] offsets={-WHEEL_HALF_WIDTH, 0, WHEEL_HALF_WIDTH};
  double sum=0; int count=0;
  for (double o : offsets) {
   var r=castAt(anchorX+o, bodyPositionY);
   if (r.hit()) { sum+=r.groundY(); count++; }
  }
  return count>0 ? sum/count : Double.NaN;
 }

 /** Sweeps the wheel's anchor X across the step edge in small increments (simulating the vehicle
  * driving over it) and records the largest single-tick jump in sampled ground height for both
  * strategies. A real tick-to-tick jump in contact height feeds directly into a jump in normal
  * force magnitude/application point in the real suspension code - this measures the size of
  * that discontinuity at its source, before any suspension math is applied. */
 @Test void measuresContactHeightDiscontinuityAcrossARealStepEdge() {
  double bodyPositionY=EQUILIBRIUM_BODY_POSITION_Y;
  double stepSize=0.05; // a plausible per-tick horizontal travel distance at moderate speed
  double maxJumpSingle=0, maxJumpThreeRay=0;
  Double prevSingle=null, prevThreeRay=null;
  for (double x=-0.5; x<=0.5; x+=stepSize) {
   double single=singleRayContactY(x, bodyPositionY);
   double threeRay=threeRayAverageContactY(x, bodyPositionY);
   if (prevSingle!=null && Double.isFinite(single) && Double.isFinite(prevSingle)) maxJumpSingle=Math.max(maxJumpSingle, Math.abs(single-prevSingle));
   if (prevThreeRay!=null && Double.isFinite(threeRay) && Double.isFinite(prevThreeRay)) maxJumpThreeRay=Math.max(maxJumpThreeRay, Math.abs(threeRay-prevThreeRay));
   prevSingle=Double.isFinite(single)?Double.valueOf(single):prevSingle;
   prevThreeRay=Double.isFinite(threeRay)?Double.valueOf(threeRay):prevThreeRay;
  }
  System.out.println("[SupportRaySamplingConsistencyTest] maxJumpSingleRay="+maxJumpSingle+"m maxJumpThreeRayAverage="+maxJumpThreeRay+"m (step edge height="+STEP_HEIGHT+"m)");
  org.junit.jupiter.api.Assertions.assertTrue(maxJumpThreeRay <= maxJumpSingle,
   "a 3-ray lateral average must not jump MORE than single-ray sampling across the same step edge - got single="+maxJumpSingle+" threeRay="+maxJumpThreeRay);
  org.junit.jupiter.api.Assertions.assertTrue(maxJumpSingle >= STEP_HEIGHT*0.9,
   "sanity: single-ray sampling must actually show close to the full step discontinuity (confirming this test built a real edge, not a smoothed one) - got "+maxJumpSingle);
 }
}
