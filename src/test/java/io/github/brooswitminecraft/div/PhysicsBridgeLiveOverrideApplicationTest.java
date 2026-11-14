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
  fresh.angularWaterDrag = 50.0; fresh.antiRollStiffness = 0.4;
  fresh.stallAngleDegrees = 12.0; fresh.liftSlope = 5.0; fresh.controlSurfaceSlope = 4.0;
  fresh.brakeBiasFront = 0.65;
  fresh.driveBiasFront = 0.3;
  // MINECRAFT-522: the three new rotor fields must go through this same live-mutation path -
  // John's review (comment 53751) flagged that this test exercised every pre-existing live-safe
  // field but never changed/asserted these.
  fresh.cyclicLimitDegrees = 12.0; fresh.cyclicAuthorityScale = 1.4; fresh.yawAuthorityScale = 0.6;
  List<String> applied = new ArrayList<>();
  PhysicsBridge.applyLiveSafeFields(target, fresh, applied);
  assertEquals(0.5, target.suspensionTravel); assertEquals(1.5, target.friction); assertEquals(9000.0, target.driveForce);
  assertEquals(13000.0, target.brakeForce); assertEquals(35.0, target.maxSteerDegrees); assertEquals(0.7, target.lateralGripMultiplier);
  assertEquals(450.0, target.waterDrag); assertEquals(7000.0, target.propellerForce); assertEquals(50.0, target.angularWaterDrag);
  assertEquals(0.4, target.antiRollStiffness);
  assertEquals(12.0, target.stallAngleDegrees); assertEquals(5.0, target.liftSlope); assertEquals(4.0, target.controlSurfaceSlope);
  assertEquals(0.65, target.brakeBiasFront);
  assertEquals(0.3, target.driveBiasFront);
  assertEquals(12.0, target.cyclicLimitDegrees); assertEquals(1.4, target.cyclicAuthorityScale); assertEquals(0.6, target.yawAuthorityScale);
  assertEquals(Set.of("suspensionTravel","friction","driveForce","brakeForce","maxSteerDegrees","lateralGripMultiplier","waterDrag","propellerForce","angularWaterDrag","antiRollStiffness","stallAngleDegrees","liftSlope","controlSurfaceSlope","brakeBiasFront","driveBiasFront",
      "cyclicLimitDegrees","cyclicAuthorityScale","yawAuthorityScale"),
    new TreeSet<>(applied));
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
