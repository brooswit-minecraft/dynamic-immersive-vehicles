package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547 (John's comment 54486): pure unit coverage of the window/body-restriction
 * seam itself - no ServerLevel or any Minecraft class involved, since this helper takes none.
 */
class DiagnosticWindowTest {
 private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
 private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");

 @Test void trueOnlyWhenBodyExpectedAndTickInRange() {
  assertTrue(DiagnosticWindow.inWindow(A, 55, Set.of(A), 55, 60));
  assertTrue(DiagnosticWindow.inWindow(A, 60, Set.of(A), 55, 60));
  assertTrue(DiagnosticWindow.inWindow(A, 57, Set.of(A), 55, 60));
 }

 @Test void falseWhenTickOutsideRangeEitherSide() {
  assertFalse(DiagnosticWindow.inWindow(A, 54, Set.of(A), 55, 60));
  assertFalse(DiagnosticWindow.inWindow(A, 61, Set.of(A), 55, 60));
 }

 @Test void falseWhenBodyNotInExpectedSet() {
  assertFalse(DiagnosticWindow.inWindow(B, 57, Set.of(A), 55, 60));
 }

 @Test void singleTickWindowMatchesOnlyThatTick() {
  // The TOWING_REPLACEMENT transition's own usage: a one-tick window (ticksInPhase==1).
  assertTrue(DiagnosticWindow.inWindow(A, 1, Set.of(A, B), 1, 1));
  assertFalse(DiagnosticWindow.inWindow(A, 2, Set.of(A, B), 1, 1));
 }
}
