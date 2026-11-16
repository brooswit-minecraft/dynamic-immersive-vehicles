package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticket MINECRAFT-546: the {@code /div tuning} command's actual decision logic, exercised
 * entirely through {@code TuningProvenance} (never {@code TuningCommand} itself). This project's
 * test source set has no Minecraft/NeoForge/Brigadier/MTS classes on its compile-or-runtime
 * classpath at all (only {@code main} does, via the neoForge moddev plugin) - confirmed two ways:
 * a direct {@code CommandDispatcher<CommandSourceStack>}/registration-shape test here failed to
 * COMPILE ("package com.mojang.brigadier does not exist"), and merely referencing {@code
 * TuningCommand} (which does compile against those classes in {@code main}) from a test failed
 * at runtime with {@code NoClassDefFoundError} on class load, before any assertion even ran.
 * Exercising {@code TuningCommand.register}/{@code run} directly is therefore not possible from
 * a plain unit test in this codebase, the same pre-existing constraint {@code
 * PhysicsBridgeStepperTest}'s own doc already states for a live {@code EntityVehicleF_Physics}/
 * Sable body - so every actual DECISION {@code TuningCommand} makes was moved into {@code
 * TuningProvenance} (which has no Minecraft/MTS import at all) and is covered here: {@code
 * explainResponse} - the full response-assembly decision {@code TuningCommand.run} delegates to,
 * a pure function of plain data (vehicle key, the vehicle's own live {@code Tuning} or {@code
 * null}, installed part keys) exercising every branch (dedup, unconfigured-part skip, and the
 * live-vs-resolved-config divergence a respawn-required field can show) without needing a live
 * entity - and the plain-string failure-message builders {@code run} sends through {@code
 * CommandSourceStack}.
 */
class TuningCommandTest {
 @AfterEach void resetOverrides() {
  Overrides.setForTest(Map.of(), Map.of(), Map.of());
 }

 @Test void notEligibleMessageNamesTheTargetByDisplayName() {
  assertEquals("[DIV] Cow is not a DIV-eligible vehicle entity.", TuningProvenance.notEligibleMessage("Cow"));
 }

 @Test void noDefinitionMessageIsAFixedNonEmptyNotice() {
  assertEquals("[DIV] that vehicle has no loaded definition.", TuningProvenance.NO_DEFINITION_MESSAGE);
 }

 @Test void explainResponseLeadsWithTheUnwiredLayersNoticeAndVehicleStatusLine() {
  // A non-null liveVehicleTuning means this vehicle currently has an active body - same signal
  // PhysicsBridge.activeTuning(vehicle) != null used to collapse into a bare boolean.
  var lines = TuningProvenance.explainResponse("packid:unconfigured_vehicle", new Overrides.Tuning(), List.of());
  assertEquals(TuningProvenance.UNWIRED_LAYERS_NOTICE, lines.get(0));
  assertEquals("[DIV] vehicle packid:unconfigured_vehicle - active (spawned/physics-engaged)", lines.get(1));
 }

 @Test void explainResponseReportsInactiveWhenNoActiveBodyExists() {
  var lines = TuningProvenance.explainResponse("packid:v", null, List.of());
  assertTrue(lines.get(1).contains("not currently active"));
 }

 @Test void explainResponseSkipsPartsWithNoOverrideEntryAtAll() {
  var lines = TuningProvenance.explainResponse("packid:v", null, List.of("packid:unconfigured_part"));
  assertTrue(lines.stream().noneMatch(l -> l.contains("part packid:unconfigured_part")),
    "a part nobody configured has nothing to explain - must not appear in the output at all");
 }

 @Test void explainResponseExplainsEachDistinctConfiguredPartExactlyOnceDespiteDuplicateInstalls() {
  var partOverride = new Overrides.Tuning();
  partOverride.driveForce = 999.0;
  Overrides.setForTest(Map.of(), Map.of(), Map.of("packid:engine_part", partOverride));
  // Four installed instances of the SAME part definition (e.g. four identical wheels/engines) -
  // must be explained exactly once, not four times.
  var lines = TuningProvenance.explainResponse("packid:v", null,
    List.of("packid:engine_part", "packid:engine_part", "packid:engine_part", "packid:engine_part"));
  long occurrences = lines.stream().filter(l -> l.startsWith("[DIV] part packid:engine_part")).count();
  assertEquals(1, occurrences, "a part key installed multiple times must only be explained once");
  assertTrue(lines.stream().anyMatch(l -> l.contains("driveForce=999.0")));
 }

 // Review fix (comment 54192): a respawn-required field changed by a reload since this vehicle
 // last activated must show what the running body is ACTUALLY using, not just the freshly
 // resolved config value under a generic "active" label - the two can legitimately diverge.

 @Test void explainResponseFlagsLiveDivergenceOnARespawnRequiredFieldAfterAConfigChange() {
  var currentConfig = new Overrides.Tuning();
  currentConfig.massKg = 500.0; // the NEW config value, resolved fresh right now
  Overrides.setForTest(Map.of("packid:v", currentConfig));
  var liveTuning = new Overrides.Tuning();
  liveTuning.massKg = 300.0; // what THIS already-active body was actually built with (stale)
  var lines = TuningProvenance.explainResponse("packid:v", liveTuning, List.of());
  var massLine = lines.stream().filter(l -> l.contains("massKg=")).findFirst().orElseThrow();
  assertTrue(massLine.contains("massKg=500.0"), "the resolved-config value must still be shown as the headline value: " + massLine);
  assertTrue(massLine.contains("LIVE (currently applied to this vehicle): 300.0"),
    "a stale respawn-required field must name what is ACTUALLY running: " + massLine);
 }

 @Test void explainResponseShowsNoDivergenceNoteWhenLiveAlreadyMatchesResolvedConfig() {
  var currentConfig = new Overrides.Tuning();
  currentConfig.massKg = 500.0;
  Overrides.setForTest(Map.of("packid:v", currentConfig));
  var liveTuning = new Overrides.Tuning();
  liveTuning.massKg = 500.0; // this body was built fresh with exactly today's config
  var lines = TuningProvenance.explainResponse("packid:v", liveTuning, List.of());
  var massLine = lines.stream().filter(l -> l.contains("massKg=")).findFirst().orElseThrow();
  assertFalse(massLine.contains("LIVE ("), "an up-to-date respawn-required field must not claim a divergence: " + massLine);
 }

 @Test void explainResponseNeverCompaesLiveSafeFieldsAgainstLiveTuningEvenIfTheyDifferedSomehow() {
  var currentConfig = new Overrides.Tuning();
  currentConfig.friction = 2.0; // friction is live-safe - hot reload keeps the live object in sync
  Overrides.setForTest(Map.of("packid:v", currentConfig));
  var liveTuning = new Overrides.Tuning();
  liveTuning.friction = 1.0; // deliberately different, to prove this is never even checked
  var lines = TuningProvenance.explainResponse("packid:v", liveTuning, List.of());
  var frictionLine = lines.stream().filter(l -> l.contains("friction=")).findFirst().orElseThrow();
  assertFalse(frictionLine.contains("LIVE ("), "a live-safe field must never be compared against liveTuning at all: " + frictionLine);
 }

 @Test void explainResponseReportsNoDivergenceWhenVehicleHasNoActiveBodyAtAll() {
  var currentConfig = new Overrides.Tuning();
  currentConfig.massKg = 500.0;
  Overrides.setForTest(Map.of("packid:v", currentConfig));
  var lines = TuningProvenance.explainResponse("packid:v", null, List.of());
  var massLine = lines.stream().filter(l -> l.contains("massKg=")).findFirst().orElseThrow();
  assertFalse(massLine.contains("LIVE ("), "with no active body there is nothing live to compare against: " + massLine);
 }
}
