package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547, John's comments 54700/54703: tests for the UNWIRED production-integration
 * design {@link ProposedBoundedDragIntegration}. Not called from any production path.
 */
class ProposedBoundedDragIntegrationTest {
 private static final double DT = 0.05;

 @Test void aDragForceAlreadyWithinBoundIsReturnedUnchanged() {
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0); // +y, so a -y drag OPPOSES it (s<0)
  Vector3d angularVelocity = new Vector3d(0, 0, 0);
  // Opposing direction (-y) with a tiny magnitude - certainly within bound.
  Vector3d smallDrag = new Vector3d(0, -0.01, 0);

  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, smallDrag, linearVelocity, angularVelocity, DT);

  assertEquals(0, new Vector3d(smallDrag).sub(result).length(), 1e-12, "a drag force already well within bound must be returned unchanged");
 }

 @Test void aDragForceExceedingTheBoundIsScaledDownToExactlyArrestTheOpposedPointVelocityComponentNotReverseIt() {
  // 54703: a prior version capped at the ENERGY-NEUTRAL bound, which (confirmed by its own
  // test asserting exact equality) lands exactly at the reversal boundary - the opposite of the
  // "strictly interior" claim in its own doc. Fixed to cap at maxNonReversingImpulseMagnitude:
  // the resulting impulse must drive the opposed point-velocity component to EXACTLY ZERO, not
  // reverse it - confirmed here directly from the resulting velocity, not just the impulse math.
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0);
  Vector3d angularVelocity = new Vector3d(0, 0, 0);
  Vector3d largeDrag = new Vector3d(0, -2_000_000.0, 0); // deliberately far past any real bound here

  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, largeDrag, linearVelocity, angularVelocity, DT);

  assertTrue(result.length() < largeDrag.length(), "an over-bound drag force must be scaled down");
  assertTrue(result.y < 0, "direction must be preserved - still opposing +y");

  // Independently apply the RESULTING (scaled) impulse via the ledger and confirm the
  // point-velocity component along the drag direction lands at exactly zero, not reversed.
  SequentialImpulseEnergyLedger ledger = new SequentialImpulseEnergyLedger(massData, linearVelocity, angularVelocity);
  Vector3d direction = new Vector3d(largeDrag).normalize(); // points -y, same direction result itself points
  ledger.applyImpulseAndMeasureEnergyChange(arm, direction, result.length() * DT); // positive forward magnitude along that same direction
  Vector3d pointVelocityAfter = new Vector3d(ledger.angularVelocity()).cross(arm).add(new Vector3d(ledger.linearVelocity()));
  double sAfter = pointVelocityAfter.dot(direction);
  assertEquals(0, sAfter, 1e-6, "the opposed point-velocity component must land at exactly zero, never past it into reversal");
 }

 @Test void aDragForceThatAidsRatherThanOpposesThePointVelocityIsScaledEntirelyToZero() {
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0); // +y point velocity
  Vector3d angularVelocity = new Vector3d(0, 0, 0);
  Vector3d aidingDrag = new Vector3d(0, 50.0, 0); // SAME sign as the velocity - aiding, not opposing

  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, aidingDrag, linearVelocity, angularVelocity, DT);

  assertEquals(0, result.length(), 1e-12, "a drag force that aids (not opposes) the point velocity must be scaled entirely to zero");
 }

 @Test void aZeroDragForceIsReturnedUnchanged() {
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(
    massData, arm, new Vector3d(0, 0, 0), new Vector3d(43.90, 0, 0), new Vector3d(0, 0, 0), DT);
  assertEquals(0, result.length(), 1e-12);
 }

 @Test void scalingNeverProducesAMagnitudeLargerThanThePhysicalForceItself() {
  double mass = 250.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(3.0, 0.75, 1.5));
  Vector3d arm = new Vector3d(-2.2, 1.1, -0.9);
  Vector3d linearVelocity = new Vector3d(-12.0, 59.09, 20.15);
  Vector3d angularVelocity = new Vector3d(1.5, -2.0, 0.8);
  Vector3d drag = new Vector3d(0.4, 0.6, -0.69).normalize().mul(37.2);

  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, drag, linearVelocity, angularVelocity, DT);

  assertTrue(result.length() <= drag.length() + 1e-9, "the result's magnitude must never exceed the physical force's own magnitude");
 }

 @Test void anInvalidDtProducesAConservativeZeroNotNaNOrTheRawForceUnchanged() {
  // 54703: "Invalid dt (zero/negative/nonfinite) ... must have an explicit safe contract;
  // ... NaN dt can produce NaN output" - a prior version had no guard for this at all.
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0);
  Vector3d angularVelocity = new Vector3d(0, 0, 0);
  Vector3d drag = new Vector3d(0, -50.0, 0);

  for (double badDt : new double[] {0.0, -0.05, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
   Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, drag, linearVelocity, angularVelocity, badDt);
   assertTrue(result.isFinite(), "an invalid dt (" + badDt + ") must never produce a non-finite result");
   assertEquals(0, result.length(), 1e-12, "an invalid dt (" + badDt + ") has no well-defined budget - must return a conservative zero, not the raw force unchanged");
  }
 }

 @Test void aNonFiniteDragForceProducesAConservativeZeroNotReturnedUnchanged() {
  // 54703: "current NaN force can be returned unchanged" - a prior version's zero-magnitude
  // guard (`!(forceMagnitude > 0)`) let a NaN-containing force slip through as "unchanged"
  // because any comparison against NaN is false, so the "exceeds bound" check never triggered.
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0);
  Vector3d angularVelocity = new Vector3d(0, 0, 0);

  Vector3d nanDrag = new Vector3d(0, Double.NaN, 0);
  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, nanDrag, linearVelocity, angularVelocity, DT);
  assertTrue(result.isFinite(), "a non-finite drag force must never produce a non-finite result");
  assertEquals(0, result.length(), 1e-12, "a non-finite drag force has no well-defined budget - must return a conservative zero");
 }

 @Test void multipleFloatsClampedSequentiallyKeepTheirTrueCombinedEnergyChangeNonPositiveAndNoPointVelocityReverses() {
  // 54703: "test actual before/after energy and point velocity when clamping is active,
  // including multiple floats." Applies the CLAMPED result for several floats, in sequence
  // (via the real sequential ledger, matching confirmed native application order), and checks
  // both the true combined energy change and that no float's own opposed point-velocity
  // component is driven past zero into reversal.
  double mass = 400.0;
  Vector3d halfExtents = new Vector3d(2.0, 1.0, 4.0);
  MassData massData = TestMassData.solidBox(mass, halfExtents);
  SequentialImpulseEnergyLedger ledger = new SequentialImpulseEnergyLedger(
    massData, new Vector3d(0, 59.09, 10.0), new Vector3d(5.0, -8.0, 3.0));

  Vector3d[] arms = {
   new Vector3d(1.8, -0.9, 3.5),
   new Vector3d(-1.8, -0.9, 3.5),
   new Vector3d(0.0, 0.9, -3.5),
  };
  // Deliberately oversized physical drag forces - each would far exceed its own bound unclamped.
  Vector3d[] physicalDrags = {
   new Vector3d(0, -5_000_000.0, 0),
   new Vector3d(300_000.0, -4_000_000.0, 100_000.0),
   new Vector3d(-200_000.0, -3_000_000.0, 150_000.0),
  };

  double totalEnergyChange = 0;
  for (int i = 0; i < arms.length; i++) {
   Vector3d freshLinear = new Vector3d(ledger.linearVelocity());
   Vector3d freshAngular = new Vector3d(ledger.angularVelocity());
   Vector3d clamped = ProposedBoundedDragIntegration.boundedDragForce(massData, arms[i], physicalDrags[i], freshLinear, freshAngular, DT);
   Vector3d impulseVector = new Vector3d(clamped).mul(DT);
   double impulseMagnitude = impulseVector.length();
   if (impulseMagnitude == 0) continue;
   Vector3d direction = new Vector3d(clamped).normalize();

   // Confirm no reversal BEFORE applying, using the ledger's own live state (matching what
   // boundedDragForce itself just bounded against).
   Vector3d pointVelocityBefore = new Vector3d(ledger.angularVelocity()).cross(arms[i]).add(new Vector3d(ledger.linearVelocity()));
   double sBefore = pointVelocityBefore.dot(direction);

   totalEnergyChange += ledger.applyImpulseAndMeasureEnergyChange(arms[i], direction, impulseMagnitude);

   Vector3d pointVelocityAfter = new Vector3d(ledger.angularVelocity()).cross(arms[i]).add(new Vector3d(ledger.linearVelocity()));
   double sAfter = pointVelocityAfter.dot(direction);
   if (sBefore < 0) {
    assertTrue(sAfter >= sBefore - 1e-6 && sAfter <= 1e-6,
     "a clamped, opposing drag impulse must move the point-velocity component toward zero and never past it into the opposite sign");
   }
  }

  assertTrue(totalEnergyChange <= 1e-6,
   "three floats' clamped drag impulses, applied sequentially against live state, must keep the true combined energy change non-positive");
 }

 // MINECRAFT-545/547 (John's comments 55116/55129/55133): the failing-before/passing-after
 // demonstration tying this already-reviewed bound math directly to the ticket's OWN observed
 // divergence, using REALISTIC values drawn from the actual native log rather than an arbitrary
 // synthetic magnitude (the existing tests above already use a deliberately-huge synthetic
 // drag force to test the bound math in isolation; this one instead runs the REAL
 // Buoyancy.directionalDragForce formula with the real fixture's own mass/half-extents and a
 // point speed matching what M545-packet-9732b32-replacementprobe-round2's own log actually
 // recorded at the tick the divergence began (gameTime=204: towed point speed ~17 m/s,
 // tuning.waterDrag's real default of 400.0, fraction=1.0 - fully submerged, matching that run).

 @Test void aRepresentativeRegressionUsingTheTicketsOwnRealisticHighSpeedMagnitudeExceedsTheNonReversingBound() {
  // MINECRAFT-545/547 (John's comment 55139, item 3 - "label representative regression...
  // overstates synthetic speed17/zeroangular/directionY as recorded six-DOF same event"):
  // RELABELED. This is a REPRESENTATIVE regression using a REALISTIC magnitude (~17 m/s,
  // matching the order of magnitude M545-packet-9732b32-replacementprobe-round2's own log
  // actually recorded at gameTime=204) - it is a synthetic SCALAR case (single axis, zero
  // angular velocity), NOT a claim of reproducing the full six-degree-of-freedom real event,
  // and it does NOT independently verify the solid-box inertia model against Sable's own native
  // response (that assumption remains a modeling choice grounded in the three native runs this
  // ticket already performed, not re-proven by this scalar math alone - see
  // VERIFICATION.md/BuoyantForces.UNBOUNDED_DRAG_OVERRIDE's own doc for that distinction).
  // "BEFORE": the OLD unconditional path's own real formula, fed this representative value -
  // confirms the raw force's own implied impulse genuinely WOULD overshoot past a non-reversing
  // bound for a realistic magnitude, not an arbitrary synthetic one.
  double speed = 17.0; // m/s, matching the magnitude observed at the actual divergence tick
  double dragCoefficient = 400.0; // Overrides.FALLBACK.waterDrag's own real default
  double rawDragForceMagnitude = -Buoyancy.directionalDragForce(speed, speed, 1.0, dragCoefficient, 1.0);
  // (directionalDragForce returns a signed, opposing value; magnitude of interest is positive)
  assertTrue(rawDragForceMagnitude > 100_000, "sanity check: this must reproduce the same ~10^5 N order of magnitude the real log recorded at gameTime=204, not an arbitrary number");

  double mass = 400.0; // this fixture's own real configured massKg
  MassData massData = TestMassData.solidBox(mass, new Vector3d(0.6, 0.3, 1.0)); // this fixture's own real half-extents
  Vector3d arm = new Vector3d(0.5, -0.2, 0.0); // this fixture's own real floatLocalOffset
  Vector3d direction = new Vector3d(0, -1, 0); // drag opposing a +y point velocity component
  Vector3d linearVelocity = new Vector3d(0, speed, 0);
  Vector3d angularVelocity = new Vector3d(0, 0, 0);
  Vector3d position = new Vector3d(arm).add(massData.getCenterOfMass());
  Vector3d pointVelocity = new Vector3d(angularVelocity).cross(arm, new Vector3d()).add(new Vector3d(linearVelocity));
  double s = pointVelocity.dot(direction); // negative - direction opposes the point velocity
  double k = massData.getInverseNormalMass(position, direction);
  double nonReversingBound = Buoyancy.maxNonReversingImpulseMagnitude(s, k);
  double rawImpulseMagnitude = rawDragForceMagnitude * DT;

  assertTrue(rawImpulseMagnitude > nonReversingBound,
    "the REAL unbounded formula's own impulse at this representative magnitude must genuinely exceed the non-reversing bound - a representative regression, not a full six-DOF mechanism reproduction");

  // "AFTER": the SAME inputs, through the already-reviewed bounded path - must not exceed
  // the bound, and must still point the same direction (never reversed, never boosted).
  Vector3d rawDragForceVector = new Vector3d(direction).mul(rawDragForceMagnitude);
  Vector3d bounded = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, rawDragForceVector, linearVelocity, angularVelocity, DT);
  double boundedImpulseMagnitude = bounded.length() * DT;
  assertTrue(boundedImpulseMagnitude <= nonReversingBound + 1e-9,
    "the bounded path, given the EXACT SAME representative inputs that broke the unconditional path, must never exceed the non-reversing bound");
  assertTrue(bounded.length() < rawDragForceMagnitude,
    "the bound must actually have engaged (scaled down), not merely returned the raw force unchanged, for these representative inputs");
 }

 @Test void theCapItselfAtOrdinaryLowSpeedIsANoOp() {
  // CORRECTED per John's comment 55138, item 2 - "'true no-op/unaffected' claim is false for
  // whole integration": this test proves ONLY that the CAP/BOUND FUNCTION ITSELF
  // (ProposedBoundedDragIntegration.boundedDragForce) is a no-op at ordinary low speed - it
  // does NOT prove the whole integration is unaffected (the sequential fresh-per-float
  // recomputation is a SEPARATE reordering effect, present regardless of speed, quantified
  // separately in multipleOffCenterAnisotropicFloatsShowAMeasurableButSmallDifferenceFrom
  // SharedSnapshotEvenWithoutCapping below - do not conflate the two).
  double speed = 0.4; // m/s, matching the real pre-divergence magnitude from the actual log
  double dragCoefficient = 400.0;
  double rawDragForceMagnitude = -Buoyancy.directionalDragForce(speed, speed, 1.0, dragCoefficient, 1.0);
  assertTrue(rawDragForceMagnitude < 100, "sanity check: low speed must give a small, ordinary force, matching the real log's own pre-divergence values");

  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(0.6, 0.3, 1.0));
  Vector3d arm = new Vector3d(0.5, -0.2, 0.0);
  Vector3d direction = new Vector3d(0, -1, 0);
  Vector3d linearVelocity = new Vector3d(0, speed, 0);
  Vector3d angularVelocity = new Vector3d(0, 0, 0);
  Vector3d rawDragForceVector = new Vector3d(direction).mul(rawDragForceMagnitude);

  Vector3d bounded = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, rawDragForceVector, linearVelocity, angularVelocity, DT);

  assertEquals(0, new Vector3d(rawDragForceVector).sub(bounded).length(), 1e-9,
    "at ordinary low speed the CAP ITSELF must return the raw force unchanged - the cap is a genuine no-op here; this does NOT claim the whole integration (including the separate sequential-recomputation reordering effect) is unaffected");
 }

 @Test void multipleOffCenterAnisotropicFloatsShowAMeasurableButSmallDifferenceFromSharedSnapshotEvenWithoutCapping() {
  // MINECRAFT-545/547 (John's comment 55138, item 2 AND item 4 - "quantify intended integration
  // change" + "production sequence tests covering... multiple off-center anisotropic floats,
  // not only scalar cap"): at ORDINARY low speed (cap never binds for either float, confirmed
  // below), compares the OLD/unconditional model (every float's own drag computed from ONE
  // SHARED pre-tick velocity snapshot, impulses summed and applied once) against the NEW
  // default model (each float's impulse applied immediately via the real
  // SequentialImpulseEnergyLedger - confirmed native-synchronous - so the SECOND float's own
  // drag is computed from the ALREADY-UPDATED state the first float's own impulse produced).
  // Two floats, OFF-CENTER (different, nonzero arms) and ANISOTROPIC (different per-axis
  // multipliers, modeling tuning.longitudinal/lateral/verticalWaterDragMultiplier), mirroring
  // the real multi-float case BuoyantForces.apply actually handles, not a single scalar value.
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(0.6, 0.3, 1.0));
  Vector3d arm1 = new Vector3d(0.5, -0.2, 0.0), arm2 = new Vector3d(-0.5, -0.2, 0.0); // off-center, two real float positions
  Vector3d direction = new Vector3d(0, -1, 0);
  Vector3d initialLinear = new Vector3d(0, 0.4, 0); // ordinary low speed - same as theCapItselfAtOrdinaryLowSpeedIsANoOp
  Vector3d initialAngular = new Vector3d(0, 0, 0);
  double multiplier1 = 0.7, multiplier2 = 1.3; // anisotropic - different per-axis multipliers for each float

  // Confirm the cap never binds for either float at this speed (isolating the reordering effect
  // from any capping effect, per this test's own stated purpose).
  double raw1 = -Buoyancy.directionalDragForce(0.4, 0.4, 1.0, 400.0, multiplier1);
  double raw2 = -Buoyancy.directionalDragForce(0.4, 0.4, 1.0, 400.0, multiplier2);
  Vector3d rawVec1 = new Vector3d(direction).mul(raw1), rawVec2 = new Vector3d(direction).mul(raw2);
  assertEquals(0, new Vector3d(rawVec1).sub(ProposedBoundedDragIntegration.boundedDragForce(massData, arm1, rawVec1, initialLinear, initialAngular, DT)).length(), 1e-9,
    "setup check: the cap must not bind for float 1 at this speed");
  assertEquals(0, new Vector3d(rawVec2).sub(ProposedBoundedDragIntegration.boundedDragForce(massData, arm2, rawVec2, initialLinear, initialAngular, DT)).length(), 1e-9,
    "setup check: the cap must not bind for float 2 at this speed");

  // OLD/unconditional model: both floats' own drag computed from the SAME initial velocity,
  // impulses summed, applied ONCE at the end (the shared-snapshot behavior VERIFICATION.md's
  // own "Corrections per 54700" entry already confirmed the pre-this-fix code actually had).
  SequentialImpulseEnergyLedger sharedSnapshotLedger = new SequentialImpulseEnergyLedger(massData, initialLinear, initialAngular);
  double sharedSnapshotEnergyChange = sharedSnapshotLedger.applyImpulseAndMeasureEnergyChange(arm1, direction, raw1 * DT)
    + sharedSnapshotLedger.applyImpulseAndMeasureEnergyChange(arm2, direction, raw2 * DT);
  Vector3dc sharedSnapshotFinalLinear = sharedSnapshotLedger.linearVelocity();
  Vector3dc sharedSnapshotFinalAngular = sharedSnapshotLedger.angularVelocity();

  // NEW default model: float 1's impulse applied immediately (updating real state), THEN
  // float 2's own drag recomputed FRESH from that already-updated state - exactly
  // BuoyantForces.apply's own per-float loop + fresh handle.getLinearVelocity/AngularVelocity
  // reads between floats.
  SequentialImpulseEnergyLedger freshSequentialLedger = new SequentialImpulseEnergyLedger(massData, initialLinear, initialAngular);
  double freshEnergyChange1 = freshSequentialLedger.applyImpulseAndMeasureEnergyChange(arm1, direction, raw1 * DT);
  // Recompute float 2's own drag FRESH from the state float 1's own impulse just produced:
  Vector3d updatedPointVelocity2 = new Vector3d(freshSequentialLedger.angularVelocity()).cross(arm2, new Vector3d()).add(new Vector3d(freshSequentialLedger.linearVelocity()));
  double updatedSpeed2 = updatedPointVelocity2.length();
  double freshRaw2 = updatedSpeed2 > 1e-9 ? -Buoyancy.directionalDragForce(updatedSpeed2, updatedPointVelocity2.dot(new Vector3d(0,1,0)), 1.0, 400.0, multiplier2) : raw2;
  double freshEnergyChange2 = freshSequentialLedger.applyImpulseAndMeasureEnergyChange(arm2, direction, freshRaw2 * DT);
  double freshSequentialEnergyChange = freshEnergyChange1 + freshEnergyChange2;
  Vector3dc freshSequentialFinalLinear = freshSequentialLedger.linearVelocity();
  Vector3dc freshSequentialFinalAngular = freshSequentialLedger.angularVelocity();

  // QUANTIFY the difference - do not claim zero, measure it.
  double linearDifference = new Vector3d(sharedSnapshotFinalLinear).sub(new Vector3d(freshSequentialFinalLinear)).length();
  double angularDifference = new Vector3d(sharedSnapshotFinalAngular).sub(new Vector3d(freshSequentialFinalAngular)).length();
  assertTrue(linearDifference > 0 || angularDifference > 0,
    "the sequential-recomputation reordering effect must be measurably NONZERO even though neither float's own cap ever bound - confirming the whole-integration equivalence claim does NOT hold, exactly as 55138 found");
  // The difference must still be SMALL (bounded in magnitude) at this ordinary low speed - a
  // reordering effect, not a new instability.
  assertTrue(linearDifference < 0.01, "the reordering effect's own magnitude must be small at ordinary low speed, not itself a source of instability: got " + linearDifference);
  assertTrue(angularDifference < 0.01, "the reordering effect's own magnitude must be small at ordinary low speed, not itself a source of instability: got " + angularDifference);

  // What IS preserved regardless of the reordering: the physical law itself - drag never adds
  // energy, in EITHER model.
  assertTrue(sharedSnapshotEnergyChange <= 1e-9, "the shared-snapshot model's own total energy change from drag must stay non-positive");
  assertTrue(freshSequentialEnergyChange <= 1e-9, "the fresh-sequential model's own total energy change from drag must ALSO stay non-positive - the physical law is preserved under both orderings, even though the numeric result differs");
 }

 @Test void theBoundScalesCorrectlyAcrossMassVariation() {
  // Mass variation (John's comment 55133, item 3): a heavier body has a smaller inverse
  // effective mass (k), so the SAME point velocity yields a LARGER non-reversing bound (more
  // inertia resists the same velocity change) - confirmed directly, not merely asserted.
  Vector3d arm = new Vector3d(0.5, -0.2, 0.0);
  Vector3d direction = new Vector3d(0, -1, 0);
  Vector3d position = new Vector3d(arm);
  double s = -17.0;
  double kLight = TestMassData.solidBox(100.0, new Vector3d(0.6, 0.3, 1.0)).getInverseNormalMass(position, direction);
  double kHeavy = TestMassData.solidBox(1600.0, new Vector3d(0.6, 0.3, 1.0)).getInverseNormalMass(position, direction);
  assertTrue(kHeavy < kLight, "a 16x heavier body must have a smaller inverse effective mass");
  double boundLight = Buoyancy.maxNonReversingImpulseMagnitude(s, kLight);
  double boundHeavy = Buoyancy.maxNonReversingImpulseMagnitude(s, kHeavy);
  assertTrue(boundHeavy > boundLight, "a heavier body must tolerate a LARGER impulse before reversing the same velocity component - more inertia, not less");
 }

 @Test void theBoundScalesCorrectlyAcrossDtVariation() {
  // dt/substep variation (John's comment 55133, item 3): the bound itself (an impulse budget,
  // in N*s) does not depend on dt at all - only how much of a given FORCE's own impulse
  // (force*dt) that budget actually permits does. Confirmed: a smaller dt means the SAME force
  // produces a SMALLER impulse, so it is relatively less likely to exceed the SAME bound.
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(0.6, 0.3, 1.0));
  Vector3d arm = new Vector3d(0.5, -0.2, 0.0);
  Vector3d direction = new Vector3d(0, -1, 0);
  Vector3d linearVelocity = new Vector3d(0, 17.0, 0);
  Vector3d angularVelocity = new Vector3d(0, 0, 0);
  double rawDragForceMagnitude = -Buoyancy.directionalDragForce(17.0, 17.0, 1.0, 400.0, 1.0);
  Vector3d rawDragForceVector = new Vector3d(direction).mul(rawDragForceMagnitude);

  Vector3d boundedFullDt = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, rawDragForceVector, linearVelocity, angularVelocity, DT);
  Vector3d boundedHalfDt = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, rawDragForceVector, linearVelocity, angularVelocity, DT / 2.0);
  // A smaller dt raises the SAME force's own allowed headroom (same budget, smaller per-step
  // impulse demand) - the half-dt result must be AT LEAST as large in magnitude (never smaller).
  assertTrue(boundedHalfDt.length() >= boundedFullDt.length() - 1e-6,
    "a smaller dt must never result in a MORE aggressively clamped force than a larger one, for the same physical force");
 }

 @Test void theBoundRespectsAnisotropicPerAxisMultipliers() {
  // Anisotropic drag (John's comment 55133, item 3): a different per-axis multiplier changes
  // the RAW force fed into the bound, but the bound math itself treats whatever magnitude it is
  // given identically regardless of which axis multiplier produced it - confirmed by feeding
  // two different multiplier-scaled raw forces through the same bound and checking each is
  // still correctly capped relative to its OWN magnitude, not silently ignored.
  double speed = 17.0;
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(0.6, 0.3, 1.0));
  Vector3d arm = new Vector3d(0.5, -0.2, 0.0);
  Vector3d direction = new Vector3d(0, -1, 0);
  Vector3d linearVelocity = new Vector3d(0, speed, 0);
  Vector3d angularVelocity = new Vector3d(0, 0, 0);

  double rawLowMultiplier = -Buoyancy.directionalDragForce(speed, speed, 1.0, 400.0, 0.5); // half-strength axis
  double rawHighMultiplier = -Buoyancy.directionalDragForce(speed, speed, 1.0, 400.0, 2.0); // double-strength axis
  Vector3d boundedLow = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, new Vector3d(direction).mul(rawLowMultiplier), linearVelocity, angularVelocity, DT);
  Vector3d boundedHigh = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, new Vector3d(direction).mul(rawHighMultiplier), linearVelocity, angularVelocity, DT);

  assertTrue(boundedLow.length() <= rawLowMultiplier, "the lower-multiplier axis must still be correctly bounded relative to its own (smaller) raw force");
  assertTrue(boundedHigh.length() <= rawHighMultiplier, "the higher-multiplier axis must still be correctly bounded relative to its own (larger) raw force");
  // Both must respect the SAME underlying non-reversing bound (the bound depends on velocity/
  // mass/arm, not on which axis multiplier produced the raw force magnitude).
  Vector3d position = new Vector3d(arm).add(massData.getCenterOfMass());
  Vector3d pointVelocity = new Vector3d(angularVelocity).cross(arm, new Vector3d()).add(new Vector3d(linearVelocity));
  double s = pointVelocity.dot(direction);
  double k = massData.getInverseNormalMass(position, direction);
  double bound = Buoyancy.maxNonReversingImpulseMagnitude(s, k);
  assertTrue(boundedLow.length() * DT <= bound + 1e-9);
  assertTrue(boundedHigh.length() * DT <= bound + 1e-9);
 }
}
