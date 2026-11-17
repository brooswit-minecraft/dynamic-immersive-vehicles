package io.github.brooswitminecraft.div;
import java.util.Set;
import java.util.UUID;

/**
 * MINECRAFT-545/547 (John's comment 54494): a small, pure, explicitly-armed diagnostic context -
 * restricted body IDs plus an explicit {@code gameTime} window - shared between a caller (which
 * already knows its own fixture body IDs/window, e.g. {@code experimental.BuoyantHitchProbe})
 * and {@link BuoyantForces#apply}'s own detailed logging, so that logging is bounded to the
 * armed fixture/body/window rather than merely to the global {@code GroundContactDiagnostics}
 * flag (which still gates every body/float/tick regardless, per 54469's own existing meaning -
 * this context narrows further, it does not replace that flag). The caller arms it BEFORE the
 * tick's intended force hook runs (i.e. before {@code BuoyantBody.step}/{@code
 * BuoyantForces.apply} is invoked for that tick), covering a SINGLE continuous {@code gameTime}
 * range that spans a phase reset (e.g. a fixture's own two separate phase-relative tick
 * counters), rather than being tied to either counter directly. {@link #matches} itself is pure
 * (no static/mutable state) and is what the focused tests exercise directly; {@link #isActiveFor}
 * is the thin, diagnostic-only static accessor {@code apply} actually calls, armed/disarmed by
 * {@link #arm}/{@link #disarm} - never consulted by any production decision, only by logging.
 */
public final class DiagnosticContext {
 private static volatile DiagnosticContext ACTIVE = null;

 private final Set<UUID> bodyIds;
 private final long startGameTimeInclusive, endGameTimeInclusive;

 private DiagnosticContext(Set<UUID> bodyIds, long startGameTimeInclusive, long endGameTimeInclusive) {
  this.bodyIds = bodyIds;
  this.startGameTimeInclusive = startGameTimeInclusive;
  this.endGameTimeInclusive = endGameTimeInclusive;
 }

 /** Pure: does this context cover {@code bodyId} at {@code gameTime}? No static state involved -
  * directly unit-testable by constructing a context and calling this. */
 public boolean matches(UUID bodyId, long gameTime) {
  return bodyIds.contains(bodyId) && gameTime >= startGameTimeInclusive && gameTime <= endGameTimeInclusive;
 }

 /** Arms the shared context a caller (e.g. the probe fixture) wants {@code BuoyantForces.apply}'s
  * own detailed logging bounded to, for this run. Call BEFORE the tick's force hook runs. */
 public static void arm(Set<UUID> bodyIds, long startGameTimeInclusive, long endGameTimeInclusive) {
  ACTIVE = new DiagnosticContext(bodyIds, startGameTimeInclusive, endGameTimeInclusive);
 }

 /** Clears the armed context; detailed logging stops regardless of the global flag. */
 public static void disarm() { ACTIVE = null; }

 /** Diagnostic-only accessor {@code BuoyantForces.apply} calls - false whenever nothing is armed
  * (the default, outside any diagnostic run), never consulted by production behavior. */
 static boolean isActiveFor(UUID bodyId, long gameTime) {
  var ctx = ACTIVE;
  return ctx != null && ctx.matches(bodyId, gameTime);
 }
}
