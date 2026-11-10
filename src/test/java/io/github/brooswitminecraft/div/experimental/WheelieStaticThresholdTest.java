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
 * <p>This does not model suspension/solver dynamics at all - it is the simplest possible
 * "could this even happen on paper" check, deliberately smaller in scope than a full simulation,
 * to establish whether "overly easy wheelies" is consistent with the vehicle's own configured
 * numbers before attempting anything more elaborate (or any code change).
 */
class WheelieStaticThresholdTest {
 private static final double MASS=350.0, GRAVITY=11.0, WHEEL_RADIUS=0.375, SUSPENSION_TRAVEL=0.3, MAX_DRIVE_FORCE=8000.0;
 // Real chassisReceipt/wheel values (ticket comment 52984/52985).
 private static final double CENTER_Y=0.553593748062849;
 private static final double REAR_Z=-0.0625, FRONT_Z=1.25, CG_Z=0.625; // LOCAL_OFFSETS z-values + chassisReceipt center.z
 private static final double STATIC_COMPRESSION=0.05; // real logged tick0 compression

 @Test void realDriveForceExceedsTheStaticWheelieThreshold() {
  double cgHeightAboveGround=CENTER_Y-((0.125+SUSPENSION_TRAVEL*.5)-(WHEEL_RADIUS+STATIC_COMPRESSION));
  double rearAxleToCgHorizontal=CG_Z-REAR_Z;
  double frontAxleToCgHorizontal=FRONT_Z-CG_Z;
  double thresholdDriveForce=MASS*GRAVITY*rearAxleToCgHorizontal/cgHeightAboveGround;
  System.out.println("[WheelieStaticThresholdTest] cgHeightAboveGround="+cgHeightAboveGround
    +" rearAxleToCgHorizontal="+rearAxleToCgHorizontal+" frontAxleToCgHorizontal="+frontAxleToCgHorizontal
    +" staticWheelieThresholdDriveForce="+thresholdDriveForce+" N, configured max drive="+MAX_DRIVE_FORCE+" N"
    +" (ratio "+(MAX_DRIVE_FORCE/thresholdDriveForce)+"x)");
  assertTrue(thresholdDriveForce > 0 && thresholdDriveForce < MAX_DRIVE_FORCE,
    "the real vehicle's own configured max drive force ("+MAX_DRIVE_FORCE+" N) should already exceed "
    +"the static front-axle-unload threshold ("+thresholdDriveForce+" N) computed purely from its own "
    +"mass/wheelbase/CG height - if so, a wheelie at or near full throttle is consistent with the "
    +"vehicle's own configured numbers, not necessarily a solver/friction-model defect");
 }

 @Test void zeroDriveNeverExceedsAnyPositiveThreshold() {
  // The "same controls with drive=0" diagnostic case review asked for - trivial by construction,
  // stated explicitly rather than skipped: zero drive force cannot exceed a threshold that is a
  // positive multiple of mass*gravity*distance/height (all positive for this real geometry).
  assertTrue(0.0 < MASS*GRAVITY*(CG_Z-REAR_Z)/(CENTER_Y-((0.125+SUSPENSION_TRAVEL*.5)-(WHEEL_RADIUS+STATIC_COMPRESSION))));
 }
}
