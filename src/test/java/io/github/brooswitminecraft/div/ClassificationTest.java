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
 }

 @Test void rotorcraftIsExcludedEvenThoughItIsAnAircraft() {
  // A helicopter: IV marks its propeller isRotor and gives it its own thrust-vectoring solver
  // this bridge does not replicate - it must stay on IV's native physics, not get zero wing
  // lift and forward-only thrust.
  var v = motorized();
  v.motorized.isAircraft = true;
  assertFalse(Classification.eligible(v, List.of(), List.of(rotor()), false, false));
  assertTrue(Classification.isRotor(rotor()));
 }

 @Test void rotorAmongMultiplePropellersStillExcludes() {
  var v = motorized();
  v.motorized.isAircraft = true;
  assertFalse(Classification.eligible(v, List.of(), List.of(forwardPropeller(), rotor()), false, false));
 }

 @Test void nullPropellerEntryIsIgnoredNotACrash() {
  assertFalse(Classification.isRotor(null));
  var v = motorized();
  v.motorized.isAircraft = true;
  assertTrue(Classification.eligible(v, List.of(), Arrays.<JSONPart.JSONPartPropeller>asList(null, forwardPropeller()), false, false));
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
  // A pontoon/skid rotorcraft: IV's rotor-lift/thrust-vectoring solver is a different,
  // unmodeled physics domain regardless of which other domain the chassis would otherwise
  // qualify for - the same exclusion eligible() applies for a flight vehicle's rotor.
  assertFalse(Classification.floatEligible(motorized(), List.of(pontoon()), List.of(rotor()), false, false));
 }

 @Test void floatOnlyHullWithAnOrdinaryPropellerStaysFloatEligible() {
  assertTrue(Classification.floatEligible(motorized(), List.of(pontoon()), List.of(forwardPropeller()), false, false));
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
