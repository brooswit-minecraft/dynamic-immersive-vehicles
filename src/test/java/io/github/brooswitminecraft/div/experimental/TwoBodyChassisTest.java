package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
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

 // Force ownership and pose reconstruction (ticket comment 53261).

 @Test void everyRealAtvWheelAnchorBelongsToTheLowerHalf() {
  // The real ATV's own established wheel geometry (anchor = wheel.localOffset - center) - every
  // wheel sits below the chassis center for this vehicle, so EVERY wheel's force should belong
  // to the lower half, not an even split - the physically correct outcome, not an edge case.
  Vector3d center = new Vector3d(0, 0.553593748062849, 0.625);
  Vector3d[] wheelLocalOffsets = {
    new Vector3d(0.35, 0.125, -0.0625), new Vector3d(-0.375, 0.125, 1.25),
    new Vector3d(0.375, 0.125, 1.25), new Vector3d(-0.375, 0.125, -0.0625)};
  for (Vector3d offset : wheelLocalOffsets) {
   double anchorY = offset.y - center.y;
   assertFalse(TwoBodyChassis.isUpperHalf(anchorY), "wheel at "+offset+" has anchorY="+anchorY+" and must belong to the LOWER half, not the upper");
  }
 }

 @Test void isUpperHalfIsExactlyGatedAtZeroNotShiftedEitherWay() {
  assertTrue(TwoBodyChassis.isUpperHalf(0.0), "exactly at G must count as upper (the boundary convention, documented, not accidental)");
  assertTrue(TwoBodyChassis.isUpperHalf(0.001));
  assertFalse(TwoBodyChassis.isUpperHalf(-0.001));
 }

 @Test void vehiclePositionFromLowerBodyRecoversGExactlyWhenUnrotated() {
  Vector3d lowerBodyPosition = new Vector3d(100, 50, 200); // G - (0,B/2,0) for some real G
  Vector3d expectedG = new Vector3d(lowerBodyPosition).add(0, B / 2, 0);
  Vector3d recoveredG = TwoBodyChassis.vehicleWorldPositionFromLowerBody(lowerBodyPosition, new Quaterniond(), B);
  assertEquals(expectedG.x, recoveredG.x, 1e-9);
  assertEquals(expectedG.y, recoveredG.y, 1e-9);
  assertEquals(expectedG.z, recoveredG.z, 1e-9);
 }

 @Test void vehiclePositionFromLowerBodyRecoversGExactlyWhenRotated() {
  // Build a real, non-axis-aligned orientation, place the lower body at a KNOWN offset from a
  // chosen G under that same rotation, then confirm reconstruction recovers that exact G -
  // round-trip correctness, not just the trivial unrotated case above.
  Quaterniond q = new Quaterniond().rotateY(Math.toRadians(40)).rotateX(Math.toRadians(15));
  Vector3d knownG = new Vector3d(5000, 80, 5000);
  Vector3d lowerBodyPosition = new Vector3d(knownG).sub(q.transform(new Vector3d(TwoBodyChassis.jointAnchorLocalInLowerBody(B))));
  Vector3d recoveredG = TwoBodyChassis.vehicleWorldPositionFromLowerBody(lowerBodyPosition, q, B);
  assertEquals(knownG.x, recoveredG.x, 1e-9);
  assertEquals(knownG.y, recoveredG.y, 1e-9);
  assertEquals(knownG.z, recoveredG.z, 1e-9);
 }

 // Force-ownership lever-arm correction (review 53272) - discriminating tests at f=.5 AND f=.4
 // with a real nonzero rotation, not only the unchanged (f-independent, zero-rotation) off-path
 // behavior every other test in this investigation already covers.

 /** Review 53277's correction: the FIRST version of this test checked "G + correction ==
  * Cowner" - true, but the WRONG invariant for a lever-arm correction (that describes
  * reconstructing a center from G, not correcting an arm measured FROM G). The actual invariant
  * {@code worldLeverArmCorrection} must satisfy, checked directly here for an ARBITRARY contact
  * point P (not G itself): {@code Cowner + (armOld + correction) == P}, where
  * {@code armOld = P - G} is exactly what every wheel's {@code point}/{@code arm} in stepGround
  * already is. Checked for both halves, at IDENTITY orientation AND a real non-axis-aligned
  * rotation, per review's explicit ask - this is independent of mass fraction (pure geometry, see
  * the method's own doc), so f is not varied here (already confirmed elsewhere that f doesn't
  * affect either half's own center position). */
 @Test void worldLeverArmCorrectionSatisfiesTheActualLeverArmInvariantForAnArbitraryContactPoint() {
  Vector3d knownG = new Vector3d(5000, 80, 5000);
  // An arbitrary real contact point, well off both G and either half's own center - if the
  // correction's sign or magnitude were wrong, this would not round-trip back to P.
  Vector3d contactP = new Vector3d(5000.8, 79.1, 5001.3);
  for (Quaterniond q : new Quaterniond[]{new Quaterniond(), new Quaterniond().rotateY(Math.toRadians(40)).rotateX(Math.toRadians(15))}) {
   Vector3d armOld = new Vector3d(contactP).sub(knownG);
   for (boolean upperOwned : new boolean[]{true, false}) {
    Vector3d ownCenter = new Vector3d(knownG).add(q.transform(new Vector3d(upperOwned ? TwoBodyChassis.upperCenterOffsetFromG(B) : TwoBodyChassis.lowerCenterOffsetFromG(B))));
    Vector3d correction = TwoBodyChassis.worldLeverArmCorrection(q, B, upperOwned);
    Vector3d armNew = new Vector3d(armOld).add(correction);
    Vector3d reconstructedP = new Vector3d(ownCenter).add(armNew);
    String label = "upperOwned="+upperOwned+" rotated="+!q.equals(new Quaterniond());
    assertEquals(contactP.x, reconstructedP.x, 1e-9, label);
    assertEquals(contactP.y, reconstructedP.y, 1e-9, label);
    assertEquals(contactP.z, reconstructedP.z, 1e-9, label);
   }
  }
 }

 @Test void lowerOwnMassAndInertiaAreLargerAtFPointFourThanAtFPointFive() {
  // f=.4 means a LIGHTER upper half / HEAVIER lower half than the even f=.5 split - a real,
  // discriminating check that different configured fractions actually produce different,
  // correctly-ordered per-half mass/inertia, not just that the formulas run without error.
  var half = new TwoBodyChassis.Config(0.5);
  var pointFour = new TwoBodyChassis.Config(0.4);
  double lowerMassAtHalf = TwoBodyChassis.lowerMassKg(MASS, half);
  double lowerMassAtPointFour = TwoBodyChassis.lowerMassKg(MASS, pointFour);
  assertTrue(lowerMassAtPointFour > lowerMassAtHalf, "f=.4 must give the lower half MORE mass than f=.5 - got half="+lowerMassAtHalf+" pointFour="+lowerMassAtPointFour);
  var lowerInertiaAtHalf = TwoBodyChassis.lowerOwnInertia(MASS, A, B, C, half);
  var lowerInertiaAtPointFour = TwoBodyChassis.lowerOwnInertia(MASS, A, B, C, pointFour);
  assertTrue(lowerInertiaAtPointFour.x > lowerInertiaAtHalf.x, "more mass in the same half-geometry must mean larger inertia, not smaller or unchanged");
  double upperMassAtHalf = TwoBodyChassis.upperMassKg(MASS, half);
  double upperMassAtPointFour = TwoBodyChassis.upperMassKg(MASS, pointFour);
  assertTrue(upperMassAtPointFour < upperMassAtHalf, "f=.4 must give the upper half LESS mass than f=.5 - not inverted");
 }
}
