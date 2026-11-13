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

 // --- Tracker: the actual per-engine usage ---

 @Test void trackerClimbsMultipleTicksTowardAStableTargetInEitherDirection() {
  var up = new HPatternShifter.Tracker();
  int gear = 0;
  HPatternShifter.Action a;
  while ((a = up.nextAction(gear, 3)) != HPatternShifter.Action.NONE) { assertEquals(HPatternShifter.Action.SHIFT_UP, a); gear++; }
  assertEquals(3, gear);

  var down = new HPatternShifter.Tracker();
  gear = 5;
  while ((a = down.nextAction(gear, 2)) != HPatternShifter.Action.NONE) { assertEquals(HPatternShifter.Action.SHIFT_DOWN, a); gear--; }
  assertEquals(2, gear);
 }
 @Test void fiveToTwoThenReverseThenNeutralFullSequence() {
  // ticket 53603's explicit test ask: "test 5→2, reverse safety, neutral".
  var tracker = new HPatternShifter.Tracker();
  int gear = 5;
  HPatternShifter.Action a;
  while ((a = tracker.nextAction(gear, 2)) != HPatternShifter.Action.NONE) { gear--; }
  assertEquals(2, gear);
  while ((a = tracker.nextAction(gear, HPatternShifter.REVERSE_REQUEST)) != HPatternShifter.Action.NONE) {
   gear = switch (a) { case NEUTRAL -> 0; case SHIFT_DOWN -> gear - 1; default -> gear; };
  }
  assertEquals(-1, gear, "reverse request should settle at the first reverse gear, via neutral");
  while ((a = tracker.nextAction(gear, HPatternShifter.NEUTRAL_REQUEST)) != HPatternShifter.Action.NONE) { gear = 0; }
  assertEquals(0, gear);
 }
 @Test void trackerDoesNotReclimbAHeldTargetAfterReaching() {
  var tracker = new HPatternShifter.Tracker();
  assertEquals(HPatternShifter.Action.SHIFT_UP, tracker.nextAction(0, 1));
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(1, 1));
  for (int i = 0; i < 10; i++) assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(1, 1));
 }
 @Test void aPaddleShiftIsNotClobberedByAnUnchangedHeldHPatternTarget() {
  var tracker = new HPatternShifter.Tracker();
  int gear = 0;
  HPatternShifter.Action a;
  while ((a = tracker.nextAction(gear, 4)) != HPatternShifter.Action.NONE) { gear++; }
  assertEquals(4, gear);
  gear = 3; // simulated independent paddle downshift
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(gear, 4),
    "an unchanged held H-pattern target must not revert a paddle shift that moved currentGear below it");
 }
 @Test void movingTheShifterToAGenuinelyNewSlotResumesClimbing() {
  var tracker = new HPatternShifter.Tracker();
  tracker.nextAction(0, 1);
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(1, 1));
  assertEquals(HPatternShifter.Action.SHIFT_UP, tracker.nextAction(1, 2));
 }
 @Test void simpleThrottleAutoReverseDoesNotFightAHeldHPatternSlot() {
  // ticket 53603: "resolve simpleThrottle auto-reverse conflict" - native's simpleThrottle can
  // change currentGear on its own (via a different Var-toggle path this class never touches).
  // As long as the H-pattern's OWN request value is unchanged, the tracker must stay latched
  // and silent, never fighting to climb back to the held slot.
  var tracker = new HPatternShifter.Tracker();
  int gear = 0;
  HPatternShifter.Action a;
  while ((a = tracker.nextAction(gear, 1)) != HPatternShifter.Action.NONE) { gear++; }
  assertEquals(1, gear);
  gear = -1; // simpleThrottle auto-reverse, independent of this tracker
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(gear, 1));
  gear = 1; // simpleThrottle auto-forward again
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(gear, 1));
 }
 @Test void aSafetyRefusedShiftSimplyRetriesNextTickWithoutFalselyLatching() {
  // PartEngine.shiftUp()/shiftDown() can refuse a shift (e.g. unsafe direction/speed) without
  // changing the gear. Since the tracker only latches on step()==NONE (currentGear==target), a
  // refusal (gear unchanged, action still non-NONE) must keep retrying, not get stuck achieved.
  var tracker = new HPatternShifter.Tracker();
  assertEquals(HPatternShifter.Action.SHIFT_UP, tracker.nextAction(0, 1));
  // Simulate PartEngine refusing the shift (gear stays 0, as if shiftUp() returned false).
  assertEquals(HPatternShifter.Action.SHIFT_UP, tracker.nextAction(0, 1),
    "a refused shift must retry, not silently give up");
 }
}
