package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticket MINECRAFT-546: TuningProvenance must explain the SAME values Overrides.resolved/
 * resolvedPart would actually compute, by reusing the shared resolver rather than a duplicate
 * precedence implementation - these tests exercise that against Overrides' own setForTest seam
 * (no live FML config directory or Gson needed), the same pattern OverridesTest already uses.
 */
class TuningProvenanceTest {
 @AfterEach void resetOverrides() {
  Overrides.setForTest(Map.of(), Map.of(), Map.of());
 }

 @Test void unconfiguredVehicleResolvesEveryFieldFromFallbackOrUnset() {
  var explanation = TuningProvenance.explainVehicle("packid:not_configured");
  assertFalse(explanation.found());
  assertTrue(explanation.extendsChain().isEmpty());
  for (var field : explanation.fields()) {
   assertTrue(field.source() == TuningProvenance.Source.FALLBACK || field.source() == TuningProvenance.Source.UNSET,
     field.field()+" should resolve from fallback or be unset with no config entry at all, was "+field.source());
   assertNull(field.templateName());
  }
  // A field with a documented flat fallback must actually carry it.
  var driveForce = fieldNamed(explanation, "driveForce");
  assertEquals(TuningProvenance.Source.FALLBACK, driveForce.source());
  assertEquals(8000.0, driveForce.value());
  // enabled/massKg have no flat fallback at all (Overrides.resolveFields' own doc) - UNSET.
  assertEquals(TuningProvenance.Source.UNSET, fieldNamed(explanation, "enabled").source());
  assertEquals(TuningProvenance.Source.UNSET, fieldNamed(explanation, "massKg").source());
 }

 @Test void directOperatorOverrideWinsOverFallbackWithNoTemplateAttribution() {
  var override = new Overrides.Tuning();
  override.driveForce = 12345.0;
  Overrides.setForTest(Map.of("packid:direct_override", override));
  var explanation = TuningProvenance.explainVehicle("packid:direct_override");
  assertTrue(explanation.found());
  var driveForce = fieldNamed(explanation, "driveForce");
  assertEquals(TuningProvenance.Source.OPERATOR_OVERRIDE, driveForce.source());
  assertEquals(12345.0, driveForce.value());
  assertNull(driveForce.templateName(), "a field set directly on the entry itself must not be attributed to any template");
  // A field this entry never set still falls through to fallback.
  assertEquals(TuningProvenance.Source.FALLBACK, fieldNamed(explanation, "brakeForce").source());
 }

 @Test void fieldInheritedThroughExtendsIsAttributedToTheTemplateThatActuallySetIt() {
  var grandparent = new Overrides.Tuning();
  grandparent.friction = 2.5;
  var parent = new Overrides.Tuning();
  parent.extendsTemplate = "grandparent";
  parent.driveForce = 9999.0;
  var child = new Overrides.Tuning();
  child.extendsTemplate = "parent";
  Overrides.setForTest(Map.of("grandparent", grandparent, "parent", parent),
    Map.of("packid:inherited_child", child), Map.of());
  var explanation = TuningProvenance.explainVehicle("packid:inherited_child");
  assertEquals(List.of("parent", "grandparent"), explanation.extendsChain());
  var driveForce = fieldNamed(explanation, "driveForce");
  assertEquals(TuningProvenance.Source.OPERATOR_OVERRIDE, driveForce.source());
  assertEquals(9999.0, driveForce.value());
  assertEquals("parent", driveForce.templateName(), "driveForce was set on 'parent', the nearest ancestor that actually sets it");
  var friction = fieldNamed(explanation, "friction");
  assertEquals(TuningProvenance.Source.OPERATOR_OVERRIDE, friction.source());
  assertEquals(2.5, friction.value());
  assertEquals("grandparent", friction.templateName(), "friction skips 'parent' (which never sets it) and is attributed to 'grandparent'");
 }

 @Test void partExplanationIncludesOnlyFieldsWithARealPartLevelConsumer() {
  var partOverride = new Overrides.Tuning();
  partOverride.driveForce = 500.0;
  Overrides.setForTest(Map.of(), Map.of(), Map.of("packid:engine_part", partOverride));
  var explanation = TuningProvenance.explainPart("packid:engine_part");
  assertTrue(explanation.isPart());
  var names = explanation.fields().stream().map(TuningProvenance.FieldOrigin::field).toList();
  assertTrue(names.contains("driveForce"));
  assertTrue(names.contains("friction"));
  assertTrue(names.contains("rollingResistance"));
  // massKg/brakeForce/etc. have no part-level consumer at all (Tuning#validateSupportedForPart) -
  // including them here would be exactly the "unrelated config contents" the ticket excludes.
  assertFalse(names.contains("massKg"));
  assertFalse(names.contains("brakeForce"));
  assertFalse(names.contains("extendsTemplate"));
 }

 @Test void liveSafeAndRespawnRequiredClassificationsAreDisjointAndMatchPhysicsBridge() {
  // Derived from PhysicsBridge's own real classification methods (see TuningProvenance's own
  // doc) - friction is live-reloadable (PhysicsBridge.applyLiveSafeFields), massKg needs a
  // respawn (PhysicsBridge.reportFieldsNeedingRespawn); neither set should ever overlap.
  assertTrue(TuningProvenance.isLiveSafe("friction"));
  assertFalse(TuningProvenance.isRespawnRequired("friction"));
  assertTrue(TuningProvenance.isRespawnRequired("massKg"));
  assertFalse(TuningProvenance.isLiveSafe("massKg"));
  assertTrue(TuningProvenance.isRespawnRequired("enabled"));
  assertFalse(TuningProvenance.isLiveSafe("enabled"));
 }

 @Test void explanationReflectsFieldClassificationInline() {
  var explanation = TuningProvenance.explainVehicle("packid:not_configured");
  assertTrue(fieldNamed(explanation, "friction").liveSafe());
  assertFalse(fieldNamed(explanation, "friction").respawnRequired());
  assertTrue(fieldNamed(explanation, "massKg").respawnRequired());
  assertFalse(fieldNamed(explanation, "massKg").liveSafe());
 }

 @Test void rejectedReloadLeavesTheDiagnosticUnchangedFromLastKnownGood() {
  var goodOverride = new Overrides.Tuning();
  goodOverride.driveForce = 7777.0;
  Overrides.setForTest(Map.of("packid:lkg_vehicle", goodOverride));
  var before = TuningProvenance.explainVehicle("packid:lkg_vehicle");
  assertEquals(7777.0, fieldNamed(before, "driveForce").value());
  // An invalid candidate config (massKg out of range) must be rejected - Overrides.apply's own
  // last-known-good contract, exercised through the SAME test seam OverridesTest's own
  // last-known-good regression uses (no live FML config directory needed).
  var badOverride = new Overrides.Tuning();
  badOverride.massKg = 1.0; // below the documented [50,100000] range - Tuning#validate rejects it
  var badConfig = new Overrides.Config(Map.of(), Map.of("packid:lkg_vehicle", badOverride), Map.of());
  var result = Overrides.apply(() -> badConfig);
  assertFalse(result.success());
  var after = TuningProvenance.explainVehicle("packid:lkg_vehicle");
  assertEquals(7777.0, fieldNamed(after, "driveForce").value(), "a rejected reload must retain the prior (last-known-good) resolved value");
 }

 @Test void renderIncludesEveryFieldSourceAndTemplateAttributionWhenPresent() {
  var parent = new Overrides.Tuning();
  parent.friction = 3.0;
  var child = new Overrides.Tuning();
  child.extendsTemplate = "parent-template";
  Overrides.setForTest(Map.of("parent-template", parent), Map.of("packid:render_vehicle", child), Map.of());
  var explanation = TuningProvenance.explainVehicle("packid:render_vehicle");
  var lines = TuningProvenance.render("vehicle packid:render_vehicle", explanation);
  assertTrue(lines.get(0).contains("extends parent-template"));
  assertTrue(lines.stream().anyMatch(l -> l.contains("friction=3.0") && l.contains("via template 'parent-template'")));
  assertTrue(lines.stream().anyMatch(l -> l.contains("driveForce=8000.0") && l.contains("fallback")));
 }

 @Test void unwiredLayersNoticeExplicitlyNamesEveryUnwiredLayer() {
  assertTrue(TuningProvenance.UNWIRED_LAYERS_NOTICE.contains("pack"));
  assertTrue(TuningProvenance.UNWIRED_LAYERS_NOTICE.contains("native"));
  assertTrue(TuningProvenance.UNWIRED_LAYERS_NOTICE.contains("derived"));
 }

 private static TuningProvenance.FieldOrigin fieldNamed(TuningProvenance.Explanation explanation, String name) {
  return explanation.fields().stream().filter(f -> f.field().equals(name)).findFirst()
    .orElseThrow(() -> new AssertionError("no field named "+name+" in explanation"));
 }
}
