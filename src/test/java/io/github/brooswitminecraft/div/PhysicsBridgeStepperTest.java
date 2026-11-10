package io.github.brooswitminecraft.div;
import minecrafttransportsimulator.jsondefs.JSONPart;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@code PhysicsBridge.selectStepper(...)} and {@code PhysicsBridge.planTick(...)},
 * the exact (package-private) decisions {@code Body.step} makes every tick to pick a force
 * model and compose it with buoyancy - the live integration seam between
 * {@code Classification.isRotorcraft}/{@code needsUnderwaterPropellerThrust} (already covered in
 * isolation by {@code ClassificationTest}) and the production dispatch that actually calls
 * {@code stepGround}/{@code stepFlight}/{@code stepRotor}/{@code BuoyantForces.apply}. A live
 * {@code EntityVehicleF_Physics}/Sable body cannot be constructed in a unit test (see
 * VERIFICATION.md), so this is the closest production-seam coverage available: both methods are
 * the literal code {@code Body.step} calls, not a parallel stand-in.
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

 @Test void groundVehicleSelectsGroundWithNoInstalledRotor() {
  assertEquals(PhysicsBridge.Stepper.GROUND, PhysicsBridge.selectStepper(false, List.of()));
  assertEquals(PhysicsBridge.Stepper.GROUND, PhysicsBridge.selectStepper(false, List.of(forwardPropeller())));
 }

 @Test void installedRotorSelectsRotorEvenWithoutTheFlightFlag() {
  // Review finding: a vehicle with an actual installed isRotor propeller but no
  // isAircraft/isBlimp flag was previously stuck on GROUND, its rotor never getting lift/
  // control. The installed part must win dispatch regardless of flight.
  assertEquals(PhysicsBridge.Stepper.ROTOR, PhysicsBridge.selectStepper(false, List.of(rotor())));
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

 @Test void planTickComposesStepperAndBuoyancyIndependently() {
  // planTick is the literal method Body.step calls to decide both which primary force model to
  // run and whether to additionally apply BuoyantForces this tick - the two are independent
  // axes (a ground, flight, or rotor body can each separately be float-capable or not).
  assertEquals(new PhysicsBridge.TickPlan(PhysicsBridge.Stepper.GROUND, false), PhysicsBridge.planTick(false, false, List.of()));
  assertEquals(new PhysicsBridge.TickPlan(PhysicsBridge.Stepper.GROUND, true), PhysicsBridge.planTick(false, true, List.of()));
  assertEquals(new PhysicsBridge.TickPlan(PhysicsBridge.Stepper.FLIGHT, false), PhysicsBridge.planTick(true, false, List.of()));
  assertEquals(new PhysicsBridge.TickPlan(PhysicsBridge.Stepper.FLIGHT, true), PhysicsBridge.planTick(true, true, List.of()));
 }

 @Test void floatEquippedRotorcraftTickPlansRotorPlusBuoyancy() {
  // The rotor+float production composition the review specifically asked for: a float-equipped
  // helicopter's tick plan must select ROTOR (so stepRotor supplies its own collective/cyclic
  // thrust) AND apply buoyancy (so BuoyantForces still lifts/rights its float devices) - both at
  // once, neither at the expense of the other. This holds with or without the aircraft/blimp
  // flag, since rotor dispatch no longer depends on it.
  var installedPropellers = List.of(rotor());
  assertEquals(new PhysicsBridge.TickPlan(PhysicsBridge.Stepper.ROTOR, true), PhysicsBridge.planTick(true, true, installedPropellers));
  assertEquals(new PhysicsBridge.TickPlan(PhysicsBridge.Stepper.ROTOR, true), PhysicsBridge.planTick(false, true, installedPropellers));
 }

 private static JSONPart.JSONPartGroundDevice pontoon() {
  var g = new JSONPart.JSONPartGroundDevice();
  g.canFloat = true;
  return g;
 }
 private static JSONPart.JSONPartGroundDevice wheel() {
  return new JSONPart.JSONPartGroundDevice();
 }

 @Test void liveFloatDeviceAddRemoveTransitionTogglesBuoyancyEachTick() {
  // Self-review finding: Body previously cached floatCapable once at construction, so
  // installing or removing a canFloat device afterward (which doesn't itself change the
  // ground/flight category, so never triggers replaceIfCategoryChanged) left buoyancy
  // permanently stuck on whatever was true when the body was built. The fix is that Body.step
  // now passes hasFloatDevice(v) - freshly computed from the vehicle's current parts - into
  // planTick every tick, the same uncached pattern already proven for rotor add/remove above.
  //
  // A coordinator-relayed review of the first fix correctly found this test was not actually
  // exercising that seam: it hand-fed false/true/false straight into planTick, so a regression
  // that went back to caching floatCapable would stay green. This drives the exact production
  // decision - PhysicsBridge.hasFloatDevice(List<JSONPartGroundDevice>), the pure function
  // hasFloatDevice(EntityVehicleF_Physics) delegates to - over a mutable installed-ground-device
  // list, installing and removing a real non-spare canFloat device through it, the same way
  // liveRotorAddRemoveTransitionSwitchesSolversEachTick mutates a propeller list through
  // selectStepper. Reintroducing a cached floatCapable field would make this fail: the second
  // and third assertions would see whatever boolean was captured from the first.
  var installedGroundDevices = new ArrayList<JSONPart.JSONPartGroundDevice>();
  installedGroundDevices.add(wheel());
  var installedPropellers = List.<JSONPart.JSONPartPropeller>of();
  assertFalse(PhysicsBridge.hasFloatDevice(installedGroundDevices), "no float device yet");
  assertFalse(PhysicsBridge.planTick(false, PhysicsBridge.hasFloatDevice(installedGroundDevices), installedPropellers).appliesBuoyancy());
  installedGroundDevices.add(pontoon());
  assertTrue(PhysicsBridge.hasFloatDevice(installedGroundDevices), "float device just installed");
  assertTrue(PhysicsBridge.planTick(false, PhysicsBridge.hasFloatDevice(installedGroundDevices), installedPropellers).appliesBuoyancy());
  installedGroundDevices.remove(installedGroundDevices.size() - 1);
  assertFalse(PhysicsBridge.hasFloatDevice(installedGroundDevices), "float device just removed");
  assertFalse(PhysicsBridge.planTick(false, PhysicsBridge.hasFloatDevice(installedGroundDevices), installedPropellers).appliesBuoyancy());
 }

 @Test void rotorAndNonRotorPropellerThrustOwnershipPartitionsWithNoOverlapOrGap() {
  // The other half of the rotor+float composition: when a tick plan selects ROTOR, the rotor
  // propeller's own thrust comes from stepRotor using the same installedPropellers list passed
  // into planTick; BuoyantForces (applied because appliesBuoyancy is true) must exclude exactly
  // that propeller from its own underwater-thrust pass via needsUnderwaterPropellerThrust, so
  // the rotor's thrust is counted exactly once - never by both methods, never by neither.
  var rotor = rotor();
  var forward = forwardPropeller();
  var plan = PhysicsBridge.planTick(true, true, List.of(forward, rotor));
  assertEquals(PhysicsBridge.Stepper.ROTOR, plan.stepper(), "stepRotor owns this tick's rotor thrust");
  assertTrue(plan.appliesBuoyancy(), "BuoyantForces still applies for this tick's float devices");
  assertFalse(Classification.needsUnderwaterPropellerThrust(rotor), "excluded from BuoyantForces's thrust pass - stepRotor already owns it");
  assertTrue(Classification.needsUnderwaterPropellerThrust(forward), "not excluded - BuoyantForces owns this one, stepRotor does not apply to it");
 }
}
