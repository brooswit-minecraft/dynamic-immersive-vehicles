package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PhysicsBridge.applyLiveSafeFields}/{@code reportFieldsNeedingRespawn} are the literal
 * per-field decision {@code reloadOverrides} makes for every already-active vehicle body - which
 * {@code Overrides.Tuning} fields are safe to mutate in place (because every real {@code Body}/
 * {@code BuoyantBody} tick/call re-reads them fresh from the same live object, confirmed by
 * direct inspection of every {@code tuning.X} read site in PhysicsBridge.java/BuoyantForces.java)
 * versus construction-cached ones that need a respawn instead. A live {@code Body}/Sable runtime
 * cannot be constructed in a unit test (see VERIFICATION.md), so this exercises the exact same
 * (package-private) methods against plain {@code Overrides.Tuning} instances instead - no
 * stand-in duplicate logic.
 */
class PhysicsBridgeLiveOverrideApplicationTest {
 @Test void everyTickReadFieldIsAppliedLiveAndMutatesTheTargetInPlace() {
  var target = Overrides.defaultTuning(); // starts at FALLBACK values
  var fresh = Overrides.defaultTuning();
  fresh.suspensionTravel = 0.5; fresh.friction = 1.5; fresh.driveForce = 9000.0; fresh.brakeForce = 13000.0;
  fresh.maxSteerDegrees = 35.0; fresh.lateralGripMultiplier = 0.7; fresh.waterDrag = 450.0; fresh.propellerForce = 7000.0;
  // MINECRAFT-544: marineSteerDegrees must go through this same live-mutation path too.
  fresh.marineSteerDegrees = 18.0;
  fresh.angularWaterDrag = 50.0; fresh.antiRollStiffness = 0.4;
  fresh.stallAngleDegrees = 12.0; fresh.liftSlope = 5.0; fresh.controlSurfaceSlope = 4.0;
  // MINECRAFT-541: the three new fixed-wing per-axis authority fields must go through this same
  // live-mutation path too.
  fresh.aileronAuthorityScale = 1.2; fresh.elevatorAuthorityScale = 0.8; fresh.rudderAuthorityScale = 1.4;
  fresh.brakeBiasFront = 0.65;
  fresh.driveBiasFront = 0.3;
  // MINECRAFT-522: the three new rotor fields must go through this same live-mutation path -
  // John's review (comment 53751) flagged that this test exercised every pre-existing live-safe
  // field but never changed/asserted these.
  fresh.cyclicLimitDegrees = 12.0; fresh.cyclicAuthorityScale = 1.4; fresh.yawAuthorityScale = 0.6;
  // MINECRAFT-523: suspensionDampingRatio must go through this same live-mutation path too.
  fresh.suspensionDampingRatio = 1.1;
  // MINECRAFT-536: parasiticDragMultiplier/oswaldEfficiency must go through this same
  // live-mutation path too.
  fresh.parasiticDragMultiplier = 1.3; fresh.oswaldEfficiency = 0.65;
  // MINECRAFT-537: rotorAngularDampingCoefficient must go through this same live-mutation path too.
  fresh.rotorAngularDampingCoefficient = 3.5;
  // MINECRAFT-542: fixedWingAngularDampingCoefficient must go through this same live-mutation
  // path too - a wholly separate field from rotorAngularDampingCoefficient above.
  fresh.fixedWingAngularDampingCoefficient = 2.5;
  // MINECRAFT-543: rotorAirDragCoefficient must go through this same live-mutation path too.
  fresh.rotorAirDragCoefficient = 0.9;
  // MINECRAFT-533: toeFrontDegrees/toeRearDegrees must go through this same live-mutation path.
  fresh.toeFrontDegrees = 2.5; fresh.toeRearDegrees = -1.5;
  // MINECRAFT-532: axleDriveForceSharing must go through this same live-mutation path too.
  fresh.axleDriveForceSharing = true;
  // MINECRAFT-540: ackermannSteering must go through this same live-mutation path too.
  fresh.ackermannSteering = true;
  // MINECRAFT-535: longitudinal/lateral/verticalWaterDragMultiplier must go through this same
  // live-mutation path too (Wroosbit review, comment 54117: the original revision added the
  // PhysicsBridge.applyLiveSafeFields mutations but never actually exercised them here, so the
  // test stayed green even if those lines were removed).
  fresh.longitudinalWaterDragMultiplier = 2.5; fresh.lateralWaterDragMultiplier = 0.4; fresh.verticalWaterDragMultiplier = 3.0;
  // MINECRAFT-539: suspensionDampingRatioFront/Rear and suspensionSpringMultiplierFront/Rear
  // must go through this same live-mutation path too.
  fresh.suspensionDampingRatioFront = 1.2; fresh.suspensionDampingRatioRear = 0.9;
  fresh.suspensionSpringMultiplierFront = 1.8; fresh.suspensionSpringMultiplierRear = 0.75;
  // MINECRAFT-553: gearRatioAware must go through this same live-mutation path too.
  fresh.gearRatioAware = true;
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertEquals(0.5, target.suspensionTravel); assertEquals(1.5, target.friction); assertEquals(9000.0, target.driveForce);
  assertEquals(13000.0, target.brakeForce); assertEquals(35.0, target.maxSteerDegrees); assertEquals(0.7, target.lateralGripMultiplier);
  assertEquals(450.0, target.waterDrag); assertEquals(7000.0, target.propellerForce); assertEquals(50.0, target.angularWaterDrag);
  assertEquals(18.0, target.marineSteerDegrees);
  assertEquals(0.4, target.antiRollStiffness);
  assertEquals(12.0, target.stallAngleDegrees); assertEquals(5.0, target.liftSlope); assertEquals(4.0, target.controlSurfaceSlope);
  assertEquals(1.2, target.aileronAuthorityScale); assertEquals(0.8, target.elevatorAuthorityScale); assertEquals(1.4, target.rudderAuthorityScale);
  assertEquals(0.65, target.brakeBiasFront);
  assertEquals(0.3, target.driveBiasFront);
  assertEquals(12.0, target.cyclicLimitDegrees); assertEquals(1.4, target.cyclicAuthorityScale); assertEquals(0.6, target.yawAuthorityScale);
  assertEquals(1.1, target.suspensionDampingRatio);
  assertEquals(1.3, target.parasiticDragMultiplier); assertEquals(0.65, target.oswaldEfficiency);
  assertEquals(3.5, target.rotorAngularDampingCoefficient);
  assertEquals(2.5, target.fixedWingAngularDampingCoefficient);
  assertEquals(0.9, target.rotorAirDragCoefficient);
  assertEquals(2.5, target.toeFrontDegrees); assertEquals(-1.5, target.toeRearDegrees);
  assertEquals(true, target.axleDriveForceSharing);
  assertEquals(true, target.ackermannSteering);
  assertEquals(2.5, target.longitudinalWaterDragMultiplier); assertEquals(0.4, target.lateralWaterDragMultiplier); assertEquals(3.0, target.verticalWaterDragMultiplier);
  assertEquals(1.2, target.suspensionDampingRatioFront); assertEquals(0.9, target.suspensionDampingRatioRear);
  assertEquals(1.8, target.suspensionSpringMultiplierFront); assertEquals(0.75, target.suspensionSpringMultiplierRear);
  assertEquals(true, target.gearRatioAware);
  assertEquals(Set.of("suspensionTravel","friction","driveForce","brakeForce","maxSteerDegrees","marineSteerDegrees","lateralGripMultiplier","waterDrag","propellerForce","angularWaterDrag","antiRollStiffness","stallAngleDegrees","liftSlope","controlSurfaceSlope","aileronAuthorityScale","elevatorAuthorityScale","rudderAuthorityScale","brakeBiasFront","driveBiasFront",
      "cyclicLimitDegrees","cyclicAuthorityScale","yawAuthorityScale","suspensionDampingRatio","parasiticDragMultiplier","oswaldEfficiency","rotorAngularDampingCoefficient","fixedWingAngularDampingCoefficient","rotorAirDragCoefficient","toeFrontDegrees","toeRearDegrees","axleDriveForceSharing","ackermannSteering","gearRatioAware",
      "longitudinalWaterDragMultiplier","lateralWaterDragMultiplier","verticalWaterDragMultiplier",
      "suspensionDampingRatioFront","suspensionDampingRatioRear","suspensionSpringMultiplierFront","suspensionSpringMultiplierRear"),
    new TreeSet<>(applied));
 }

 @Test void removingADragTuningOverrideLiveAppliesBackDownToItsDocumentedDefault() {
  // Same "revert to documented default on removal" requirement as the rotor/suspension fields
  // above - an operator who removes a previously-configured parasiticDragMultiplier/
  // oswaldEfficiency entry must see the live vehicle actually settle back to the FALLBACK
  // (1.0/0.8), not get stuck at its last explicit value.
  var target = Overrides.defaultTuning();
  target.parasiticDragMultiplier = 1.8; target.oswaldEfficiency = 0.6;
  var fresh = Overrides.defaultTuning(); // no override at all -> FALLBACK defaults
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertEquals(1.0, target.parasiticDragMultiplier, "must revert to the documented no-op 1.0 default once the override is removed");
  assertEquals(FlightForces.DEFAULT_OSWALD_EFFICIENCY, target.oswaldEfficiency, "must revert to FlightForces's own previously-hardcoded 0.8 once the override is removed");
  assertTrue(applied.containsAll(List.of("parasiticDragMultiplier","oswaldEfficiency")),
    "the revert-to-default must itself be reported as an applied live change");
 }

 @Test void removingASuspensionDampingRatioOverrideLiveAppliesBackDownToItsDocumentedDefault() {
  // Same "revert to documented default on removal" requirement as the rotor fields above - an
  // operator who removes a previously-configured suspensionDampingRatio entry must see the live
  // vehicle actually settle back to 0.7, not get stuck at its last explicit value.
  var target = Overrides.defaultTuning();
  target.suspensionDampingRatio = 1.3;
  var fresh = Overrides.defaultTuning(); // no override at all -> FALLBACK default (0.7)
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertEquals(0.7, target.suspensionDampingRatio, "must revert to the documented 0.7 default once the override is removed");
  assertTrue(applied.contains("suspensionDampingRatio"), "the revert-to-default must itself be reported as an applied live change");
 }

 @Test void removingARotorFieldOverrideLiveAppliesBackDownToItsDocumentedDefault() {
  // MINECRAFT-522 (John's review, comment 53751): an operator who previously configured these
  // three fields and then removes that config entry entirely must see the live vehicle actually
  // fall back to the documented default (20deg/1.0/1.0) on the next reload - not get stuck at
  // its last explicit value. `fresh` here is exactly what Overrides.resolved() returns for an
  // unconfigured vehicle (the FALLBACK level) - simulating the override having been removed.
  var target = Overrides.defaultTuning();
  target.cyclicLimitDegrees = 12.0; target.cyclicAuthorityScale = 1.4; target.yawAuthorityScale = 0.6;
  var fresh = Overrides.defaultTuning(); // no override at all -> FALLBACK defaults
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertEquals(20.0, target.cyclicLimitDegrees, "must revert to the documented 20-degree default once the override is removed");
  assertEquals(1.0, target.cyclicAuthorityScale, "must revert to unit scaling once the override is removed");
  assertEquals(1.0, target.yawAuthorityScale, "must revert to unit scaling once the override is removed");
  assertTrue(applied.containsAll(List.of("cyclicLimitDegrees","cyclicAuthorityScale","yawAuthorityScale")),
    "the revert-to-default must itself be reported as an applied live change");
 }

 @Test void removingAnAxisAuthorityScaleOverrideLiveAppliesBackDownToItsDocumentedDefault() {
  // MINECRAFT-541: same "revert to documented default on removal" requirement as every other
  // live-safe field above - an operator who previously configured these three independently and
  // then removes that config entry entirely must see the live vehicle actually fall back to the
  // documented 1.0 no-op default on the next reload, not get stuck at its last explicit value.
  var target = Overrides.defaultTuning();
  target.aileronAuthorityScale = 2.0; target.elevatorAuthorityScale = 0.3; target.rudderAuthorityScale = 4.0;
  var fresh = Overrides.defaultTuning(); // no override at all -> FALLBACK defaults (1.0 each)
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertEquals(1.0, target.aileronAuthorityScale, "must revert to the documented no-op 1.0 default once the override is removed");
  assertEquals(1.0, target.elevatorAuthorityScale, "must revert to the documented no-op 1.0 default once the override is removed");
  assertEquals(1.0, target.rudderAuthorityScale, "must revert to the documented no-op 1.0 default once the override is removed");
  assertTrue(applied.containsAll(List.of("aileronAuthorityScale","elevatorAuthorityScale","rudderAuthorityScale")),
    "the revert-to-default must itself be reported as an applied live change");
 }

 @Test void removingARotorAngularDampingCoefficientOverrideLiveAppliesBackDownToOffDefault() {
  // MINECRAFT-537: same "revert to documented default on removal" requirement as the
  // MINECRAFT-522 rotor fields above - an operator who previously configured this and then
  // removes that config entry entirely must see the live vehicle actually fall back to the
  // documented off default (0.0) on the next reload, the zero/off-parity hot-reload requirement.
  var target = Overrides.defaultTuning();
  target.rotorAngularDampingCoefficient = 3.5;
  var fresh = Overrides.defaultTuning(); // no override at all -> FALLBACK default (0.0)
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertEquals(0.0, target.rotorAngularDampingCoefficient, "must revert to the documented off (0.0) default once the override is removed");
  assertTrue(applied.contains("rotorAngularDampingCoefficient"), "the revert-to-default must itself be reported as an applied live change");
 }

 @Test void removingAFixedWingAngularDampingCoefficientOverrideLiveAppliesBackDownToOffDefault() {
  // MINECRAFT-542: same "revert to documented default on removal" requirement as
  // rotorAngularDampingCoefficient (MINECRAFT-537) above, for the wholly separate fixed-wing
  // field - an operator who previously configured this and then removes that config entry
  // entirely must see the live vehicle actually fall back to the documented off default (0.0) on
  // the next reload.
  var target = Overrides.defaultTuning();
  target.fixedWingAngularDampingCoefficient = 2.5;
  var fresh = Overrides.defaultTuning(); // no override at all -> FALLBACK default (0.0)
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertEquals(0.0, target.fixedWingAngularDampingCoefficient, "must revert to the documented off (0.0) default once the override is removed");
  assertTrue(applied.contains("fixedWingAngularDampingCoefficient"), "the revert-to-default must itself be reported as an applied live change");
 }

 @Test void removingARotorAirDragCoefficientOverrideLiveAppliesBackDownToOffDefault() {
  // MINECRAFT-543: same "revert to documented default on removal" requirement as
  // rotorAngularDampingCoefficient above - an operator who previously configured this and then
  // removes that config entry entirely must see the live vehicle actually fall back to the
  // documented off default (0.0) on the next reload, the zero/off-parity hot-reload requirement.
  var target = Overrides.defaultTuning();
  target.rotorAirDragCoefficient = 0.9;
  var fresh = Overrides.defaultTuning(); // no override at all -> FALLBACK default (0.0)
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertEquals(0.0, target.rotorAirDragCoefficient, "must revert to the documented off (0.0) default once the override is removed");
  assertTrue(applied.contains("rotorAirDragCoefficient"), "the revert-to-default must itself be reported as an applied live change");
 }

 @Test void removingAToeOverrideLiveAppliesBackDownToTheUnconfiguredNullNoOp() {
  // Same "no FALLBACK entry" shape as brakeBiasFront/driveBiasFront - an operator who removes a
  // previously-configured toe entry must see the live vehicle actually go back to null (no toe
  // applied), not get stuck at its last explicit value.
  var target = Overrides.defaultTuning();
  target.toeFrontDegrees = 3.0; target.toeRearDegrees = -2.0;
  var fresh = Overrides.defaultTuning(); // no override at all -> stays null, same as unconfigured
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertNull(target.toeFrontDegrees, "must revert to unconfigured (null, no toe applied) once the override is removed");
  assertNull(target.toeRearDegrees, "must revert to unconfigured (null, no toe applied) once the override is removed");
  assertTrue(applied.containsAll(List.of("toeFrontDegrees","toeRearDegrees")), "the revert-to-unconfigured must itself be reported as an applied live change");
 }

 @Test void removingAnAckermannSteeringOverrideLiveAppliesBackDownToOffDefault() {
  // MINECRAFT-540: same "revert to documented default on removal" requirement as
  // axleDriveForceSharing above - an operator who previously opted in and then removes that
  // config entry entirely must see the live vehicle actually fall back to the uniform pre-ticket
  // steer angle on the next reload (null/false), not get stuck opted in.
  var target = Overrides.defaultTuning();
  target.ackermannSteering = true;
  var fresh = Overrides.defaultTuning(); // no override at all -> stays null, same as unconfigured
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertNull(target.ackermannSteering, "must revert to unconfigured (null, uniform steer angle) once the override is removed");
  assertTrue(applied.contains("ackermannSteering"), "the revert-to-unconfigured must itself be reported as an applied live change");
 }

 @Test void removingAGearRatioAwareOverrideLiveAppliesBackDownToOffDefault() {
  // MINECRAFT-553: same "revert to documented default on removal" requirement as
  // axleDriveForceSharing/ackermannSteering above - an operator who previously opted in and then
  // removes that config entry entirely must see the live vehicle actually fall back to the
  // pre-ticket uniform-across-forward-gears drive force on the next reload (null/false), not get
  // stuck opted in.
  var target = Overrides.defaultTuning();
  target.gearRatioAware = true;
  var fresh = Overrides.defaultTuning(); // no override at all -> stays null, same as unconfigured
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertNull(target.gearRatioAware, "must revert to unconfigured (null, every forward gear produces the same drive-force magnitude) once the override is removed");
  assertTrue(applied.contains("gearRatioAware"), "the revert-to-unconfigured must itself be reported as an applied live change");
 }

 @Test void removingADirectionalWaterDragMultiplierOverrideLiveAppliesBackDownToItsDocumentedDefault() {
  // MINECRAFT-535: same "revert to documented default on removal" requirement as every other
  // live-safe field above - an operator who removes a previously-configured longitudinal/
  // lateral/verticalWaterDragMultiplier entry must see the live vehicle actually settle back to
  // the FALLBACK (1.0, the isotropic no-op), not get stuck at its last explicit value.
  var target = Overrides.defaultTuning();
  target.longitudinalWaterDragMultiplier = 2.5; target.lateralWaterDragMultiplier = 0.4; target.verticalWaterDragMultiplier = 3.0;
  var fresh = Overrides.defaultTuning(); // no override at all -> FALLBACK defaults (1.0 each)
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertEquals(1.0, target.longitudinalWaterDragMultiplier, "must revert to the documented no-op 1.0 default once the override is removed");
  assertEquals(1.0, target.lateralWaterDragMultiplier, "must revert to the documented no-op 1.0 default once the override is removed");
  assertEquals(1.0, target.verticalWaterDragMultiplier, "must revert to the documented no-op 1.0 default once the override is removed");
  assertTrue(applied.containsAll(List.of("longitudinalWaterDragMultiplier","lateralWaterDragMultiplier","verticalWaterDragMultiplier")),
    "the revert-to-default must itself be reported as an applied live change");
 }

 @Test void removingAMarineSteerDegreesOverrideLiveAppliesBackDownToTheInheritedMaxSteerDegrees() {
  // MINECRAFT-544: marineSteerDegrees has no flat FALLBACK of its own - an operator who removes a
  // previously-configured marineSteerDegrees entry must see the live vehicle's propeller steering
  // actually fall back to inheriting that SAME (possibly also-configured) maxSteerDegrees, not
  // get stuck at its last explicit value.
  var target = Overrides.defaultTuning();
  target.maxSteerDegrees = 40.0; target.marineSteerDegrees = 12.0;
  var fresh = Overrides.resolved("packid:not_configured"); // no marineSteerDegrees override -> inherits maxSteerDegrees (30.0 FALLBACK here)
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertEquals(30.0, target.marineSteerDegrees, "must revert to inheriting the resolved maxSteerDegrees (the FALLBACK, 30.0) once the override is removed");
  assertEquals(30.0, target.maxSteerDegrees, "fresh's own maxSteerDegrees (the FALLBACK) must also apply live, same as any other reload");
  assertTrue(applied.containsAll(List.of("marineSteerDegrees","maxSteerDegrees")), "the revert-to-inherited must itself be reported as an applied live change");
 }

 @Test void removingAnAxleSuspensionOverrideLiveAppliesBackDownToTheUnconfiguredNullNoOp() {
  // MINECRAFT-539: same "no FALLBACK entry" shape as toeFrontDegrees/toeRearDegrees above - an
  // operator who removes a previously-configured axle-specific spring/damping entry must see the
  // live vehicle actually go back to null (inherit the vehicle-wide base value via
  // AxleSuspensionDistribution), not get stuck at its last explicit axle-specific value.
  var target = Overrides.defaultTuning();
  target.suspensionDampingRatioFront = 1.2; target.suspensionDampingRatioRear = 0.9;
  target.suspensionSpringMultiplierFront = 1.8; target.suspensionSpringMultiplierRear = 0.75;
  var fresh = Overrides.defaultTuning(); // no override at all -> stays null, same as unconfigured
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertNull(target.suspensionDampingRatioFront, "must revert to unconfigured (null, inherit base ratio) once the override is removed");
  assertNull(target.suspensionDampingRatioRear, "must revert to unconfigured (null, inherit base ratio) once the override is removed");
  assertNull(target.suspensionSpringMultiplierFront, "must revert to unconfigured (null, inherit base multiplier) once the override is removed");
  assertNull(target.suspensionSpringMultiplierRear, "must revert to unconfigured (null, inherit base multiplier) once the override is removed");
  assertTrue(applied.containsAll(List.of("suspensionDampingRatioFront","suspensionDampingRatioRear","suspensionSpringMultiplierFront","suspensionSpringMultiplierRear")),
    "the revert-to-unconfigured must itself be reported as an applied live change");
 }

 @Test void unchangedFieldsAreNotReportedAsApplied() {
  var target = Overrides.defaultTuning();
  var fresh = Overrides.defaultTuning(); // identical values
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertTrue(applied.isEmpty(), "nothing actually changed, so nothing should be reported as applied");
 }

 @Test void constructionCachedFieldsAreNeverMutatedByApplyLiveSafeFields() {
  var target = Overrides.defaultTuning(); target.massKg = 500.0; target.hullFillFactor = 0.5; target.upperMassFraction = 0.5;
  var fresh = Overrides.defaultTuning(); fresh.massKg = 999.0; fresh.hullFillFactor = 0.9; fresh.upperMassFraction = 0.2;
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertEquals(500.0, target.massKg, "massKg is construction-cached (mass) - applyLiveSafeFields must never touch it");
  assertEquals(0.5, target.hullFillFactor, "hullFillFactor is construction-cached (hullVolume) - must never be live-mutated");
  assertEquals(0.5, target.upperMassFraction, "upperMassFraction is construction-cached (twoBodyConfig) - must never be live-mutated");
 }

 @Test void reportFieldsNeedingRespawnNamesEveryConstructionCachedFieldThatActuallyChanged() {
  var target = Overrides.defaultTuning(); target.massKg = 500.0;
  var fresh = Overrides.defaultTuning(); fresh.massKg = 999.0; fresh.hullFillFactor = 0.9; fresh.upperMassFraction = 0.2;
  fresh.selfRightStrength = 200.0; fresh.selfRightDamping = 80.0; fresh.selfRightMaxTorque = 600.0;
  fresh.selfRightFadeStartDegrees = 10.0; fresh.selfRightFadeEndDegrees = 50.0;
  Set<String> pending = new TreeSet<>();
  PhysicsBridge.reportFieldsNeedingRespawn(target, fresh, pending);
  assertEquals(Set.of("massKg","hullFillFactor","upperMassFraction","selfRight*"), pending);
 }

 @Test void reportFieldsNeedingRespawnReportsNothingWhenNoCachedFieldActuallyDiffers() {
  var target = Overrides.defaultTuning();
  var fresh = Overrides.defaultTuning();
  Set<String> pending = new TreeSet<>();
  PhysicsBridge.reportFieldsNeedingRespawn(target, fresh, pending);
  assertTrue(pending.isEmpty());
 }
}
