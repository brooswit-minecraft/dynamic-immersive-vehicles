package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parse/validate coverage for {@code -Ddiv.experimentalFrictionSolverIterations} (ticket
 * comments 53127/53130: "bounded higher-iteration experimental candidate... preserve current
 * production default 4"). 0 means bypassed - every value that isn't a valid positive in-bound
 * integer must resolve to 0, never some other active default, since an unreviewed experimental
 * accuracy knob should never silently activate itself. Mirrors
 * PhysicsBridgeExperimentalThrottleRampTicksTest's coverage shape.
 */
class PhysicsBridgeExperimentalFrictionSolverIterationsTest {
 @Test void nullPropertyIsBypassed() {
  assertEquals(0, PhysicsBridge.resolveExperimentalFrictionSolverIterations(null));
 }
 @Test void blankOrNonNumericIsBypassed() {
  assertEquals(0, PhysicsBridge.resolveExperimentalFrictionSolverIterations(""));
  assertEquals(0, PhysicsBridge.resolveExperimentalFrictionSolverIterations("not-a-number"));
  assertEquals(0, PhysicsBridge.resolveExperimentalFrictionSolverIterations("NaN"));
  assertEquals(0, PhysicsBridge.resolveExperimentalFrictionSolverIterations("Infinity"));
 }
 @Test void zeroOrNegativeIsBypassed() {
  assertEquals(0, PhysicsBridge.resolveExperimentalFrictionSolverIterations("0"));
  assertEquals(0, PhysicsBridge.resolveExperimentalFrictionSolverIterations("-64"));
 }
 @Test void aboveTheDocumentedUpperBoundIsBypassed() {
  assertEquals(0, PhysicsBridge.resolveExperimentalFrictionSolverIterations(Integer.toString(PhysicsBridge.MAX_EXPERIMENTAL_FRICTION_SOLVER_ITERATIONS+1)));
 }
 @Test void exactlyTheUpperBoundIsAccepted() {
  assertEquals(PhysicsBridge.MAX_EXPERIMENTAL_FRICTION_SOLVER_ITERATIONS,
    PhysicsBridge.resolveExperimentalFrictionSolverIterations(Integer.toString(PhysicsBridge.MAX_EXPERIMENTAL_FRICTION_SOLVER_ITERATIONS)));
 }
 @Test void theMeasuredCandidateValueIsAccepted() {
  assertEquals(64, PhysicsBridge.resolveExperimentalFrictionSolverIterations("64"));
 }
 @Test void effectiveFrictionSolverIterationsFallsBackToShippedDefaultWhenBypassed() {
  // System property is unset in this test JVM, so the candidate is bypassed by construction -
  // effective must equal the shipped default, not 0 or any other value.
  assertEquals(io.github.brooswitminecraft.div.experimental.MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS,
    PhysicsBridge.effectiveFrictionSolverIterations());
 }
}
