package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-level coverage for ticket comment 53189's "add zero-normal-hit/airborne gating
 * tests" and comment 53191's "rear-only positive support" requirement - exercises the SAME
 * raycast+suspension geometry this investigation's other replay tests use, computing total
 * normal load the way {@code Body.stepGround}'s actual wiring does (summed across all 4 wheels'
 * {@code WheelHit.normal()}), rather than the pure {@code SelfRightingAssist.torque} unit alone.
 *
 * <p>Review (comment 53194) caught that the first version never actually exercised a
 * ray-HIT-but-zero-normal state (it hardcoded zero vertical velocity, so the damper term never
 * overwhelmed the spring term) and that the rear-only case only asserted total&gt;0, not which
 * specific wheels were loaded/missed - fixed here with an explicit velocity parameter and
 * per-wheel assertions.
 *
 * <p>Does not cover a full perturbed/settling no-wobble-retention run with the assist active
 * (comment 53189 also asked for that - see {@code SelfRightingPerturbedReplayTest}).
 */
class SelfRightingAssistGatingIntegrationTest {
 private static final double MASS=350.0, GRAVITY=11.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375, LENGTH=WHEEL_RADIUS+SUSPENSION_TRAVEL;
 private static final double EQUILIBRIUM_BODY_POSITION_Y=0.803593748062849;
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625);
 private static final Vector3d[] REAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 private static final boolean[] IS_FRONT={false,true,true,false};
 private static Vector3d anchorLocal(int i) { return new Vector3d(REAL_OFFSETS[i].x, REAL_OFFSETS[i].y+SUSPENSION_TRAVEL*.5, REAL_OFFSETS[i].z).sub(CENTER); }
 private static double suspensionSpring(int n) { return MASS*GRAVITY/(n*SUSPENSION_TRAVEL*.5); }
 private static double suspensionDamper(double spring, int n) { return 2*Math.sqrt(spring*MASS/n)*.7; }
 private static double suspensionForce(double c, double v, double spring, double damper) { return Math.max(0, c*spring-v*damper); }
 private record WheelResult(boolean hit, double normal, boolean front) {}
 /** @param upwardVelocity the wheel anchor's own velocity along "up" - a large enough positive
  *  value drives the damper term past the spring term, producing hit=true but normal=0 (review
  *  comment 53194's explicit ask: a ray HIT that is still unloaded, not a miss). */
 private static WheelResult raycastWheel(int i, double bodyPositionY, Quaterniond orientation, double spring, double damper, double upwardVelocity) {
  Vector3d anchorWorld=orientation.transform(new Vector3d(anchorLocal(i)));
  Vector3d up=orientation.transform(new Vector3d(0,1,0));
  double startY=bodyPositionY+anchorWorld.y;
  double tt=startY/(LENGTH*up.y);
  if (!(tt>=0&&tt<=1)||!Double.isFinite(tt)) return new WheelResult(false,0,IS_FRONT[i]);
  double compression=LENGTH*(1-tt);
  double normal=Math.min(MASS*GRAVITY, suspensionForce(compression, upwardVelocity, spring, damper));
  return new WheelResult(true,normal,IS_FRONT[i]);
 }
 private static WheelResult[] allWheels(double bodyPositionY, Quaterniond orientation, double upwardVelocity) {
  double spring=suspensionSpring(4), damper=suspensionDamper(spring,4);
  var results=new WheelResult[4];
  for (int i=0;i<4;i++) results[i]=raycastWheel(i,bodyPositionY,orientation,spring,damper,upwardVelocity);
  return results;
 }
 /** Mirrors Body.stepGround's own wiring exactly: sum normal across all 4 wheels (not just
  * "any hit"), then gate on totalNormalLoad&gt;0. */
 private static double totalNormalLoad(WheelResult[] wheels) {
  double total=0; for (var w : wheels) if (w.hit()) total+=w.normal(); return total;
 }

 @Test void settledFlatVehicleHasPositiveTotalSupport() {
  assertTrue(totalNormalLoad(allWheels(EQUILIBRIUM_BODY_POSITION_Y, new Quaterniond(), 0)) > 0);
 }

 @Test void fullyAirborneVehicleHasExactlyZeroTotalSupport() {
  // Body far above the ground - every ray misses, so total normal load is exactly 0, not merely
  // "some ray happened to report zero".
  assertEquals(0, totalNormalLoad(allWheels(EQUILIBRIUM_BODY_POSITION_Y + 10.0, new Quaterniond(), 0)));
 }

 @Test void rayHitButZeroNormalFromExcessiveUpwardVelocityIsNotTreatedAsSupport() {
  // Review comment 53194: a ray HIT that the damper has driven to zero load is a real, distinct
  // case from a miss - must still gate as "no support", and this test must actually construct
  // that state (hit=true, normal=0), not merely claim to.
  double spring=suspensionSpring(4), damper=suspensionDamper(spring,4);
  double compression=0.05; // small but nonzero - genuinely compressed, a real hit
  double hugeUpwardVelocity=1000.0; // damper*velocity term overwhelms spring*compression
  double normal=suspensionForce(compression, hugeUpwardVelocity, spring, damper);
  assertEquals(0, normal, "this specific velocity must actually drive normal force to exactly zero, confirming the hit/zero-load state is real, not assumed");
  var wheels=allWheels(EQUILIBRIUM_BODY_POSITION_Y, new Quaterniond(), hugeUpwardVelocity);
  boolean anyHit=false; for (var w : wheels) if (w.hit()) anyHit=true;
  assertTrue(anyHit, "the ray(s) must still report hit=true - this is a loaded->unloaded transition, not the vehicle leaving the ground");
  assertEquals(0, totalNormalLoad(wheels), "total normal load must be exactly zero despite real ray hits, so the assist correctly treats this as no support");
 }

 @Test void rearOnlySupportDuringAWheelieExplicitlyHasFrontUnloadedAndRearLoaded() {
  // Review comment 53194: assert WHICH wheels, not just that the total happens to be positive.
  Quaterniond pitched=new Quaterniond().rotateX(Math.toRadians(-25)); // nose-up
  var wheels=allWheels(EQUILIBRIUM_BODY_POSITION_Y, pitched, 0);
  for (var w : wheels) {
   if (w.front()) assertTrue(!w.hit() || w.normal()==0, "front wheels must be unloaded or missed entirely during this nose-up pitch - got hit="+w.hit()+" normal="+w.normal());
   else assertTrue(w.hit() && w.normal()>0, "rear wheels must be hit AND positively loaded during this nose-up pitch - got hit="+w.hit()+" normal="+w.normal());
  }
  assertTrue(totalNormalLoad(wheels) > 0, "with rear wheels confirmed loaded, total support must be positive");
 }

 @Test void selfRightTorqueRespondsCorrectlyGivenThisWiringsOwnHasPositiveSupportGate() {
  // End-to-end: compute hasPositiveSupport the same way the real wiring does, then feed it into
  // the actual SelfRightingAssist.torque used by Body.stepGround.
  var config = new SelfRightingAssist.Config(500,100,2000,15,60);
  Quaterniond rolled=new Quaterniond().rotateZ(Math.toRadians(-20));
  boolean hasPositiveSupportWhileRolled = totalNormalLoad(allWheels(EQUILIBRIUM_BODY_POSITION_Y, rolled, 0)) > 0;
  Vector3d forward=rolled.transform(new Vector3d(0,0,1)), up=rolled.transform(new Vector3d(0,1,0)), right=rolled.transform(new Vector3d(1,0,0));
  var torque = SelfRightingAssist.torque(config, forward, up, right, new Vector3d(0,0,0), true, hasPositiveSupportWhileRolled);
  assertTrue(hasPositiveSupportWhileRolled, "a vehicle resting at its equilibrium height, just rolled, must still have real support");
  assertTrue(torque.length() > 0, "with real support confirmed and a real bank angle, the assist must produce a nonzero correcting torque through this exact wiring path");

  double totalWhenFarAirborne = totalNormalLoad(allWheels(EQUILIBRIUM_BODY_POSITION_Y + 10.0, rolled, 0));
  var torqueWhenAirborne = SelfRightingAssist.torque(config, forward, up, right, new Vector3d(0,0,0), true, totalWhenFarAirborne > 0);
  assertEquals(0, torqueWhenAirborne.length(), 1e-12, "the same bank angle, while airborne, must produce zero torque through this exact wiring path");
 }
}
