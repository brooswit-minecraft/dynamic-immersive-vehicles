package io.github.brooswitminecraft.div.experimental;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Priority analysis requested in ticket comment 53069: a driven-wheelie counterfactual on the
 * UNCHANGED real geometry - specifically, whether the real vehicle's configured drive force,
 * mass, wheelbase and CG height put it past the classical static wheelie (front-axle-unloading)
 * threshold on their own, independent of any solver detail. This is a pure physical calculation
 * from the real chassisReceipt/wheel values (ticket comment 52984/52985), not a simulation - the
 * "same controls with drive=0" diagnostic case is handled trivially (zero drive force is always
 * below any positive threshold) rather than simulated.
 *
 * <p>Static front-axle-unload threshold (standard longitudinal load-transfer result for a rigid
 * body on 4 wheels, ignoring suspension dynamics): the front axle's normal force reaches zero,
 * and the vehicle is at the edge of a wheelie, when the pitching moment the drive force applies
 * about the rear axle's ground contact line equals the restoring moment from weight:
 * {@code driveForce * cgHeight == mass*gravity*rearAxleToCgHorizontalDistance}, i.e.
 * {@code thresholdDriveForce = mass*gravity*rearAxleToCgHorizontalDistance/cgHeight}.
 *
 * <p><b>Correction (ticket comment 53072):</b> review caught a geometry substitution error - the
 * real ray distance from anchor to ground is {@code wheelRadius+suspensionTravel-compression}
 * (the full ray length minus how much of it the hit consumed), NOT {@code wheelRadius+compression}
 * as this originally used. Also, tick0's compression (0.05) is a falling/transient value, not
 * static equilibrium - the equilibrium compression (where spring force exactly balances each
 * wheel's share of weight, by the suspensionSpring formula's own construction) is
 * {@code suspensionTravel/2 = 0.15}. Corrected CG height below uses the right formula and the
 * equilibrium compression, matching review's own figure (~0.8036, not the original ~0.7036).
 *
 * <p><b>Also per review: this static/scalar result must NOT be used to conclude drive tuning is
 * the root cause.</b> Real tire force is traction-limited (roughly weight*mu near rest) and
 * load-dependent, not simply "commanded force vs a static threshold" - a trivial
 * 0&lt;positive-threshold assertion is not the requested dynamic counterfactual. This class is
 * kept only as a corrected, disclosed background calculation; see
 * {@link DriveOnOffPitchCounterfactualTest} for the actual dynamic drive-on/off analysis review
 * asked for, which tracks realized (not commanded) impulse and front/rear support unloading
 * over time against the real captured pitch onset.
 */
class WheelieStaticThresholdTest {
 private static final double MASS=350.0, GRAVITY=11.0, WHEEL_RADIUS=0.375, SUSPENSION_TRAVEL=0.3, MAX_DRIVE_FORCE=8000.0;
 // Real chassisReceipt/wheel values (ticket comment 52984/52985).
 private static final double CENTER_Y=0.553593748062849;
 private static final double REAR_Z=-0.0625, FRONT_Z=1.25, CG_Z=0.625; // LOCAL_OFFSETS z-values + chassisReceipt center.z
 private static final double EQUILIBRIUM_COMPRESSION=SUSPENSION_TRAVEL/2; // 0.15 - static rest, not the tick0 transient (0.05)

 private static final double ANCHOR_Y=0.125+SUSPENSION_TRAVEL*.5; // real localOffset.y + travel*.5, before subtracting center

 @Test void correctedCgHeightMatchesReviewsFigure() {
  double groundHeightRelativeToOrigin=ANCHOR_Y-(WHEEL_RADIUS+SUSPENSION_TRAVEL-EQUILIBRIUM_COMPRESSION);
  double cgHeightAboveGround=CENTER_Y-groundHeightRelativeToOrigin;
  System.out.println("[WheelieStaticThresholdTest] corrected cgHeightAboveGround="+cgHeightAboveGround+" (review's figure: ~0.80359)");
  assertEquals(0.803593748062849, cgHeightAboveGround, 1e-9);
 }

 @Test void staticThresholdIsBelowMaxDriveButNotUsedToConcludeRootCause() {
  double groundHeightRelativeToOrigin=ANCHOR_Y-(WHEEL_RADIUS+SUSPENSION_TRAVEL-EQUILIBRIUM_COMPRESSION);
  double cgHeightAboveGround=CENTER_Y-groundHeightRelativeToOrigin;
  double rearAxleToCgHorizontal=CG_Z-REAR_Z;
  double thresholdDriveForce=MASS*GRAVITY*rearAxleToCgHorizontal/cgHeightAboveGround;
  System.out.println("[WheelieStaticThresholdTest] corrected staticWheelieThresholdDriveForce="+thresholdDriveForce
    +" N, configured max drive="+MAX_DRIVE_FORCE+" N (ratio "+(MAX_DRIVE_FORCE/thresholdDriveForce)+"x) - "
    +"background context only, NOT a root-cause conclusion (see class doc)");
  assertTrue(thresholdDriveForce > 0 && thresholdDriveForce < MAX_DRIVE_FORCE);
 }
}
