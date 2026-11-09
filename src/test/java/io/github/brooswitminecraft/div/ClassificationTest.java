package io.github.brooswitminecraft.div;
import minecrafttransportsimulator.jsondefs.JSONPart;
import minecrafttransportsimulator.jsondefs.JSONVehicle;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the real IV JSON definition types (no mocks, no live entity/world/Sable runtime
 * needed) to pin down which vehicle categories get physics by default and which stay excluded.
 */
class ClassificationTest {
 private static final List<JSONPart.JSONPartPropeller> NO_PROPELLERS = List.of();
 private static JSONVehicle motorized() {
  var v = new JSONVehicle();
  v.motorized = new JSONVehicle.VehicleMotorized();
  return v;
 }
 private static JSONPart.JSONPartGroundDevice wheel() {
  var g = new JSONPart.JSONPartGroundDevice();
  g.isWheel = true;
  return g;
 }
 private static JSONPart.JSONPartGroundDevice tread() {
  var g = new JSONPart.JSONPartGroundDevice();
  g.isTread = true;
  return g;
 }
 private static JSONPart.JSONPartGroundDevice pontoon() {
  var g = new JSONPart.JSONPartGroundDevice();
  g.canFloat = true;
  return g;
 }
 private static JSONPart.JSONPartPropeller rotor() {
  var p = new JSONPart.JSONPartPropeller();
  p.isRotor = true;
  return p;
 }
 private static JSONPart.JSONPartPropeller forwardPropeller() {
  return new JSONPart.JSONPartPropeller();
 }

 @Test void anyWheelCountIsEligibleNotJustFour() {
  for (int wheels = 1; wheels <= 8; wheels++) {
   assertTrue(Classification.eligible(motorized(), Collections.nCopies(wheels, wheel()), NO_PROPELLERS, false, false), wheels + " wheels");
  }
 }

 @Test void tracksAreEligibleWithoutAnyWheel() {
  assertTrue(Classification.eligible(motorized(), List.of(tread(), tread()), NO_PROPELLERS, false, false));
 }

 @Test void mixedWheelsAndTracksAreEligible() {
  assertTrue(Classification.eligible(motorized(), List.of(wheel(), tread()), NO_PROPELLERS, false, false));
 }

 @Test void noOverrideEntryStillDefaultsToEligible() {
  // "Every vehicle by default" is the point: a plain wheeled car with no config entry at all
  // (optedOut=false, the lookup-miss case) must still get physics.
  assertTrue(Classification.eligible(motorized(), List.of(wheel()), NO_PROPELLERS, false, false));
 }

 @Test void explicitOptOutExcludesAnOtherwiseEligibleCar() {
  assertFalse(Classification.eligible(motorized(), List.of(wheel()), NO_PROPELLERS, false, true));
 }

 @Test void aircraftIsEligibleWithNoGroundDeviceAtAll() {
  var v = motorized();
  v.motorized.isAircraft = true;
  assertTrue(Classification.eligible(v, List.of(), NO_PROPELLERS, false, false));
  assertTrue(Classification.isFlightVehicle(v));
 }

 @Test void fixedWingAircraftWithAForwardPropellerIsStillEligible() {
  var v = motorized();
  v.motorized.isAircraft = true;
  assertTrue(Classification.eligible(v, List.of(), List.of(forwardPropeller()), false, false));
  assertFalse(Classification.isRotorcraft(List.of(forwardPropeller())));
 }

 @Test void rotorcraftIsEligibleAsAnAircraft() {
  // A helicopter: IV marks its propeller isRotor and gives it its own thrust-vectoring solver.
  // This bridge now replaces that with RotorForces instead of the fixed-wing model - a
  // rotorcraft stays eligible as an aircraft, it just needs PhysicsBridge.Body to pick the
  // matching model, which isRotorcraft (below) is what that choice is based on.
  var v = motorized();
  v.motorized.isAircraft = true;
  assertTrue(Classification.eligible(v, List.of(), List.of(rotor()), false, false));
  assertTrue(Classification.isRotor(rotor()));
  assertTrue(Classification.isRotorcraft(List.of(rotor())));
 }

 @Test void installedRotorPropellerIsEligibleEvenWithNoAircraftOrBlimpFlag() {
  // Review finding: a vehicle with an actual installed isRotor propeller but neither
  // isAircraft nor isBlimp set must still be eligible as a rotorcraft, driven by the real
  // installed part rather than gated on a definition flag it doesn't need - otherwise it was
  // either deactivated outright (no wheels) or stuck on the ground solver (with wheels),
  // its rotor never getting lift/control.
  var v = motorized();
  assertFalse(Classification.isFlightVehicle(v));
  assertTrue(Classification.eligible(v, List.of(), List.of(rotor()), false, false));
 }

 @Test void installedRotorPropellerIsEligibleEvenAlongsideWheelsAndNoFlightFlag() {
  var v = motorized();
  assertTrue(Classification.eligible(v, List.of(wheel()), List.of(rotor()), false, false));
 }

 @Test void installedRotorPropellerStillExcludedByOptOutOrActiveTow() {
  var v = motorized();
  assertFalse(Classification.eligible(v, List.of(), List.of(rotor()), false, true));
  assertFalse(Classification.eligible(v, List.of(), List.of(rotor()), true, false));
 }

 @Test void rotorAmongMultiplePropellersStillMarksRotorcraft() {
  assertTrue(Classification.isRotorcraft(List.of(forwardPropeller(), rotor())));
 }

 @Test void noRotorAmongPropellersIsNotRotorcraft() {
  assertFalse(Classification.isRotorcraft(List.of(forwardPropeller(), forwardPropeller())));
  assertFalse(Classification.isRotorcraft(List.of()));
 }

 @Test void isRotorcraftReflectsPartsChangingAfterAnEarlierCallNotAnyFrozenState() {
  // Integration-seam guard: PhysicsBridge.Body.step re-derives solver selection from this method
  // every tick instead of caching it from construction (a prior revision did cache it, missing
  // the ticket's "missing/changed parts" requirement - a rotor installed/removed/replaced after
  // activation never flipped the selected solver). isRotorcraft itself must have no memory of a
  // previous call: the exact same mutable installed-parts list, after a rotor is added then
  // removed, must answer each call based only on its current contents.
  var installed = new java.util.ArrayList<JSONPart.JSONPartPropeller>();
  installed.add(forwardPropeller());
  assertFalse(Classification.isRotorcraft(installed), "no rotor installed yet");
  installed.add(rotor());
  assertTrue(Classification.isRotorcraft(installed), "rotor just installed: solver must switch to RotorForces");
  installed.remove(installed.size() - 1);
  assertFalse(Classification.isRotorcraft(installed), "rotor just removed: solver must switch back to FlightForces");
 }

 @Test void nullPropellerEntryIsIgnoredNotACrash() {
  assertFalse(Classification.isRotor(null));
  assertFalse(Classification.isRotorcraft(Arrays.<JSONPart.JSONPartPropeller>asList(null, forwardPropeller())));
  assertTrue(Classification.isRotorcraft(Arrays.<JSONPart.JSONPartPropeller>asList(null, rotor())));
 }

 @Test void blimpIsEligibleWithNoGroundDeviceAtAll() {
  var v = motorized();
  v.motorized.isBlimp = true;
  assertTrue(Classification.eligible(v, List.of(), NO_PROPELLERS, false, false));
  assertTrue(Classification.isFlightVehicle(v));
 }

 @Test void aircraftOptOutStillExcludes() {
  var v = motorized();
  v.motorized.isAircraft = true;
  assertFalse(Classification.eligible(v, List.of(), NO_PROPELLERS, false, true));
 }

 @Test void activelyTowedAircraftIsExcluded() {
  var v = motorized();
  v.motorized.isAircraft = true;
  assertFalse(Classification.eligible(v, List.of(), NO_PROPELLERS, true, false));
 }

 @Test void groundVehicleIsNotClassifiedAsFlight() {
  assertFalse(Classification.isFlightVehicle(motorized()));
 }

 @Test void nullDefinitionIsNotClassifiedAsFlight() {
  assertFalse(Classification.isFlightVehicle(null));
 }

 @Test void activelyTowedVehicleIsExcludedEvenIfOtherwiseEligible() {
  assertFalse(Classification.eligible(motorized(), List.of(wheel()), NO_PROPELLERS, true, false));
 }

 @Test void pontoonOnlyHullWithNoWheelOrTrackIsExcluded() {
  // A boat hull: floats, but has no wheel/track device this bridge's ground solver can drive.
  assertFalse(Classification.eligible(motorized(), List.of(pontoon()), NO_PROPELLERS, false, false));
 }

 @Test void pontoonOnlyHullIsFloatEligible() {
  assertTrue(Classification.floatEligible(motorized(), List.of(pontoon()), NO_PROPELLERS, false, false));
 }

 @Test void multiplePontoonsAreFloatEligible() {
  assertTrue(Classification.floatEligible(motorized(), List.of(pontoon(), pontoon()), NO_PROPELLERS, false, false));
 }

 @Test void aVehicleWithAWheelIsNotFloatEligibleEvenIfItAlsoHasAFloat() {
  // An amphibious car stays on the ground solver via eligible(); floatEligible() is the
  // exclusive fallback for a hull with nothing the ground solver can drive.
  assertFalse(Classification.floatEligible(motorized(), List.of(wheel(), pontoon()), NO_PROPELLERS, false, false));
 }

 @Test void skidWithNoCanFloatIsNotFloatEligible() {
  // A grounded skid helicopter: no wheel/track, and its skid does not float either.
  var skid = new JSONPart.JSONPartGroundDevice();
  assertFalse(Classification.floatEligible(motorized(), List.of(skid), NO_PROPELLERS, false, false));
 }

 @Test void floatOnlyHullWithAnInstalledRotorPropellerIsExcluded() {
  // A pontoon/skid rotorcraft with no isAircraft/isBlimp flag set: eligible() now claims this
  // vehicle directly via isRotorcraft, and floatEligible's own rotor check independently keeps
  // it excluded too - IV's rotor-lift/thrust-vectoring solver is a different, unmodeled physics
  // domain regardless of which other domain the chassis would otherwise qualify for.
  assertFalse(Classification.floatEligible(motorized(), List.of(pontoon()), List.of(rotor()), false, false));
 }

 @Test void floatOnlyHullWithAnOrdinaryPropellerStaysFloatEligible() {
  assertTrue(Classification.floatEligible(motorized(), List.of(pontoon()), List.of(forwardPropeller()), false, false));
 }

 @Test void floatEquippedRotorcraftIsEligibleNeverFloatEligible() {
  // The combined rotor+float case (an amphibious helicopter): eligible() claims it directly via
  // isRotorcraft (true, regardless of the aircraft/blimp flag), and floatEligible's own rotor
  // guard independently keeps it false - the two classifications are mutually exclusive for
  // every input, so PhysicsBridge.active() can never pick both a Body and a BuoyantBody for the
  // same vehicle. PhysicsBridge.Body still applies BuoyantForces for this vehicle's float
  // devices alongside its rotor forces - see PhysicsBridge.Body.floatCapable.
  var v = motorized();
  v.motorized.isAircraft = true;
  var installedGroundDevices = List.of(pontoon());
  var installedPropellers = List.of(rotor());
  assertTrue(Classification.eligible(v, installedGroundDevices, installedPropellers, false, false));
  assertFalse(Classification.floatEligible(v, installedGroundDevices, installedPropellers, false, false));
 }

 @Test void floatEligibleExcludesAircraftBlimpTowedAndOptedOut() {
  var aircraft = motorized();
  aircraft.motorized.isAircraft = true;
  assertFalse(Classification.floatEligible(aircraft, List.of(pontoon()), NO_PROPELLERS, false, false));

  var blimp = motorized();
  blimp.motorized.isBlimp = true;
  assertFalse(Classification.floatEligible(blimp, List.of(pontoon()), NO_PROPELLERS, false, false));

  assertFalse(Classification.floatEligible(motorized(), List.of(pontoon()), NO_PROPELLERS, true, false));
  assertFalse(Classification.floatEligible(motorized(), List.of(pontoon()), NO_PROPELLERS, false, true));
 }

 @Test void floatEligibleWithNoGroundDeviceAtAllIsExcluded() {
  assertFalse(Classification.floatEligible(motorized(), List.of(), NO_PROPELLERS, false, false));
 }

 @Test void floatEligibleOnNullDefinitionIsExcludedNotACrash() {
  assertFalse(Classification.floatEligible(null, List.of(pontoon()), NO_PROPELLERS, false, false));
 }

 @Test void noGroundDeviceAtAllIsExcluded() {
  assertFalse(Classification.eligible(motorized(), List.of(), NO_PROPELLERS, false, false));
 }

 @Test void missingMotorizedSectionIsExcludedNotACrash() {
  var v = new JSONVehicle();
  assertFalse(Classification.eligible(v, List.of(wheel()), NO_PROPELLERS, false, false));
 }

 @Test void nullDefinitionIsExcludedNotACrash() {
  assertFalse(Classification.eligible(null, List.of(wheel()), NO_PROPELLERS, false, false));
 }

 @Test void nullGroundEntryAmongPartsIsIgnoredNotACrash() {
  assertFalse(Classification.isDrivableGroundDevice(null));
  assertTrue(Classification.eligible(motorized(), Arrays.<JSONPart.JSONPartGroundDevice>asList(null, wheel()), NO_PROPELLERS, false, false));
 }
}
