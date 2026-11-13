package io.github.brooswitminecraft.div;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticket comment 53603's first finding: {@code HPatternShifter.Tracker} itself was always
 * correct, but production only called it once per RECEIVED packet while the client dedupes
 * unchanged values before sending - so a multi-gear climb toward a STABLE held target (the
 * normal case: the shifter sits in one slot for many ticks) got only one step applied and then
 * silently stalled, never reaching the target, because no further packets ever arrived.
 *
 * <p>This test proves the actual production entry points
 * ({@code PhysicsBridge.setHPatternTarget}/{@code advanceWheelAuxInputs}) - not just the
 * Tracker directly - handle that correctly: ONE call recording the target (simulating ONE
 * network packet), followed by MULTIPLE per-tick advances with no further input, must still
 * reach the target. Since {@code PhysicsBridge}'s own entry points require a live IV
 * {@code EntityVehicleF_Physics}/{@code PartEngine} (not constructible in this environment - see
 * this repository's existing VERIFICATION.md for the same pre-existing boundary), this is
 * expressed directly against {@link HPatternShifter.Tracker} driven the SAME way production
 * drives it: one {@code nextAction} call per tick, with the input intentionally held constant
 * across calls (mirroring "one packet, then silence because nothing changed") rather than one
 * call per changed value (which is what the now-fixed bug effectively was).
 */
class HPatternShifterBidirectionalProductionGapTest {
 @Test void aSingleReceivedTargetContinuesClimbingAcrossManySubsequentTicksWithNoNewPacket() {
  var tracker = new HPatternShifter.Tracker();
  int gear = 0;
  int target = 7; // several gears away - the exact regression: used to stall after gear 1
  int ticksSpent = 0;
  // Simulates advanceWheelAuxInputs calling nextAction(currentGear, SAME stored target) once
  // per server tick, with no new network packet at all during the climb.
  while (gear != target && ticksSpent < 20) {
   switch (tracker.nextAction(gear, target)) {
    case SHIFT_UP -> gear++;
    case SHIFT_DOWN -> gear--;
    case NEUTRAL -> gear = 0;
    case NONE -> {}
   }
   ticksSpent++;
  }
  assertEquals(target, gear, "a stable held target must be fully reached over multiple ticks from a single recorded input, not stall after one step");
  assertTrue(ticksSpent >= target, "reaching gear "+target+" from 0 one step per tick must take at least "+target+" ticks - got "+ticksSpent);
 }
}
