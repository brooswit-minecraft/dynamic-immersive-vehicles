package io.github.brooswitminecraft.div;

/**
 * Pure gear-climbing algorithm for a physical H-pattern shifter read directly by DIV (ticket
 * MINECRAFT-504, comment 53572 - "they should work together": native IV's own H-pattern support
 * (its {@code useShifter} setting) and sequential paddle shifting are mutually exclusive in the
 * installed build (one gates off the other entirely in {@code ControlSystem.controlGroundVehicle}
 * - confirmed by decompiling the exact pinned jar), so running both simultaneously requires DIV to
 * read the H-pattern buttons itself and drive {@code PartEngine}'s public {@code shiftUp()}/
 * {@code shiftDown()}/{@code shiftNeutral()} directly, entirely independent of {@code useShifter}
 * and never touching the native {@code shiftSelectionVar} it fights over - leaving native's own
 * {@code useShifter=false} paddle handling (already shipped, unmodified) to coexist peacefully,
 * since the two trigger on different physical buttons and only converge on the same public
 * methods.
 *
 * <p>Deliberately does NOT reuse native's own re-check-every-tick design for one specific reason
 * (ticket comment 53573's explicit test ask): native's {@code shiftSelectionVar} branch
 * re-evaluates "does the held slot still match the current gear" on EVERY tick for as long as the
 * shifter sits in that slot, which is exactly what lets a later, independent paddle downshift get
 * silently reverted back up to the still-held slot. {@link Tracker} instead only climbs toward a
 * target while it is actively being approached, and latches "achieved" once reached (or blocked)
 * so a later paddle shift that moves {@code currentGear} away from an unchanged held target is
 * never clobbered - the climb only resumes if the physical shifter is moved to an actually NEW
 * slot. Climbing itself still mirrors native's own asymmetry (only ever climbs up toward a
 * numbered forward target, never down) - that part is native IV's own existing behavior, kept
 * faithfully, not something this class is trying to improve on.
 */
public final class HPatternShifter {
 private HPatternShifter() {}
 /** Mirrors {@code PartEngine}'s three gear-request sentinels exactly (its own {@code
  * shiftSelectionVar} value space): 1-9 = direct forward gear select, 10 = reverse request
  * (only takes effect from neutral), 11 = neutral request. -1 means "no H-pattern reading this
  * tick" (disabled or no device). */
 public static final int REVERSE_REQUEST = 10;
 public static final int NEUTRAL_REQUEST = 11;
 public static final int DISABLED = -1;
 public enum Action { NONE, SHIFT_UP, SHIFT_DOWN, NEUTRAL }
 /** One stateless step toward {@code target} from {@code currentGear}, with no memory of
  * whether this exact target was already satisfied before - see {@link Tracker} for the actual
  * per-engine usage, which adds that memory to avoid re-clobbering a paddle shift. Exposed
  * separately because it is independently simple to verify. */
 static Action step(int currentGear, int target) {
  if (target == DISABLED) return Action.NONE;
  if (target == NEUTRAL_REQUEST) return currentGear != 0 ? Action.NEUTRAL : Action.NONE;
  if (target == REVERSE_REQUEST) return currentGear == 0 ? Action.SHIFT_DOWN : Action.NONE;
  // target is 1-9: native's own asymmetry - only ever climbs up, never down, even if
  // currentGear is already above target (see class doc).
  return currentGear < target ? Action.SHIFT_UP : Action.NONE;
 }
 /**
  * Per-engine climb state: remembers which target value was last fully satisfied (reached, or
  * blocked for a reason other than "not there yet" - e.g. a reverse request while already in a
  * forward gear) so that target is never re-climbed toward again just because the shifter is
  * still sitting in the same slot - only an actual change of {@code target} resumes climbing.
  * Not thread-safe; callers own one instance per engine and call it from a single thread (the
  * server tick thread), same as the rest of this codebase's per-tick state.
  */
 public static final class Tracker {
  private int lastTarget = DISABLED;
  private boolean achieved = true;
  public Action nextAction(int currentGear, int target) {
   if (target != lastTarget) { lastTarget = target; achieved = false; }
   if (achieved) return Action.NONE;
   Action action = step(currentGear, target);
   if (action == Action.NONE) achieved = true;
   return action;
  }
 }
}
