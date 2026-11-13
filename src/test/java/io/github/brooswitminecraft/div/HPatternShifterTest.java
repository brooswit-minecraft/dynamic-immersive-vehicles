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
  assertEquals(HPatternShifter.Action.NONE, HPatternShifter.step(0, HPatternShifter.NEUTRAL_REQUEST),
    "a held-neutral request must not be reapplied once already in neutral");
 }
 @Test void reverseRequestOnlyEngagesFromNeutral() {
  assertEquals(HPatternShifter.Action.SHIFT_DOWN, HPatternShifter.step(0, HPatternShifter.REVERSE_REQUEST));
  assertEquals(HPatternShifter.Action.NONE, HPatternShifter.step(2, HPatternShifter.REVERSE_REQUEST),
    "reverse request while already in a forward gear must not force a shift (matches native: must pass through neutral first)");
  assertEquals(HPatternShifter.Action.NONE, HPatternShifter.step(-1, HPatternShifter.REVERSE_REQUEST),
    "already in reverse - held request must not be reapplied");
 }
 @Test void forwardDirectSelectClimbsUpOnlyTowardTarget() {
  assertEquals(HPatternShifter.Action.SHIFT_UP, HPatternShifter.step(0, 3));
  assertEquals(HPatternShifter.Action.SHIFT_UP, HPatternShifter.step(2, 3));
  assertEquals(HPatternShifter.Action.NONE, HPatternShifter.step(3, 3));
 }
 @Test void forwardDirectSelectNeverClimbsDownMatchingNativesOwnAsymmetry() {
  // Deliberate: native IV's own shiftSelectionVar branch has this exact limitation (confirmed by
  // decompiling the installed jar) - replicated faithfully, not "fixed" beyond this ticket's scope.
  assertEquals(HPatternShifter.Action.NONE, HPatternShifter.step(5, 2));
 }

 // --- Tracker: the actual per-engine usage, and the fix for ticket 53573's explicit ask ---

 @Test void trackerClimbsMultipleTicksTowardAStableTarget() {
  var tracker = new HPatternShifter.Tracker();
  int gear = 0;
  assertEquals(HPatternShifter.Action.SHIFT_UP, tracker.nextAction(gear, 3)); gear = 1;
  assertEquals(HPatternShifter.Action.SHIFT_UP, tracker.nextAction(gear, 3)); gear = 2;
  assertEquals(HPatternShifter.Action.SHIFT_UP, tracker.nextAction(gear, 3)); gear = 3;
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(gear, 3),
    "reaching the held target must stop climbing, not keep re-triggering");
 }
 @Test void trackerDoesNotReclimbAHeldTargetAfterReaching() {
  var tracker = new HPatternShifter.Tracker();
  assertEquals(HPatternShifter.Action.SHIFT_UP, tracker.nextAction(0, 1));
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(1, 1));
  // Same held target evaluated again many ticks later - must stay NONE.
  for (int i = 0; i < 10; i++) assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(1, 1));
 }
 @Test void aPaddleShiftIsNotClobberedByAnUnchangedHeldHPatternTarget() {
  // Exactly the scenario ticket 53573 asked to be tested: the physical shifter is held in gear
  // 4 (reached via the tracker), then a paddle shifts down to gear 3 independently (the paddle
  // path never touches this tracker - it only calls PartEngine.shiftDown() directly). The
  // SAME held target (4) must not force a climb back up just because currentGear now sits
  // below it.
  var tracker = new HPatternShifter.Tracker();
  int gear = 0;
  HPatternShifter.Action a;
  while ((a = tracker.nextAction(gear, 4)) != HPatternShifter.Action.NONE) { assertEquals(HPatternShifter.Action.SHIFT_UP, a); gear++; }
  assertEquals(4, gear, "setup: should have climbed to the held target, and latched achieved via the final NONE");
  gear = 3; // simulated independent paddle downshift
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(gear, 4),
    "an unchanged held H-pattern target must not revert a paddle shift that moved currentGear below it");
 }
 @Test void movingTheShifterToAGenuinelyNewSlotResumesClimbing() {
  var tracker = new HPatternShifter.Tracker();
  tracker.nextAction(0, 1);
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(1, 1));
  // The shifter is physically moved to a new slot (2) - a genuinely new target value, not a
  // re-assertion of the old one, so climbing must resume.
  assertEquals(HPatternShifter.Action.SHIFT_UP, tracker.nextAction(1, 2));
 }
 @Test void trackerHandlesNeutralAndReverseLatchingTheSameWay() {
  var tracker = new HPatternShifter.Tracker();
  assertEquals(HPatternShifter.Action.NEUTRAL, tracker.nextAction(3, HPatternShifter.NEUTRAL_REQUEST));
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(0, HPatternShifter.NEUTRAL_REQUEST));
  // A later, independent action moves gear away from 0 (e.g. the reverse-request branch, or a
  // paddle) while the neutral request is still held - must not be re-forced back to neutral.
  assertEquals(HPatternShifter.Action.NONE, tracker.nextAction(1, HPatternShifter.NEUTRAL_REQUEST));
 }
}
