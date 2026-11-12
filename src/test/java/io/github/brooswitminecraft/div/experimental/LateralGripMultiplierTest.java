package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticket 53331/53332/53335: John's hands-on feedback ("flops over to the side when I turn...
 * also maybe less grip or side force from the tires so the thing slides") prompted a new
 * per-vehicle {@code lateralGripMultiplier}. Review (comment 53335) correctly rejected this
 * class's own first implementation attempt - damping either the per-pass lateral INCREMENT or
 * the whole ACCUMULATOR inside {@link MultiContactFrictionSolver}'s PGS loop - as unsound:
 * damping the increment only slows convergence RATE, so a high iteration count (the real
 * installed baseline's own 64, per ticket comment 53333) restores nearly full grip regardless of
 * the multiplier; damping the accumulator additionally compounds the multiplier geometrically
 * across passes. Fixed by moving the knob into an ANISOTROPIC (elliptical) friction budget
 * ({@link io.github.brooswitminecraft.div.TireForces#frictionAnisotropic}) instead - a genuine,
 * iteration-count-independent bound on the CONVERGED steady state. This test exists specifically
 * to prove that property, per review's explicit ask: tests at 4 AND 64 iterations, zero/lower
 * grip, combined drive+turn, finite results, and expected residual (non-zero) slip - "do not
 * call a one-multiply change validated without these semantics."
 */
class LateralGripMultiplierTest {
 private static final double MASS = 350.0, DT = 0.05, FRICTION = 1.0, NORMAL = 2000.0;
 private static final Vector3d INERTIA = new Vector3d(100, 100, 100);
 private static final Vector3d POINT_AT_COM = new Vector3d(0, 0, 0);
 private static final Vector3d FORWARD = new Vector3d(0, 0, 1), RIGHT = new Vector3d(1, 0, 0);
 private static final Quaterniond IDENTITY = new Quaterniond();

 private static MultiContactFrictionSolver.FrictionContact[] contacts() {
  return new MultiContactFrictionSolver.FrictionContact[]{new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM, FORWARD, RIGHT, NORMAL, 1.0)};
 }

 /** @param initialSideSpeed starting lateral (RIGHT-axis) velocity component - the "slip" a turn would induce.
  * @return the resulting lateral velocity component after one tick's worth of solve. */
 private static double turnResultingSideSpeed(double initialSideSpeed, double driveForcePerWheel, double lateralGripMultiplier, int iterations) {
  Vector3d velocity = new Vector3d(FORWARD).mul(5.0).add(new Vector3d(RIGHT).mul(initialSideSpeed));
  Vector3d angular = new Vector3d(0, 0, 0);
  MultiContactFrictionSolver.solveFriction(contacts(), MASS, INERTIA, IDENTITY, IDENTITY, velocity, angular,
    false, 0, driveForcePerWheel, FRICTION, lateralGripMultiplier, DT, iterations);
  return velocity.dot(RIGHT);
 }

 private static double turnResultingForwardSpeed(double initialSideSpeed, double driveForcePerWheel, double lateralGripMultiplier, int iterations) {
  Vector3d velocity = new Vector3d(FORWARD).mul(5.0).add(new Vector3d(RIGHT).mul(initialSideSpeed));
  Vector3d angular = new Vector3d(0, 0, 0);
  MultiContactFrictionSolver.solveFriction(contacts(), MASS, INERTIA, IDENTITY, IDENTITY, velocity, angular,
    false, 0, driveForcePerWheel, FRICTION, lateralGripMultiplier, DT, iterations);
  return velocity.dot(FORWARD);
 }

 /** Default (1.0) must be algebraically byte-identical to the original circular model - direct
  * proof at the TireForces level, independent of the solver's own iteration loop. */
 @Test void defaultMultiplierIsByteIdenticalToCircularFriction() {
  double[][] cases = {{500, 300}, {-200, 900}, {1800, 1800}, {0, 0}, {2500, -400}};
  for (double[] c : cases) {
   var circular = io.github.brooswitminecraft.div.TireForces.friction(c[0], c[1], NORMAL, FRICTION);
   var anisotropic = io.github.brooswitminecraft.div.TireForces.frictionAnisotropic(c[0], c[1], NORMAL, FRICTION, 1.0);
   assertEquals(circular.longitudinal(), anisotropic.longitudinal(), 1e-12, "longitudinal differs for " + java.util.Arrays.toString(c));
   assertEquals(circular.lateral(), anisotropic.lateral(), 1e-12, "lateral differs for " + java.util.Arrays.toString(c));
  }
 }

 /** The core property review demanded: the converged lateral-slip-cancellation strength at a
  * REDUCED multiplier must be meaningfully smaller than at full grip, AND this must hold at BOTH
  * a low (4) and high (64, the real installed baseline's own value per 53333) iteration count -
  * not just at 4, where a convergence-RATE-only fix could superficially look correct. */
 @Test void reducedGripBoundsSteadyStateAtBothLowAndHighIterationCounts() {
  double initialSideSpeed = 3.0; // m/s of lateral slip, as a hard turn would induce
  double lateralGripMultiplier = 0.2;
  for (int iterations : new int[]{4, 64}) {
   double fullGripResidual = turnResultingSideSpeed(initialSideSpeed, 0, 1.0, iterations);
   double reducedGripResidual = turnResultingSideSpeed(initialSideSpeed, 0, lateralGripMultiplier, iterations);
   assertTrue(Double.isFinite(fullGripResidual) && Double.isFinite(reducedGripResidual),
     "non-finite result at iterations=" + iterations);
   double correctionFull = initialSideSpeed - fullGripResidual;
   double correctionReduced = initialSideSpeed - reducedGripResidual;
   assertTrue(correctionFull > 0.01, "expected SOME real correction at full grip to compare against, iterations=" + iterations + " correction=" + correctionFull);
   // The real, precise property this budget design gives: the actual CORRECTION applied
   // (not merely "a larger residual") scales with lateralGripMultiplier, because both cases'
   // first pass is budget-clamped (the required unclamped correction vastly exceeds either
   // budget) - the clamped per-pass impulse is directly proportional to the lateral semi-axis,
   // which is exactly normal*mu*lateralGripMultiplier. Checking the RATIO (not an absolute
   // residual threshold, which depends on effective-mass/DT specifics this test should not
   // need to reverse-engineer) directly validates the anisotropic math itself.
   double ratio = correctionReduced / correctionFull;
   assertEquals(lateralGripMultiplier, ratio, 0.05,
     "corrected-amount ratio should track lateralGripMultiplier directly, iterations=" + iterations
       + " correctionFull=" + correctionFull + " correctionReduced=" + correctionReduced);
  }
  // Confirm the reduced-grip residual at 4 and 64 iterations is close to EACH OTHER - once the
  // first pass clamps, remaining passes re-clamp to the SAME ceiling (zero marginal impulse),
  // so this budget design is genuinely iteration-count-independent, the opposite of "64 passes
  // restores near-full grip" review warned the earlier, rejected increment-only fix would do.
  double residual4 = turnResultingSideSpeed(initialSideSpeed, 0, lateralGripMultiplier, 4);
  double residual64 = turnResultingSideSpeed(initialSideSpeed, 0, lateralGripMultiplier, 64);
  assertEquals(residual4, residual64, 0.05, "reduced-grip residual should be iteration-count-independent (steady-state bound), not convergence-rate-dependent");
 }

 /** Longitudinal (drive) authority must be unaffected by a reduced lateral multiplier whenever
  * the combined point still fits the ellipse - review's explicit "preserve longitudinal-only
  * behavior", checked under a REALISTIC combined drive+turn scenario (not lateral alone). */
 @Test void combinedDriveAndTurnPreservesLongitudinalAuthority() {
  double driveForcePerWheel = 400.0; // well under the 2000N circular/elliptical longitudinal budget
  double initialSideSpeed = 1.0; // a modest turn, not an extreme slip
  for (int iterations : new int[]{4, 64}) {
   double forwardFullGrip = turnResultingForwardSpeed(initialSideSpeed, driveForcePerWheel, 1.0, iterations);
   double forwardReducedGrip = turnResultingForwardSpeed(initialSideSpeed, driveForcePerWheel, 0.2, iterations);
   assertTrue(Double.isFinite(forwardFullGrip) && Double.isFinite(forwardReducedGrip), "non-finite forward speed at iterations=" + iterations);
   // Reducing LATERAL grip must not MEANINGFULLY reduce forward acceleration - a smaller
   // lateral claim on the shared ellipse leaves at least as much budget for longitudinal. A
   // tiny (<0.5%) difference is tolerated since the PGS loop couples both axes' candidates
   // across passes (the lateral slip-cancellation target itself depends on each pass's
   // just-applied impulse), but there must be no MEANINGFUL (>0.5%) loss of drive authority.
   double relativeDelta = (forwardFullGrip - forwardReducedGrip) / forwardFullGrip;
   assertTrue(relativeDelta < 0.005,
     "reduced lateral grip should not meaningfully reduce longitudinal/drive authority, iterations=" + iterations
       + " full=" + forwardFullGrip + " reduced=" + forwardReducedGrip + " relativeDelta=" + relativeDelta);
  }
 }

 /** Zero lateral grip is a legal, finite extreme (the tire contributes no lateral force at all,
  * sliding freely) - review's explicit "finite results" at the boundary, not just mid-range values. */
 @Test void zeroLateralGripStaysFiniteAndFullySlides() {
  for (int iterations : new int[]{4, 64}) {
   double residual = turnResultingSideSpeed(3.0, 0, 0.0, iterations);
   assertTrue(Double.isFinite(residual), "non-finite at lateralGripMultiplier=0, iterations=" + iterations);
   assertEquals(3.0, residual, 1e-9, "zero lateral grip should leave lateral velocity completely uncorrected, iterations=" + iterations);
  }
 }
}
