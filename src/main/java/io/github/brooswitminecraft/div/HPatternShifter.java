package io.github.brooswitminecraft.div;

/**
 * Pure gear-climbing algorithm for a physical H-pattern shifter read directly by DIV (ticket
 * MINECRAFT-504). Native IV's own H-pattern support (its {@code useShifter} setting) and
 * sequential paddle shifting are mutually exclusive in the installed build (one gates off the
 * other entirely in {@code ControlSystem.controlGroundVehicle} - confirmed by decompiling the
 * exact pinned jar), so running both simultaneously (explicit user requirement, comment 53577:
 * "they should work together") requires DIV to read the H-pattern buttons itself and drive
 * {@code PartEngine}'s public {@code shiftUp()}/{@code shiftDown()}/{@code shiftNeutral()}
 * directly, entirely independent of {@code useShifter} and never touching the native {@code
 * shiftSelectionVar} it fights over - leaving native's own {@code useShifter=false} paddle
 * handling (already shipped, unmodified) to coexist peacefully, since the two trigger on
 * different physical buttons and only converge on the same public methods.
 *
 * <p>Climbs in BOTH directions (comment 53603: "H direct-select must work down as well as up" -
 * an explicit correction of this class's earlier upward-only design, which faithfully but
 * wrongly copied a one-directional quirk in native's own {@code shiftSelectionVar} branch).
 * Every gear position - forward 1-9, neutral, and the single reverse slot - is modeled as one
 * continuous signed integer axis (negative = reverse, 0 = neutral, positive = forward), exactly
 * matching {@code PartEngine.shiftUp()}/{@code shiftDown()}'s own {@code currentGear±1} stepping,
 * so climbing toward ANY target (including through neutral into/out of reverse) is the same
 * {@code current < target ? UP : current > target ? DOWN : NONE} comparison - no special-cased
 * direction logic. {@code PartEngine}'s own shiftUp()/shiftDown() already refuse an unsafe 0↔1
 * crossing (e.g. engaging forward while still moving in reverse) by returning false without
 * changing the gear; since this class's {@link Tracker} only advances/latches when the gear
 * actually changes, a refusal simply retries next tick rather than getting stuck - no separate
 * safety logic is duplicated here.
 */
public final class HPatternShifter {
 private HPatternShifter() {}
 /** Mirrors {@code PartEngine}'s two non-numeric gear-request sentinels (its own {@code
  * shiftSelectionVar} value space): 10 = reverse request (the shifter's single R slot - treated
  * as "climb toward the first reverse gear", not a direct absolute target, since a real shifter
  * has no separate slot per reverse gear), 11 = neutral request. 1-9 are direct numeric forward
  * targets. -1 means no H-pattern reading AT ALL this tick (disabled, or no device/binding
  * actually configured and present - see {@code WheelAuxInput.currentHPatternTarget}'s own doc
  * for why this must be distinguished from "configured and reading released", comment 53603). */
 public static final int REVERSE_REQUEST = 10;
 public static final int NEUTRAL_REQUEST = 11;
 public static final int DISABLED = -1;
 public enum Action { NONE, SHIFT_UP, SHIFT_DOWN, NEUTRAL }
 /** The signed integer gear axis position a request sentinel represents, or {@code null} for
  * {@link #DISABLED} (no action at all). Package-visible: {@link Tracker} is the sanctioned
  * per-engine entry point; exposed at package level only for this class's own tests. */
 static Integer targetGear(int request) {
  if (request == DISABLED) return null;
  if (request == NEUTRAL_REQUEST) return 0;
  if (request == REVERSE_REQUEST) return -1;
  return request; // 1-9
 }
 /** One stateless step toward {@code request} from {@code currentGear} - see {@link Tracker}
  * for the actual per-engine usage, which adds memory so a held target is never re-applied
  * (protecting an independent paddle shift from being clobbered - see that class's own doc). */
 static Action step(int currentGear, int request) {
  Integer target = targetGear(request);
  if (target == null) return Action.NONE;
  if (currentGear == target) return Action.NONE;
  if (target == 0) return Action.NEUTRAL; // shiftNeutral() jumps straight to 0 in one call
  return currentGear < target ? Action.SHIFT_UP : Action.SHIFT_DOWN;
 }
 /**
  * Per-engine climb state: remembers which request value was last fully satisfied (reached, or
  * refused this tick and simply not-yet-retried) so that request is never re-climbed toward
  * again just because the shifter is still sitting in the same slot - only an actual change of
  * the request resumes climbing. This is what stops a held H-pattern slot from reverting an
  * independent paddle shift (ticket comment 53573's explicit ask): once a target is reached,
  * later evaluations with the SAME request are NONE even if {@code currentGear} has since moved
  * away from it (e.g. a paddle, or native's own simpleThrottle auto-reverse, changed it) - only
  * the shifter moving to a genuinely different slot resumes climbing toward the new one. This
  * is also exactly what avoids fighting native's {@code simpleThrottle} auto-reverse: since the
  * H-pattern's own request value does not change just because simpleThrottle changed the gear,
  * this tracker stays latched and silent throughout.
  *
  * <p>Not thread-safe; callers own one instance per engine and call it from a single thread (the
  * server tick thread), same as the rest of this codebase's per-tick state. Must be driven once
  * per SERVER TICK for as long as a request is outstanding (not merely once per received network
  * packet) - climbing more than one gear, or retrying a safety-refused shift, both require
  * repeated calls with the SAME request across multiple ticks; see {@code
  * PhysicsBridge.advanceWheelAuxInputs} for that continuation.
  */
 public static final class Tracker {
  private int lastRequest = DISABLED;
  private boolean achieved = true;
  public Action nextAction(int currentGear, int request) {
   if (request != lastRequest) { lastRequest = request; achieved = false; }
   if (achieved) return Action.NONE;
   Action action = step(currentGear, request);
   if (action == Action.NONE) achieved = true;
   return action;
  }
 }
}
