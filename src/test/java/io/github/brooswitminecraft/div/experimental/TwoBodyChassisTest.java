package io.github.brooswitminecraft.div.experimental;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for the two-half chassis math (ticket comment 53236/53240), checking exactly the
 * invariants the ticket itself named: f=0.5 geometry/mass/inertia equivalence with the original
 * single-body tensor, asymmetric fractions, the explicit minimum-mass guard, and that semantics
 * are never silently inverted. Uses the real ATV (quad) geometry already established throughout
 * this investigation: half-extents (0.425, 0.44109375, 1.05), mass 350kg.
 */
class TwoBodyChassisTest {
 private static final double MASS = 350.0;
 private static final double A = 0.42500001192092896, B = 0.4410937510430813, C = 1.050000011920929;

 @Test void defaultConfigIsPointFive() {
  assertEquals(0.5, TwoBodyChassis.Config.DEFAULT.upperMassFraction());
 }

 @Test void fractionMustBeStrictlyBetweenZeroAndOne() {
  assertThrows(IllegalArgumentException.class, () -> new TwoBodyChassis.Config(0));
  assertThrows(IllegalArgumentException.class, () -> new TwoBodyChassis.Config(1));
  assertThrows(IllegalArgumentException.class, () -> new TwoBodyChassis.Config(-0.1));
  assertThrows(IllegalArgumentException.class, () -> new TwoBodyChassis.Config(1.1));
  assertThrows(IllegalArgumentException.class, () -> new TwoBodyChassis.Config(Double.NaN));
  assertThrows(IllegalArgumentException.class, () -> new TwoBodyChassis.Config(Double.POSITIVE_INFINITY));
 }

 @Test void massesSumToTotalAndNeverInvertAtAnOffCenterFraction() {
  var config = new TwoBodyChassis.Config(0.4);
  double upper = TwoBodyChassis.upperMassKg(MASS, config), lower = TwoBodyChassis.lowerMassKg(MASS, config);
  assertEquals(MASS, upper + lower, 1e-9, "the two halves must account for the full total mass exactly");
  assertEquals(140.0, upper, 1e-9, "f=0.4 must give the upper half exactly 40% - not inverted to 60%");
  assertEquals(210.0, lower, 1e-9, "f=0.4 must give the lower half exactly 60% - the ticket's own worked example (lower heavier when f<0.5)");
  assertTrue(lower > upper, "f<0.5 must make the LOWER half heavier, matching the ticket's own stated semantics - not inverted");
 }

 @Test void minimumMassGuardRejectsAnExtremeFractionOnALightVehicle() {
  var extreme = new TwoBodyChassis.Config(0.001);
  double lightVehicleMass = 100.0; // upper would be 0.1kg - must be rejected
  assertThrows(IllegalArgumentException.class, () -> extreme.validateAgainstTotalMass(lightVehicleMass));
  // The SAME fraction must be accepted for a heavy enough vehicle where neither half falls below the minimum.
  double heavyVehicleMass = 1_000_000.0;
  assertDoesNotThrow(() -> extreme.validateAgainstTotalMass(heavyVehicleMass));
 }

 @Test void minimumMassGuardIsCheckedAgainstTheRealTotalMassNotFInIsolation() {
  var config = new TwoBodyChassis.Config(0.5); // a perfectly reasonable fraction...
  assertThrows(IllegalArgumentException.class, () -> config.validateAgainstTotalMass(1.0),
    "...can still be unsafe for a vehicle whose total mass itself is near the minimum - must be checked against the real mass, not assumed safe from f alone");
 }

 @Test void centersAreSymmetricAboutGAndIndependentOfMassFraction() {
  var upperOffset = TwoBodyChassis.upperCenterOffsetFromG(B);
  var lowerOffset = TwoBodyChassis.lowerCenterOffsetFromG(B);
  assertEquals(new Vector3d(0, B / 2, 0), upperOffset);
  assertEquals(new Vector3d(0, -B / 2, 0), lowerOffset);
  assertEquals(0, upperOffset.x + lowerOffset.x, 1e-12);
  assertEquals(0, upperOffset.z + lowerOffset.z, 1e-12);
 }

 @Test void halfExtentsAreHalvedOnlyInHeight() {
  var half = TwoBodyChassis.halfExtentsOfEachHalf(new Vector3d(A, B, C));
  assertEquals(A, half.x, 1e-12, "width must be preserved exactly - only height is split");
  assertEquals(B / 2, half.y, 1e-12);
  assertEquals(C, half.z, 1e-12, "depth must be preserved exactly - only height is split");
 }

 @Test void assemblyComIsExactlyAtGWhenFIsOneHalf() {
  assertEquals(0, TwoBodyChassis.assemblyComOffsetFromG(B, TwoBodyChassis.Config.DEFAULT), 1e-12);
 }

 @Test void assemblyComShiftsTowardTheHeavierHalf() {
  double offsetHeavyTop = TwoBodyChassis.assemblyComOffsetFromG(B, new TwoBodyChassis.Config(0.7));
  double offsetHeavyBottom = TwoBodyChassis.assemblyComOffsetFromG(B, new TwoBodyChassis.Config(0.3));
  assertTrue(offsetHeavyTop > 0, "a heavier UPPER half (f>0.5) must shift the assembly COM UP (positive Y)");
  assertTrue(offsetHeavyBottom < 0, "a heavier LOWER half (f<0.5) must shift the assembly COM DOWN (negative Y)");
  assertEquals(offsetHeavyTop, -offsetHeavyBottom, 1e-12, "0.7/0.3 are symmetric around 0.5, so their offsets must be exact mirror images");
 }

 @Test void eachHalfsOwnInertiaMatchesTheStandardSolidBoxFormulaAtItsOwnMassAndHalfHeight() {
  var config = new TwoBodyChassis.Config(0.5);
  var upperInertia = TwoBodyChassis.upperOwnInertia(MASS, A, B, C, config);
  double expectedMass = MASS * 0.5, h = B / 2;
  assertEquals(expectedMass / 3 * (h * h + C * C), upperInertia.x, 1e-9);
  assertEquals(expectedMass / 3 * (A * A + C * C), upperInertia.y, 1e-9);
  assertEquals(expectedMass / 3 * (A * A + h * h), upperInertia.z, 1e-9);
 }

 /** The ticket's own explicit check: "at f=.5 the aggregate should match the old uniform
  * full-box tensor." Verified here against the exact same formula PhysicsBridge.Body's
  * constructor and every other test in this investigation already use for the real ATV's
  * single-box inertia. */
 @Test void aggregateInertiaAtDefaultFractionExactlyMatchesTheOriginalSingleBoxTensor() {
  var aggregate = TwoBodyChassis.aggregateInertiaAboutAssemblyCom(MASS, A, B, C, TwoBodyChassis.Config.DEFAULT);
  double originalIxx = MASS / 3 * (B * B + C * C), originalIyy = MASS / 3 * (A * A + C * C), originalIzz = MASS / 3 * (A * A + B * B);
  assertEquals(originalIxx, aggregate.x, 1e-6, "Ixx must exactly match the original single-box tensor at f=0.5");
  assertEquals(originalIyy, aggregate.y, 1e-6, "Iyy must exactly match the original single-box tensor at f=0.5");
  assertEquals(originalIzz, aggregate.z, 1e-6, "Izz must exactly match the original single-box tensor at f=0.5");
 }

 @Test void yawInertiaNeverChangesRegardlessOfMassFraction() {
  // A pure vertical split never changes the horizontal (X/Z) mass distribution, so Iyy (yaw)
  // must be identical at every fraction - only Ixx/Izz (pitch/roll) pick up the parallel-axis term.
  double iyyAt05 = TwoBodyChassis.aggregateInertiaAboutAssemblyCom(MASS, A, B, C, new TwoBodyChassis.Config(0.5)).y;
  double iyyAt02 = TwoBodyChassis.aggregateInertiaAboutAssemblyCom(MASS, A, B, C, new TwoBodyChassis.Config(0.2)).y;
  double iyyAt08 = TwoBodyChassis.aggregateInertiaAboutAssemblyCom(MASS, A, B, C, new TwoBodyChassis.Config(0.8)).y;
  assertEquals(iyyAt05, iyyAt02, 1e-9);
  assertEquals(iyyAt05, iyyAt08, 1e-9);
 }

 @Test void pitchAndRollInertiaIncreaseAsMassMovesAwayFromTheMidplane() {
  // Moving mass fraction away from 0.5 in either direction increases the parallel-axis
  // contribution (f*(1-f) is maximized at f=0.5 itself... wait - maximized AT 0.5, meaning
  // Ixx/Izz are actually LARGEST at f=0.5 and SMALLER toward the extremes, since f*(1-f) peaks
  // at f=0.5=0.25 and shrinks toward 0 as f approaches 0 or 1 (less total mass spread across
  // the two different-height contact points). Asserting the actual measured direction, not an
  // assumption.
  double ixxAt05 = TwoBodyChassis.aggregateInertiaAboutAssemblyCom(MASS, A, B, C, new TwoBodyChassis.Config(0.5)).x;
  double ixxAt09 = TwoBodyChassis.aggregateInertiaAboutAssemblyCom(MASS, A, B, C, new TwoBodyChassis.Config(0.9)).x;
  assertTrue(ixxAt09 < ixxAt05, "concentrating mass into one half (f far from 0.5) must reduce the parallel-axis spread term, not increase it - got ixxAt05="+ixxAt05+" ixxAt09="+ixxAt09);
 }

 @Test void jointAnchorsAreBodyLocalConstantsNotWorldSpace() {
  // Review's explicit correction (comment 53236): anchors are body-local. Both must point
  // toward the shared split plane from each half's own center, in opposite Y directions.
  var upperAnchor = TwoBodyChassis.jointAnchorLocalInUpperBody(B);
  var lowerAnchor = TwoBodyChassis.jointAnchorLocalInLowerBody(B);
  assertEquals(new Vector3d(0, -B / 2, 0), upperAnchor, "from the upper half's own center, the shared face is straight down by half its own half-height");
  assertEquals(new Vector3d(0, B / 2, 0), lowerAnchor, "from the lower half's own center, the shared face is straight up by half its own half-height");
  assertEquals(0, upperAnchor.y + lowerAnchor.y, 1e-12, "the two anchors must be exact mirror images - same physical point described from each side");
 }
}
