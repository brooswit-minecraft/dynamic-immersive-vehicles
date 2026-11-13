package io.github.brooswitminecraft.div;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HPatternShifterTest {
 @Test void disabledIsAlwaysNone() {
  assertEquals(HPatternShifter.Action.NONE, HPatternShifter.step(3, HPatternShifter.DISABLED));
  assertEquals(HPatternShifter.Action.NONE, HPatternShifter.step(0, HPatternShifter.DISABLED));
 }
 @Test void neutralRequestShiftsToNeutralUnlessAlreadyThere() {
  assertEquals(HPatternShifter.Action.NEUTRAL, HPatternShifter.step(3, HPatternShifter.NEUTRAL_REQUEST));
  assertEquals(HPatternShifter.Action.NEUTRAL, HPatternShifter.step(-2, HPatternShifter.NEUTRAL_REQUEST));
  assertEquals(HPatternShifter.Action.NONE, HPatternShifter.step(0, HPatternShifter.NEUTRAL_REQUEST));
 }
 @Test void reverseRequestClimbsDownTowardFirstReverseGear() {
  assertEquals(HPatternShifter.Action.SHIFT_DOWN, HPatternShifter.step(0, HPatternShifter.REVERSE_REQUEST));
  assertEquals(HPatternShifter.Action.NONE, HPatternShifter.step(-1, HPatternShifter.REVERSE_REQUEST),
    "already at the first reverse gear - held request must not be reapplied");
 }
 @Test void reverseRequestFromAForwardGearStepsDownOneAtATimeThroughNeutral() {
  // ticket 53603: "reverse safety" - must not jump straight from a forward gear into reverse;
  // each single step is an ordinary SHIFT_DOWN (PartEngine.shiftDown()'s OWN 0-crossing is what
  // safety-gates by speed/direction - not duplicated here), so a multi-step climb naturally
  // passes through neutral one tick at a time rather than teleporting.
  assertEquals(HPatternShifter.Action.SHIFT_DOWN, HPatternShifter.step(3, HPatternShifter.REVERSE_REQUEST));
  assertEquals(HPatternShifter.Action.SHIFT_DOWN, HPatternShifter.step(1, HPatternShifter.REVERSE_REQUEST));
  assertEquals(HPatternShifter.Action.SHIFT_DOWN, HPatternShifter.step(0, HPatternShifter.REVERSE_REQUEST));
 }
 @Test void forwardDirectSelectClimbsUp() {
  assertEquals(HPatternShifter.Action.SHIFT_UP, HPatternShifter.step(0, 3));
  assertEquals(HPatternShifter.Action.SHIFT_UP, HPatternShifter.step(2, 3));
  assertEquals(HPatternShifter.Action.NONE, HPatternShifter.step(3, 3));
 }
 @Test void forwardDirectSelectAlsoClimbsDown() {
  // ticket 53603, explicit correction: must work down as well as up.
  assertEquals(HPatternShifter.Action.SHIFT_DOWN, HPatternShifter.step(5, 2));
  assertEquals(HPatternShifter.Action.SHIFT_DOWN, HPatternShifter.step(3, 2));
  assertEquals(HPatternShifter.Action.NONE, HPatternShifter.step(2, 2));
 }
 @Test void forwardTargetFromReverseStepsUpOneAtATimeThroughNeutral() {
  assertEquals(HPatternShifter.Action.SHIFT_UP, HPatternShifter.step(-1, 2));
  assertEquals(HPatternShifter.Action.SHIFT_UP, HPatternShifter.step(0, 2));
 }

 // --- Tracker: the actual per-engine usage (nextAction + recordOutcome, matching how
 // PhysicsBridge.advanceWheelAuxInputs actually drives it) ---

 /** Drives a tracker toward {@code target} assuming every issued action succeeds (reports
  * {@code recordOutcome(true)} each time, same as PhysicsBridge does for a real
  * shiftUp()/shiftDown()/shiftNeutral() that actually took effect), applying the gear delta
  * itself exactly the way a real engine would. */
 private static int climb(HPatternShifter.Tracker tracker, int startGear, int target, int maxTicks) {
  int gear = startGear;
  for (int i = 0; i < maxTicks; i++) {
   var action = tracker.nextAction(gear, target);
   if (action == HPatternShifter.Action.NONE) break;
   gear = switch (action) { case SHIFT_UP -> gear + 1; case SHIFT_DOWN -> gear - 1; case NEUTRAL -> 0; default -> gear; };
   tracker.recordOutcome(true);
  }
  return gear;
 }

 @Test void trackerClimbsMultipleTicksTowardAStableTargetInEitherDirection() {
  assertEquals(3, climb(new HPatternShifter.Tracker(), 0, 3, 10));
  assertEquals(2, climb(new HPatternShifter.Tracker(), 5, 2, 10));
 }
 @Test void fiveToTwoThenReverseThenNeutralFullSequence() {
  // ticket 53603's explicit test ask: "test 5→2, reverse safety, neutral".
  var tracker = new HPatternShifter.Tracker();
  int gear = climb(tracker, 5, 2, 10);
  assertEquals(2, gear);
  gear = climb(tracker, gear, HPatternShifter.REVERSE_REQUEST, 10);
  assertEquals(-1, gear, "reverse request should settle at the first reverse gear, via neutral");
  gear = climb(tracker, gear, HPatternShifter.NEUTRAL_REQUEST, 10);
  assertEquals(0, gear);
 }
 @Test void trackerDoesNotReclimbAHeldTargetAfterReaching() {
  var tracker = new HPatternShifter.Tracker();
  assertEquals(1, climb(tracker, 0, 1, 10));
  for (int i = 0; i < 10; i++) assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(1, 1));
 }
 @Test void aPaddleShiftIsNotClobberedByAnUnchangedHeldHPatternTargetAfterItIsReached() {
  var tracker = new HPatternShifter.Tracker();
  assertEquals(4, climb(tracker, 0, 4, 10));
  int gear = 3; // simulated independent paddle downshift, AFTER the target was already reached
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(gear, 4),
    "an unchanged held H-pattern target must not revert a paddle shift that moved currentGear below it, once already reached");
 }
 @Test void movingTheShifterToAGenuinelyNewSlotResumesClimbing() {
  var tracker = new HPatternShifter.Tracker();
  assertEquals(1, climb(tracker, 0, 1, 10));
  assertEquals(HPatternShifter.Action.SHIFT_UP, tracker.nextAction(1, 2));
 }
 @Test void simpleThrottleAutoReverseDoesNotFightAHeldHPatternSlotAfterItIsReached() {
  // ticket 53603: "resolve simpleThrottle auto-reverse conflict" - native's simpleThrottle can
  // change currentGear on its own (via a different Var-toggle path this class never touches).
  // As long as the H-pattern's OWN request value is unchanged, the tracker must stay latched
  // and silent once the target was already reached, never fighting to climb back.
  var tracker = new HPatternShifter.Tracker();
  assertEquals(1, climb(tracker, 0, 1, 10));
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(-1, 1), "simpleThrottle auto-reverse, independent of this tracker");
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(1, 1), "simpleThrottle auto-forward again");
 }
 @Test void aSafetyRefusedShiftSimplyRetriesNextTickWithoutFalselyLatching() {
  // PartEngine.shiftUp()/shiftDown() can refuse a shift (e.g. unsafe direction/speed) without
  // changing the gear. recordOutcome(false) must mean the NEXT call does not mistake the
  // unchanged gear for an external intervention and cancel the climb.
  var tracker = new HPatternShifter.Tracker();
  assertEquals(HPatternShifter.Action.SHIFT_UP, tracker.nextAction(0, 1));
  tracker.recordOutcome(false); // simulate PartEngine refusing the shift - gear stays 0
  assertEquals(HPatternShifter.Action.SHIFT_UP, tracker.nextAction(0, 1),
    "a refused shift must retry, not silently give up or be mistaken for an external change");
 }
 @Test void repeatedRefusalsNeverFalselyTriggerExternalChangeCancellation() {
  var tracker = new HPatternShifter.Tracker();
  for (int i = 0; i < 20; i++) {
   assertEquals(HPatternShifter.Action.SHIFT_UP, tracker.nextAction(0, 3), "refusal #"+i+" must keep retrying toward the target");
   tracker.recordOutcome(false);
  }
 }

 // --- The actual new behavior ticket comment 53617 asked for: a paddle (or any other external
 // cause) acting MID-CLIMB, before the target is reached, must cancel the outstanding climb
 // outright rather than being fought. ---

 @Test void anExternalGearChangeMidClimbCancelsTheOutstandingTarget() {
  var tracker = new HPatternShifter.Tracker();
  assertEquals(HPatternShifter.Action.SHIFT_UP, tracker.nextAction(0, 5)); // climbing toward 5, now expects gear 1
  tracker.recordOutcome(true);
  // A paddle fires independently, moving the gear to 3 instead of the expected 1 - a genuine
  // external intervention mid-climb, not a refusal (which would leave gear at 0).
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(3, 5),
    "an external gear change mid-climb must cancel the outstanding H-pattern target, not keep climbing toward it");
  // The SAME unchanged target (5) must stay cancelled afterward too - this is "supersede", not
  // a one-tick skip.
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(3, 5));
 }
 @Test void aNewHPatternSlotAfterAnExternalInterventionResumesNormally() {
  var tracker = new HPatternShifter.Tracker();
  tracker.nextAction(0, 5);
  tracker.recordOutcome(true);
  tracker.nextAction(3, 5); // external intervention cancels the climb toward 5
  // The physical shifter is now moved to a genuinely new slot (2) - must resume climbing.
  assertEquals(HPatternShifter.Action.SHIFT_DOWN, tracker.nextAction(3, 2));
 }
}
