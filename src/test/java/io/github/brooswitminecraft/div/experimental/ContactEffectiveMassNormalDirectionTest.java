package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Review (comment 53212) correction to 53211's proposed next step: do NOT borrow {@code
 * ServerSubLevel}'s aggregate mass tracker for DIV's own box - "reachability does not make it
 * the same rigid body". The correct operator for a contact's effective mass already exists and
 * is verified: {@code MultiContactFrictionSolver.contactEffectiveMass} (the same {@code 1/m_eff =
 * 1/m + (r x n)*I^-1*(r x n)} formula review specified), already generalized beyond lateral/
 * longitudinal (comment 53034). This confirms it is directly reusable for the UP/normal
 * direction too - a NEW use case for this exact function, not new math.
 *
 * <p>This is the smallest safe first step only - NOT the bounded implementation+validation
 * review asked for (static-sag-vs-dynamic-damping separation, coupled-contact/timestep
 * stability, rest sag/pitch/roll/no-wobble comparison in the real harness at identical controls,
 * rear-only support, unloading/recontact, rotated poses, drive/brake/steer, isolated from the
 * unreviewed native-contact commit) - that remains unstarted and is explicitly NOT claimed done
 * here.
 */
class ContactEffectiveMassNormalDirectionTest {
 private static final double MASS=350.0;
 private static final Vector3d INERTIA_BODY=new Vector3d(
   MASS/3*(0.4410937510430813*0.4410937510430813+1.050000011920929*1.050000011920929),
   MASS/3*(0.42500001192092896*0.42500001192092896+1.050000011920929*1.050000011920929),
   MASS/3*(0.42500001192092896*0.42500001192092896+0.4410937510430813*0.4410937510430813));
 private static final Quaterniond IDENTITY_INVERSE=new Quaterniond();
 private static final Vector3d UP=new Vector3d(0,1,0);

 @Test void effectiveNormalMassAtTheComReducesToTheFullBodyMass() {
  double effective=MultiContactFrictionSolver.contactEffectiveMass(MASS, new Vector3d(0,0,0), UP, IDENTITY_INVERSE, INERTIA_BODY);
  assertEquals(MASS, effective, 1e-6, "a contact exactly at the COM has no lever arm, so its effective normal mass must equal the full body mass, not a fraction of it");
 }

 @Test void effectiveNormalMassAtARealWheelAnchorIsLessThanTheFullBodyMassButMoreThanMassOverWheelCount() {
  // Real ATV front-right wheel anchor (ticket's own established LOCAL_OFFSETS, relative to
  // CENTER) - an off-center contact must reduce effective mass below the full body mass (some
  // of an applied vertical impulse here goes into rotation, not pure translation), but the
  // naive mass/4 is not claimed to be exactly right either - just checking the direction and
  // bound this operator actually produces, not asserting a specific replacement value yet.
  Vector3d frontRightAnchor=new Vector3d(0.375,0.125+0.15,1.25).sub(new Vector3d(0,0.553593748062849,0.625));
  double effective=MultiContactFrictionSolver.contactEffectiveMass(MASS, frontRightAnchor, UP, IDENTITY_INVERSE, INERTIA_BODY);
  assertTrue(effective < MASS, "an off-center contact's effective normal mass must be less than the full body mass - got "+effective);
  assertTrue(effective > 0, "effective mass must stay positive");
  System.out.println("[ContactEffectiveMassNormalDirectionTest] front-right wheel effective normal mass="+effective+" (full mass="+MASS+", naive mass/4="+(MASS/4)+")");
 }

 @Test void effectiveNormalMassIsSymmetricForMirroredContactPoints() {
  Vector3d right=new Vector3d(0.375,0.125,1.25).sub(new Vector3d(0,0.553593748062849,0.625));
  Vector3d left=new Vector3d(-0.375,0.125,1.25).sub(new Vector3d(0,0.553593748062849,0.625));
  double effectiveRight=MultiContactFrictionSolver.contactEffectiveMass(MASS, right, UP, IDENTITY_INVERSE, INERTIA_BODY);
  double effectiveLeft=MultiContactFrictionSolver.contactEffectiveMass(MASS, left, UP, IDENTITY_INVERSE, INERTIA_BODY);
  assertEquals(effectiveRight, effectiveLeft, 1e-9, "mirrored contact points must produce the same effective normal mass - no left/right bias from this operator itself");
 }
}
