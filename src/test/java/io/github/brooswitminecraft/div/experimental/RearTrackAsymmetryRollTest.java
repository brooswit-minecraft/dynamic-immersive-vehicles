package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Narrow investigation requested in ticket comment 53122: "asymmetric support/roll buildup under
 * straight drive and brake transitions, measured force/torque directions" - NOT further throttle
 * tuning, NOT an anti-roll clamp. This tests the single cleanest force/torque-direction
 * explanation for a LATERAL roll that builds with no steering input at all: this ATV's own real
 * rear-axle track geometry is NOT symmetric about the chassis centerline. From the same
 * LOCAL_OFFSETS used throughout this investigation, the rear wheels sit at x=+0.35 and x=-0.375
 * (center x=0) - a real ~0.025m mismatch, not a mirrored pair - while the front axle (x=+-0.375)
 * IS symmetric.
 *
 * <p>Torque about the forward (roll) axis from a vertical force F at offset (x,y,z) is exactly
 * x*F (the cross-product's z-component reduces to that for a purely vertical force - verified by
 * hand before writing this test). A SYMMETRIC axle's two torques (+0.375*F, -0.375*F) cancel
 * exactly regardless of F's magnitude; this real rear axle's do not: even perfectly equal
 * left/right rear load would leave a net (0.35-0.375)*F residual. Whether the real (non-rigidly-
 * imposed) suspension actually settles to a net-zero-torque equilibrium by splitting load
 * unevenly, or instead settles into a small persistent ROLL ANGLE that itself supplies the
 * missing torque balance, is exactly the empirical question below - not assumed in either
 * direction.
 *
 * <p>Isolated the same way {@code WheelCentroidCounterfactualTest} isolated the fore-aft
 * centroid mismatch: real geometry vs. a counterfactual where ONLY the rear axle's track is
 * mirrored symmetric (same total track width, same every other coordinate) - PURE passive
 * settling, zero drive/brake/steer input, so any difference is attributable to this one
 * geometric change alone, not to any control input.
 */
class RearTrackAsymmetryRollTest {
 private static final double MASS=350.0, GRAVITY=11.0, DT=0.05, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375, LENGTH=WHEEL_RADIUS+SUSPENSION_TRAVEL;
 private static final double EQUILIBRIUM_BODY_POSITION_Y=0.803593748062849;
 private static final Vector3d HALF_EXTENTS=new Vector3d(0.42500001192092896,0.4410937510430813,1.050000011920929);
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625);
 // Real geometry: rear wheels (indices 0,3) at x=+0.35/-0.375 - NOT a mirrored pair.
 private static final Vector3d[] REAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 // Counterfactual: rear axle mirrored to the symmetric average half-width (+-0.3625), same total
 // track width, every other coordinate (including the front axle) identical to REAL_OFFSETS.
 private static final Vector3d[] SYMMETRIC_REAR_OFFSETS={
  new Vector3d(0.3625,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.3625,0.125,-0.0625)};
 private static Vector3d anchorLocal(Vector3d[] offsets, int i) { return new Vector3d(offsets[i].x, offsets[i].y+SUSPENSION_TRAVEL*.5, offsets[i].z).sub(CENTER); }
 private static Vector3d inertiaBody() {
  double a=HALF_EXTENTS.x,b=HALF_EXTENTS.y,c=HALF_EXTENTS.z;
  return new Vector3d(MASS/3*(b*b+c*c), MASS/3*(a*a+c*c), MASS/3*(a*a+b*b));
 }
 private static double suspensionSpring(int n) { return MASS*GRAVITY/(n*SUSPENSION_TRAVEL*.5); }
 private static double suspensionDamper(double spring, int n) { return 2*Math.sqrt(spring*MASS/n)*.7; }
 private static double suspensionForce(double c, double v, double spring, double damper) { return Math.max(0, c*spring-v*damper); }

 private record WheelState(Vector3d anchorWorld, Vector3d up, boolean hit, double normal, Vector3d point) {}
 private static WheelState raycastWheel(Vector3d[] offsets, int i, double bodyPositionY, Quaterniond orientation, Vector3d velocity, Vector3d angular, double spring, double damper) {
  Vector3d anchorWorld=orientation.transform(new Vector3d(anchorLocal(offsets,i)));
  Vector3d up=orientation.transform(new Vector3d(0,1,0));
  double startY=bodyPositionY+anchorWorld.y;
  double tt=startY/(LENGTH*up.y);
  if (!(tt>=0&&tt<=1)||!Double.isFinite(tt)) return new WheelState(anchorWorld,up,false,0,null);
  double compression=LENGTH*(1-tt);
  Vector3d point=new Vector3d(anchorWorld).fma(-tt*LENGTH,up);
  Vector3d localVelocity=new Vector3d(angular).cross(anchorWorld).add(velocity);
  double normal=Math.min(MASS*GRAVITY, suspensionForce(compression, localVelocity.dot(up), spring, damper));
  return new WheelState(anchorWorld,up,true,normal,point);
 }

 /** Pure passive settling: zero drive/brake/steer, only each wheel's own vertical suspension
  * impulse - same structure as the settling convergence checks elsewhere in this suite, trimmed
  * to the minimum needed to isolate ONE geometric change. Returns final roll angle (degrees,
  * from the world "right" vector's y-component - zero at zero roll) and peak |angular.z| (roll
  * rate) reached. */
 private static double[] runPassive(Vector3d[] offsets, int ticks) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0), angular=new Vector3d(0,0,0);
  Quaterniond orientation=new Quaterniond();
  double bodyPositionY=EQUILIBRIUM_BODY_POSITION_Y;
  double spring=suspensionSpring(4), damper=suspensionDamper(spring,4);
  double peakRollRate=0;
  for (int t=0;t<ticks;t++) {
   Quaterniond inverse=new Quaterniond(orientation).conjugate();
   Vector3d velocity0=new Vector3d(velocity), angular0=new Vector3d(angular);
   for (int i=0;i<4;i++) {
    WheelState w=raycastWheel(offsets,i,bodyPositionY,orientation,velocity0,angular0,spring,damper);
    if (!w.hit()) continue;
    Vector3d vi=new Vector3d(w.up()).mul(w.normal()*DT);
    velocity.add(new Vector3d(vi).div(MASS));
    Vector3d tb=inverse.transform(new Vector3d(w.point()).cross(vi));
    angular.add(orientation.transform(new Vector3d(tb.x/inertiaBody.x,tb.y/inertiaBody.y,tb.z/inertiaBody.z)));
   }
   velocity.add(new Vector3d(0,-GRAVITY*DT,0));
   bodyPositionY += velocity.y*DT;
   orientation.integrate(DT, angular.x, angular.y, angular.z);
   peakRollRate=Math.max(peakRollRate, Math.abs(angular.z));
  }
  Vector3d rightNow=orientation.transform(new Vector3d(1,0,0));
  double rollDegrees=Math.toDegrees(Math.atan2(rightNow.y, rightNow.x));
  return new double[]{rollDegrees, peakRollRate};
 }

 @Test void realAsymmetricRearTrackSettlesToANonzeroRollAngleWhileTheMirroredCounterfactualDoesNot() {
  double[] real = runPassive(REAL_OFFSETS, 600);
  double[] symmetric = runPassive(SYMMETRIC_REAR_OFFSETS, 600);
  System.out.println("[RearTrackAsymmetryRollTest] real rear track (x=+0.35/-0.375): rollDegrees="+real[0]+" peakRollRate="+real[1]+"rad/s");
  System.out.println("[RearTrackAsymmetryRollTest] mirrored-symmetric rear track (x=+-0.3625): rollDegrees="+symmetric[0]+" peakRollRate="+symmetric[1]+"rad/s");
  assertTrue(Math.abs(symmetric[0]) < 0.01, "a genuinely mirrored rear track must settle to (numerically) zero roll under pure passive settling - this is the control, not the finding");
  assertTrue(Math.abs(real[0]) > Math.abs(symmetric[0]), "the real, non-mirrored rear track must settle to a LARGER-magnitude passive roll angle than its mirrored counterfactual, purely from this one geometric difference, with zero drive/brake/steer input");
 }
}
