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
   assertTrue(Classification.eligible(motorized(), Collections.nCopies(wheels, wheel()), false, false), wheels + " wheels");
  }
 }

 @Test void tracksAreEligibleWithoutAnyWheel() {
  assertTrue(Classification.eligible(motorized(), List.of(tread(), tread()), false, false));
 }

 @Test void mixedWheelsAndTracksAreEligible() {
  assertTrue(Classification.eligible(motorized(), List.of(wheel(), tread()), false, false));
 }

 @Test void noOverrideEntryStillDefaultsToEligible() {
  // "Every vehicle by default" is the point: a plain wheeled car with no config entry at all
  // (optedOut=false, the lookup-miss case) must still get physics.
  assertTrue(Classification.eligible(motorized(), List.of(wheel()), false, false));
 }

 @Test void explicitOptOutExcludesAnOtherwiseEligibleCar() {
  assertFalse(Classification.eligible(motorized(), List.of(wheel()), false, true));
 }

 @Test void aircraftIsEligibleWithNoGroundDeviceAtAll() {
  var v = motorized();
  v.motorized.isAircraft = true;
  assertTrue(Classification.eligible(v, List.of(), false, false));
  assertTrue(Classification.isFlightVehicle(v));
 }

 @Test void fixedWingAircraftWithAForwardPropellerIsStillEligible() {
  var v = motorized();
  v.motorized.isAircraft = true;
  assertTrue(Classification.eligible(v, List.of(), false, false));
  assertFalse(Classification.isRotorcraft(List.of(forwardPropeller())));
 }

 @Test void rotorcraftIsEligibleAsAnAircraft() {
  // A helicopter: IV marks its propeller isRotor and gives it its own thrust-vectoring solver.
  // This bridge now replaces that with RotorForces instead of the fixed-wing model - a
  // rotorcraft stays eligible as an aircraft, it just needs PhysicsBridge.Body to pick the
  // matching model, which isRotorcraft (below) is what that choice is based on.
  var v = motorized();
  v.motorized.isAircraft = true;
  assertTrue(Classification.eligible(v, List.of(), false, false));
  assertTrue(Classification.isRotor(rotor()));
  assertTrue(Classification.isRotorcraft(List.of(rotor())));
 }

 @Test void rotorAmongMultiplePropellersStillMarksRotorcraft() {
  assertTrue(Classification.isRotorcraft(List.of(forwardPropeller(), rotor())));
 }

 @Test void noRotorAmongPropellersIsNotRotorcraft() {
  assertFalse(Classification.isRotorcraft(List.of(forwardPropeller(), forwardPropeller())));
  assertFalse(Classification.isRotorcraft(List.of()));
 }

 @Test void nullPropellerEntryIsIgnoredNotACrash() {
  assertFalse(Classification.isRotor(null));
  assertFalse(Classification.isRotorcraft(Arrays.<JSONPart.JSONPartPropeller>asList(null, forwardPropeller())));
  assertTrue(Classification.isRotorcraft(Arrays.<JSONPart.JSONPartPropeller>asList(null, rotor())));
 }

 @Test void blimpIsEligibleWithNoGroundDeviceAtAll() {
  var v = motorized();
  v.motorized.isBlimp = true;
  assertTrue(Classification.eligible(v, List.of(), false, false));
  assertTrue(Classification.isFlightVehicle(v));
 }

 @Test void aircraftOptOutStillExcludes() {
  var v = motorized();
  v.motorized.isAircraft = true;
  assertFalse(Classification.eligible(v, List.of(), false, true));
 }

 @Test void activelyTowedAircraftIsExcluded() {
  var v = motorized();
  v.motorized.isAircraft = true;
  assertFalse(Classification.eligible(v, List.of(), true, false));
 }

 @Test void groundVehicleIsNotClassifiedAsFlight() {
  assertFalse(Classification.isFlightVehicle(motorized()));
 }

 @Test void nullDefinitionIsNotClassifiedAsFlight() {
  assertFalse(Classification.isFlightVehicle(null));
 }

 @Test void activelyTowedVehicleIsExcludedEvenIfOtherwiseEligible() {
  assertFalse(Classification.eligible(motorized(), List.of(wheel()), true, false));
 }

 @Test void pontoonOnlyHullWithNoWheelOrTrackIsExcluded() {
  // A boat hull: floats, but has no wheel/track device this bridge's ground solver can drive.
  assertFalse(Classification.eligible(motorized(), List.of(pontoon()), false, false));
 }

 @Test void noGroundDeviceAtAllIsExcluded() {
  assertFalse(Classification.eligible(motorized(), List.of(), false, false));
 }

 @Test void missingMotorizedSectionIsExcludedNotACrash() {
  var v = new JSONVehicle();
  assertFalse(Classification.eligible(v, List.of(wheel()), false, false));
 }

 @Test void nullDefinitionIsExcludedNotACrash() {
  assertFalse(Classification.eligible(null, List.of(wheel()), false, false));
 }

 @Test void nullGroundEntryAmongPartsIsIgnoredNotACrash() {
  assertFalse(Classification.isDrivableGroundDevice(null));
  assertTrue(Classification.eligible(motorized(), Arrays.<JSONPart.JSONPartGroundDevice>asList(null, wheel()), false, false));
 }
}
