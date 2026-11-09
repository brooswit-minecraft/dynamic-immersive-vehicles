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

 @Test void aircraftIsExcluded() {
  var v = motorized();
  v.motorized.isAircraft = true;
  assertFalse(Classification.eligible(v, List.of(wheel()), false, false));
 }

 @Test void blimpIsExcluded() {
  var v = motorized();
  v.motorized.isBlimp = true;
  assertFalse(Classification.eligible(v, List.of(), false, false));
 }

 @Test void activelyTowedVehicleIsExcludedEvenIfOtherwiseEligible() {
  assertFalse(Classification.eligible(motorized(), List.of(wheel()), true, false));
 }

 @Test void pontoonOnlyHullWithNoWheelOrTrackIsExcluded() {
  // A boat hull: floats, but has no wheel/track device this bridge's ground solver can drive.
  assertFalse(Classification.eligible(motorized(), List.of(pontoon()), false, false));
 }

 @Test void pontoonOnlyHullIsFloatEligible() {
  assertTrue(Classification.floatEligible(motorized(), List.of(pontoon()), false, false));
 }

 @Test void multiplePontoonsAreFloatEligible() {
  assertTrue(Classification.floatEligible(motorized(), List.of(pontoon(), pontoon()), false, false));
 }

 @Test void aVehicleWithAWheelIsNotFloatEligibleEvenIfItAlsoHasAFloat() {
  // An amphibious car stays on the ground solver via eligible(); floatEligible() is the
  // exclusive fallback for a hull with nothing the ground solver can drive.
  assertFalse(Classification.floatEligible(motorized(), List.of(wheel(), pontoon()), false, false));
 }

 @Test void skidWithNoCanFloatIsNotFloatEligible() {
  // A grounded skid helicopter: no wheel/track, and its skid does not float either.
  var skid = new JSONPart.JSONPartGroundDevice();
  assertFalse(Classification.floatEligible(motorized(), List.of(skid), false, false));
 }

 @Test void floatEligibleExcludesAircraftBlimpTowedAndOptedOut() {
  var aircraft = motorized();
  aircraft.motorized.isAircraft = true;
  assertFalse(Classification.floatEligible(aircraft, List.of(pontoon()), false, false));

  var blimp = motorized();
  blimp.motorized.isBlimp = true;
  assertFalse(Classification.floatEligible(blimp, List.of(pontoon()), false, false));

  assertFalse(Classification.floatEligible(motorized(), List.of(pontoon()), true, false));
  assertFalse(Classification.floatEligible(motorized(), List.of(pontoon()), false, true));
 }

 @Test void floatEligibleWithNoGroundDeviceAtAllIsExcluded() {
  assertFalse(Classification.floatEligible(motorized(), List.of(), false, false));
 }

 @Test void floatEligibleOnNullDefinitionIsExcludedNotACrash() {
  assertFalse(Classification.floatEligible(null, List.of(pontoon()), false, false));
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
