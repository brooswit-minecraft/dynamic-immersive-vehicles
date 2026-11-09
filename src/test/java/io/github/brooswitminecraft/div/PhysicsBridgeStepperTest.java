package io.github.brooswitminecraft.div;
import minecrafttransportsimulator.jsondefs.JSONPart;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@code PhysicsBridge.selectStepper(...)}, the exact (package-private) decision
 * {@code Body.step} makes every tick to pick a force model - the live integration seam between
 * {@code Classification.isRotorcraft} (already covered in isolation by {@code ClassificationTest})
 * and the production dispatch that actually calls {@code stepGround}/{@code stepFlight}/
 * {@code stepRotor}. A live {@code EntityVehicleF_Physics}/Sable body cannot be constructed in a
 * unit test (see VERIFICATION.md), so this is the closest production-seam coverage available:
 * {@code selectStepper} is the literal method {@code Body.step} calls, not a parallel stand-in.
 */
class PhysicsBridgeStepperTest {
 private static JSONPart.JSONPartPropeller rotor() {
  var p = new JSONPart.JSONPartPropeller();
  p.isRotor = true;
  return p;
 }
 private static JSONPart.JSONPartPropeller forwardPropeller() {
  return new JSONPart.JSONPartPropeller();
 }

 @Test void groundVehicleAlwaysSelectsGroundRegardlessOfPropellers() {
  assertEquals(PhysicsBridge.Stepper.GROUND, PhysicsBridge.selectStepper(false, List.of()));
  assertEquals(PhysicsBridge.Stepper.GROUND, PhysicsBridge.selectStepper(false, List.of(rotor())));
 }

 @Test void flightVehicleWithNoRotorSelectsFlight() {
  assertEquals(PhysicsBridge.Stepper.FLIGHT, PhysicsBridge.selectStepper(true, List.of()));
  assertEquals(PhysicsBridge.Stepper.FLIGHT, PhysicsBridge.selectStepper(true, List.of(forwardPropeller())));
 }

 @Test void flightVehicleWithAnInstalledRotorSelectsRotor() {
  assertEquals(PhysicsBridge.Stepper.ROTOR, PhysicsBridge.selectStepper(true, List.of(rotor())));
  assertEquals(PhysicsBridge.Stepper.ROTOR, PhysicsBridge.selectStepper(true, List.of(forwardPropeller(), rotor())));
 }

 @Test void liveRotorAddRemoveTransitionSwitchesSolversEachTick() {
  // The production-seam regression the review named: the exact mutable installed-parts list
  // Body.step would pass this tick, across a rotor being installed then removed, must flip
  // selectStepper's answer immediately both ways - this is what makes a rotor added/removed
  // after activation switch solvers on the very next tick rather than keeping a stale one.
  var installed = new ArrayList<JSONPart.JSONPartPropeller>();
  installed.add(forwardPropeller());
  assertEquals(PhysicsBridge.Stepper.FLIGHT, PhysicsBridge.selectStepper(true, installed), "no rotor installed yet");
  installed.add(rotor());
  assertEquals(PhysicsBridge.Stepper.ROTOR, PhysicsBridge.selectStepper(true, installed), "rotor just installed");
  installed.remove(installed.size() - 1);
  assertEquals(PhysicsBridge.Stepper.FLIGHT, PhysicsBridge.selectStepper(true, installed), "rotor just removed");
 }

 @Test void rotorAndNonRotorPropellerThrustOwnershipPartitionsWithNoOverlapOrGap() {
  // The other half of the review's finding: rotor+float force composition must count a
  // propeller's thrust exactly once. needsUnderwaterPropellerThrust(p) selects exactly the
  // propellers BuoyantForces applies underwater thrust to; a ROTOR-stepped vehicle's rotor
  // propeller must be the complementary set stepRotor already covers, by construction, for
  // every propeller - never both methods, never neither.
  var rotor = rotor();
  var forward = forwardPropeller();
  assertTrue(Classification.isRotorcraft(List.of(rotor)));
  assertFalse(Classification.needsUnderwaterPropellerThrust(rotor), "stepRotor owns this one's thrust");
  assertFalse(Classification.isRotorcraft(List.of(forward)));
  assertTrue(Classification.needsUnderwaterPropellerThrust(forward), "BuoyantForces owns this one's thrust");
  // A null propeller entry (defensive, see Classification.isRotor) is never treated as a rotor,
  // so it stays on the underwater-thrust side rather than silently losing thrust coverage.
  assertTrue(Classification.needsUnderwaterPropellerThrust(null));
 }
}
