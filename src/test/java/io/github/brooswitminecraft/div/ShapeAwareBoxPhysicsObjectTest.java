package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.companion.math.Pose3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547 (John's comments 54403/54407/54411): demonstrates the actual before/after
 * contrast directly on the real Sable classes BuoyantBody constructs, now that the moddev
 * plugin's own official {@code unitTest} support (build.gradle) wires real Minecraft/NeoForge
 * classes onto the test classpath - the same narrow, previously-established pattern this
 * project's own history already used once for MINECRAFT-546's TuningCommandRegistrationTest.
 * Still does NOT touch the native physics pipeline (no {@code onAddition}/{@code addBox} call
 * here, no dedicated server) - {@code getMassTracker()} is a plain Java method, callable on a
 * bare, never-added object.
 */
class ShapeAwareBoxPhysicsObjectTest {
 private static final Pose3d POSE = new Pose3d(new Vector3d(), new Quaterniond(), new Vector3d(), new Vector3d(1));
 private static final double MASS = 400.0;
 private static final Vector3d HALF_EXTENTS = new Vector3d(0.6, 0.3, 1.0); // this investigation's own fixture hull

 @Test void baselinePlainBoxPhysicsObjectIgnoresShape() {
  // Characterizes Sable's OWN pinned-jar legacy behavior (not DIV's code) - the bug this fix
  // works around: BEFORE this fix, BuoyantBody constructed exactly this class, so this is the
  // actual inertia the native solver was being told to use.
  var plain = new BoxPhysicsObject(POSE, HALF_EXTENTS, MASS);
  var tensor = plain.getMassTracker().getInertiaTensor();
  double fallback = MASS / 6.0;
  assertEquals(fallback, tensor.m00(), 1e-9);
  assertEquals(fallback, tensor.m11(), 1e-9);
  assertEquals(fallback, tensor.m22(), 1e-9);
  // Confirms the bug's own magnitude: the real X/Y inertia (145.33/181.33) is more than DOUBLE
  // this fallback - if BuoyantBody still used this plain class, the assertions in
  // fixedCorrectlyReportsShapeAwareInertia below would FAIL against it.
  assertTrue(145.0 > fallback * 2, "fallback must be less than half the real X inertia for this hull");
 }

 @Test void fixedCorrectlyReportsShapeAwareInertia() {
  // This is what BuoyantBody now actually constructs (see its own constructor) - the fix.
  var fixed = new ShapeAwareBoxPhysicsObject(POSE, HALF_EXTENTS, MASS);
  var tensor = fixed.getMassTracker().getInertiaTensor();
  assertEquals(MASS / 3.0 * (0.3 * 0.3 + 1.0 * 1.0), tensor.m00(), 1e-9); // pitch
  assertEquals(MASS / 3.0 * (0.6 * 0.6 + 1.0 * 1.0), tensor.m11(), 1e-9); // yaw
  assertEquals(MASS / 3.0 * (0.6 * 0.6 + 0.3 * 0.3), tensor.m22(), 1e-9); // roll
  // Would FAIL against the plain class's own mass/6 fallback (66.67) - this is the actual
  // failing-before/passing-after contrast: same inputs, different (now correct) result.
  assertNotEquals(MASS / 6.0, tensor.m00(), 1.0);
  assertNotEquals(MASS / 6.0, tensor.m11(), 1.0);
 }

 @Test void fixedIsActuallyDispatchedThroughPhysicsPipelineBodyNotJustItsOwnReference() {
  // 54411's own concern: does virtual dispatch to the OVERRIDDEN getMassTracker() actually
  // happen when a caller holds this object only through the PUBLIC interface type the native
  // consumer path itself uses (PhysicsPipelineBody), not through the subclass's own static
  // type? If the override were somehow bypassed by static/interface-default dispatch, this
  // specific call - through the interface reference, exactly how onAddition's own
  // physicsSystem.getPipeline().addBox(this) sees it (this is declared as
  // PhysicsPipelineBody there) - would still return the base class's mass/6 fallback.
  dev.ryanhcode.sable.api.physics.PhysicsPipelineBody asInterface = new ShapeAwareBoxPhysicsObject(POSE, HALF_EXTENTS, MASS);
  var tensor = asInterface.getMassTracker().getInertiaTensor();
  assertEquals(MASS / 3.0 * (0.3 * 0.3 + 1.0 * 1.0), tensor.m00(), 1e-9);
  assertNotEquals(MASS / 6.0, tensor.m00(), 1.0);
 }

 @Test void fixedPreservesEverythingElseFromTheBaseClassUnchanged() {
  var fixed = new ShapeAwareBoxPhysicsObject(POSE, HALF_EXTENTS, MASS);
  assertEquals(MASS, fixed.getMass(), 1e-9);
  assertEquals(HALF_EXTENTS, new Vector3d(fixed.getHalfExtents()));
  assertEquals(POSE.position(), fixed.getPose().position());
  assertEquals(MASS, fixed.getMassTracker().getMass(), 1e-9);
  assertEquals(1.0 / MASS, fixed.getMassTracker().getInverseMass(), 1e-12);
  assertEquals(new Vector3d(), new Vector3d(fixed.getMassTracker().getCenterOfMass()));
 }

 @Test void fixedInertiaInverseIsConsistentWithTheForwardTensor() {
  var fixed = new ShapeAwareBoxPhysicsObject(POSE, HALF_EXTENTS, MASS);
  var md = fixed.getMassTracker();
  var product = new org.joml.Matrix3d(md.getInertiaTensor()).mul(new org.joml.Matrix3d(md.getInverseInertiaTensor()));
  assertEquals(1, product.m00(), 1e-9); assertEquals(1, product.m11(), 1e-9); assertEquals(1, product.m22(), 1e-9);
 }
}
