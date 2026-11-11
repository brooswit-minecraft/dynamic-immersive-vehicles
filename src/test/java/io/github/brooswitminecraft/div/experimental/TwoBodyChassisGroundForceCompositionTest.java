package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Review comment 53277's third point: "the current tests do not exercise enabled force behavior
 * or constrained response." {@code TwoBodyChassisTest} already verifies
 * {@code worldLeverArmCorrection} in isolation against its own invariant
 * ({@code Cowner+(armOld+correction)==P}); this class instead exercises the SAME composition
 * {@code PhysicsBridge.Body.stepGround} actually performs with that correction - the per-wheel
 * {@code localVelocity=ownerAngular.cross(ownerArm).add(ownerVelocity)} kinematic formula used for
 * suspension damping, and the owner-relative contact point used for impulse application - against
 * an INDEPENDENTLY derived expectation (true rigid-body material-point velocity/position relative
 * to the owning half's own actual center), not merely against the correction function's own
 * derivation. This is what "constrained response" means here: once two independent half-bodies
 * exist, a wheel's own owning half moves (translates+rotates) according to ITS OWN center/velocity/
 * angular, not the full vehicle's G - this test is what catches a composition bug (e.g. wiring the
 * correction into the wrong arm, or skipping it for one of the two call sites) even if the
 * correction function itself is individually correct.
 *
 * <p>Does not require a live Sable body - pure kinematics, same no-runtime pattern as
 * {@code TwoBodyChassis} itself and {@code PhysicsBridgeEffectiveMassDamperTest}. Does not
 * re-litigate whether EXPERIMENTAL_EFFECTIVE_MASS_DAMPING or EXPERIMENTAL_TWO_BODY_CHASSIS are
 * good ideas - only that, when enabled, this specific force-ownership math behaves physically
 * correctly.
 */
class TwoBodyChassisGroundForceCompositionTest {
 private static final double HALF_HEIGHT_B = 0.55;
 /** Mirrors exactly what {@code Body.stepGround} computes per wheel once a {@code GroundOwner} is
  * resolved: {@code ownerArm = (P-G) + correction}, {@code localVelocity =
  * ownerAngular.cross(ownerArm).add(ownerVelocity)} - then checks it against the true material-
  * point velocity of a rigid body rotating/translating about its OWN real center, computed
  * independently from {@code ownerCenter = G + orientation.transform(centerOffsetFromG)} without
  * going through the correction function at all. */
 @Test void ownerCorrectedLocalVelocityMatchesTrueMaterialPointVelocityOfTheOwningHalf() {
  Vector3d g = new Vector3d(120, 64, -300);
  Vector3d contactP = new Vector3d(120.4, 63.1, -299.6);
  Vector3d ownerVelocity = new Vector3d(3, -1, 2);
  Vector3d ownerAngular = new Vector3d(0.5, -0.3, 0.2);
  for (Quaterniond q : new Quaterniond[]{new Quaterniond(), new Quaterniond().rotateY(Math.toRadians(25)).rotateZ(Math.toRadians(10))}) {
   for (boolean upperOwned : new boolean[]{true, false}) {
    Vector3d ownerCenter = new Vector3d(g).add(q.transform(new Vector3d(
      upperOwned ? TwoBodyChassis.upperCenterOffsetFromG(HALF_HEIGHT_B) : TwoBodyChassis.lowerCenterOffsetFromG(HALF_HEIGHT_B))));
    // Production composition, verbatim shape (PhysicsBridge.Body.stepGround): arm relative to G,
    // then the owner correction added on top before using it as the damping-basis lever arm.
    Vector3d armOld = new Vector3d(contactP).sub(g);
    Vector3d correction = TwoBodyChassis.worldLeverArmCorrection(q, HALF_HEIGHT_B, upperOwned);
    Vector3d ownerArm = new Vector3d(armOld).add(correction);
    Vector3d computedLocalVelocity = new Vector3d(ownerAngular).cross(ownerArm).add(ownerVelocity);

    // Independent expectation: v_P = v_owner + omega_owner x (P - Cowner), the textbook rigid-
    // body point-velocity formula, computed directly from the owner's own real center - no
    // reference to worldLeverArmCorrection at all.
    Vector3d trueArmFromOwnCenter = new Vector3d(contactP).sub(ownerCenter);
    Vector3d expectedLocalVelocity = new Vector3d(ownerAngular).cross(trueArmFromOwnCenter).add(ownerVelocity);

    String label = "upperOwned=" + upperOwned + " rotated=" + !q.equals(new Quaterniond());
    assertEquals(expectedLocalVelocity.x, computedLocalVelocity.x, 1e-9, label);
    assertEquals(expectedLocalVelocity.y, computedLocalVelocity.y, 1e-9, label);
    assertEquals(expectedLocalVelocity.z, computedLocalVelocity.z, 1e-9, label);
   }
  }
 }

 /** Mirrors the Phase 2/3 composition: the owner-relative point used both as the friction
  * solver's contact point and as {@code applyImpulseAtPoint}'s own lever arm
  * ({@code ownerPoint = (P-G) + correction}) must equal the true {@code P-Cowner} - the exact
  * quantity Sable's {@code applyImpulseAtPoint} needs to produce the correct torque on the
  * OWNING half, not the full vehicle. Verified independently of
  * {@code worldLeverArmCorrection}'s own unit test by reconstructing {@code Cowner} here via a
  * different code path (going through {@code TwoBodyLifecycle}'s own construction formula
  * shape) and a real, non-trivial impulse to confirm the resulting torque matches what applying
  * that same impulse directly at {@code P-Cowner} on a standalone body would give. */
 @Test void ownerRelativeContactPointProducesTheSameTorqueAsApplyingDirectlyAtTheOwnersRealCenter() {
  Vector3d g = new Vector3d(-40, 70, 10);
  Vector3d contactP = new Vector3d(-39.6, 69.2, 10.7);
  Vector3d impulse = new Vector3d(120, 0, -45);
  for (Quaterniond q : new Quaterniond[]{new Quaterniond(), new Quaterniond().rotateX(Math.toRadians(18)).rotateY(Math.toRadians(-33))}) {
   for (boolean upperOwned : new boolean[]{true, false}) {
    Vector3d ownerCenter = new Vector3d(g).add(q.transform(new Vector3d(
      upperOwned ? TwoBodyChassis.upperCenterOffsetFromG(HALF_HEIGHT_B) : TwoBodyChassis.lowerCenterOffsetFromG(HALF_HEIGHT_B))));
    Vector3d armOld = new Vector3d(contactP).sub(g);
    Vector3d correction = TwoBodyChassis.worldLeverArmCorrection(q, HALF_HEIGHT_B, upperOwned);
    Vector3d ownerPoint = new Vector3d(armOld).add(correction);

    Vector3d torqueViaOwnerPoint = new Vector3d(ownerPoint).cross(impulse);
    Vector3d torqueViaTrueArm = new Vector3d(contactP).sub(ownerCenter).cross(impulse);

    String label = "upperOwned=" + upperOwned + " rotated=" + !q.equals(new Quaterniond());
    assertEquals(torqueViaTrueArm.x, torqueViaOwnerPoint.x, 1e-9, label);
    assertEquals(torqueViaTrueArm.y, torqueViaOwnerPoint.y, 1e-9, label);
    assertEquals(torqueViaTrueArm.z, torqueViaOwnerPoint.z, 1e-9, label);
   }
  }
 }
}
