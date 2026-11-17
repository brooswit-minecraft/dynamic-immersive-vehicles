package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.companion.math.Pose3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547 (John's comments 54403/54407/54411/54413/54419): exercises the real Sable
 * classes {@code BuoyantBody} constructs, now that the moddev plugin's own official
 * {@code unitTest} support (build.gradle) wires real Minecraft/NeoForge classes onto the test
 * classpath - the same narrow, previously-established pattern this project's own history
 * already used once for MINECRAFT-546's TuningCommandRegistrationTest.
 *
 * <p><b>Corrected scope (54413/54419):</b> this is JAVA-SIDE METADATA characterization only.
 * {@code getMassTracker()} is never consulted by the actual pinned native body-creation path
 * ({@code Java_..._Rapier3D_createBox} takes raw mass/half-extents directly and lets Rapier
 * compute its own internal mass properties - confirmed by reading the pinned native source,
 * see VERIFICATION.md) - so nothing here demonstrates, or ever demonstrated, a native-inertia
 * or hitch-crash correction. These tests show only what a JAVA caller observes from
 * {@code getMassTracker()} before and after {@code ShapeAwareBoxPhysicsObject} exists - still
 * does NOT touch the native physics pipeline (no {@code onAddition}/{@code addBox} call here,
 * no dedicated server) - {@code getMassTracker()} is a plain Java method, callable on a bare,
 * never-added object.
 */
class ShapeAwareBoxPhysicsObjectTest {
 private static final Pose3d POSE = new Pose3d(new Vector3d(), new Quaterniond(), new Vector3d(), new Vector3d(1));
 private static final double MASS = 400.0;
 private static final Vector3d HALF_EXTENTS = new Vector3d(0.6, 0.3, 1.0); // this investigation's own fixture hull

 @Test void baselinePlainBoxPhysicsObjectIgnoresShape() {
  // Characterizes Sable's OWN pinned-jar legacy JAVA METADATA behavior (not DIV's code, and
  // NOT the native rigid body's own real inertia - confirmed via the pinned native source that
  // createBox never reads this value at all). This is what a Java caller reading
  // getMassTracker() observes, nothing more.
  var plain = new BoxPhysicsObject(POSE, HALF_EXTENTS, MASS);
  var tensor = plain.getMassTracker().getInertiaTensor();
  double fallback = MASS / 6.0;
  assertEquals(fallback, tensor.m00(), 1e-9);
  assertEquals(fallback, tensor.m11(), 1e-9);
  assertEquals(fallback, tensor.m22(), 1e-9);
  // Quantifies the Java-metadata discrepancy's own magnitude (not a native-behavior claim):
  // the solid-box formula's X/Y values (145.33/181.33) are more than DOUBLE this fallback.
  assertTrue(145.0 > fallback * 2, "fallback must be less than half the solid-box X value for this hull");
 }

 @Test void fixedCorrectlyReportsShapeAwareInertia() {
  // This is what BuoyantBody now actually constructs (see its own constructor) - a Java-side
  // metadata change only (confirmed NOT consulted by native body creation - see this class's
  // own doc comment and VERIFICATION.md). Not a native-behavior or hitch-crash correction.
  var fixed = new ShapeAwareBoxPhysicsObject(POSE, HALF_EXTENTS, MASS);
  var tensor = fixed.getMassTracker().getInertiaTensor();
  assertEquals(MASS / 3.0 * (0.3 * 0.3 + 1.0 * 1.0), tensor.m00(), 1e-9); // pitch
  assertEquals(MASS / 3.0 * (0.6 * 0.6 + 1.0 * 1.0), tensor.m11(), 1e-9); // yaw
  assertEquals(MASS / 3.0 * (0.6 * 0.6 + 0.3 * 0.3), tensor.m22(), 1e-9); // roll
  // Differs from the plain class's own mass/6 fallback (66.67) - a real, measured difference
  // in what getMassTracker() itself returns, at the Java level only.
  assertNotEquals(MASS / 6.0, tensor.m00(), 1.0);
  assertNotEquals(MASS / 6.0, tensor.m11(), 1.0);
 }

 @Test void fixedIsActuallyDispatchedThroughPhysicsPipelineBodyNotJustItsOwnReference() {
  // 54411's own concern: does ordinary Java virtual dispatch to the OVERRIDDEN
  // getMassTracker() still resolve correctly when a caller holds this object only through the
  // PUBLIC interface type getMassTracker() is declared on (PhysicsPipelineBody), not through
  // the subclass's own static type? Corrected (54413/54419): this is a plain Java-semantics
  // check, not a claim about what any native-facing call site's own static type actually is -
  // checked via bytecode, onAddition's own `addBox` call is declared to take a
  // BoxPhysicsObject, not this interface. Dispatch through BoxPhysicsObject-typed references
  // was never in question here; this test only confirms the interface-typed case too.
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
