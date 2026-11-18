package io.github.brooswitminecraft.div;
import java.util.Set;
import java.util.UUID;

/**
 * MINECRAFT-545/547 (John's comment 54486): a small, pure helper for restricting a diagnostic
 * marker to an explicit set of body IDs and an explicit tick-in-phase window - parameterized by
 * the caller, not hardcoded to any one fixture's own IDs or ticks, so this stays general,
 * reusable production code rather than a special case for this investigation's own probe. Takes
 * no {@code ServerLevel}/Minecraft dependency at all, so it is directly unit-testable with plain
 * JUnit (see {@code DiagnosticWindowTest}).
 */
public final class DiagnosticWindow {
 private DiagnosticWindow() {}

 /** True only if {@code bodyId} is one of {@code expectedBodyIds} AND {@code ticksInPhase} falls
  * within {@code [startTickInclusive, endTickInclusive]}. Used to mark "this body/tick was
  * EXPECTED to be diagnosed" from OUTSIDE {@code BuoyantForces.apply} (in the caller that already
  * knows its own fixture body IDs and tick window) - a missing expected-body marker is then
  * itself evidence of a capture gap, not merely a missing per-float line that could also mean
  * "correctly not submerged". Public: called from {@code experimental.BuoyantHitchProbe}, a
  * different package - diagnostic/test-only, never called from any production decision path. */
 public static boolean inWindow(UUID bodyId, int ticksInPhase, Set<UUID> expectedBodyIds, int startTickInclusive, int endTickInclusive) {
  return expectedBodyIds.contains(bodyId) && ticksInPhase >= startTickInclusive && ticksInPhase <= endTickInclusive;
 }
}
