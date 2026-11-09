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

 @Test void activelyTowedGroundVehicleIsNowEligibleForItsOwnHitchedBody() {
  // MINECRAFT-498: a trailer is eligible on the same terms as any other ground vehicle;
  // PhysicsBridge (not this pure classification) is what further requires its towing vehicle to
  // already have an active Sable body before actually constructing one for it.
  assertTrue(Classification.eligible(motorized(), List.of(wheel()), NO_PROPELLERS, true, false));
 }

 @Test void activelyTowedGroundVehicleOptOutStillExcludes() {
  assertFalse(Classification.eligible(motorized(), List.of(wheel()), NO_PROPELLERS, true, true));
 }

 @Test void pontoonOnlyHullWithNoWheelOrTrackIsExcluded() {
  // A boat hull: floats, but has no wheel/track device this bridge's ground solver can drive.
  assertFalse(Classification.eligible(motorized(), List.of(pontoon()), NO_PROPELLERS, false, false));
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
