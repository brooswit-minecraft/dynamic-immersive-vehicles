package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547 (John's comment 54494): pure coverage of {@link DiagnosticContext#matches} -
 * constructed directly (via {@code arm}/a throwaway instance check) with no static-state
 * interaction and no ServerLevel dependency.
 */
class DiagnosticContextTest {
 private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
 private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

 @Test void matchesOnlyWhenBodyInSetAndGameTimeInRange() {
  DiagnosticContext.arm(Set.of(A), 100, 110);
  assertTrue(DiagnosticContext.isActiveFor(A, 100));
  assertTrue(DiagnosticContext.isActiveFor(A, 110));
  assertTrue(DiagnosticContext.isActiveFor(A, 105));
 }

 @Test void falseOutsideGameTimeRangeEitherSide() {
  DiagnosticContext.arm(Set.of(A), 100, 110);
  assertFalse(DiagnosticContext.isActiveFor(A, 99));
  assertFalse(DiagnosticContext.isActiveFor(A, 111));
 }

 @Test void falseForABodyNotInTheArmedSet() {
  DiagnosticContext.arm(Set.of(A), 100, 110);
  assertFalse(DiagnosticContext.isActiveFor(B, 105));
 }

 @Test void falseWheneverNothingIsArmed() {
  DiagnosticContext.disarm();
  assertFalse(DiagnosticContext.isActiveFor(A, 105));
 }

 @Test void reArmingExtendsTheSetAndWindowAsExpectedByThePhaseResetUsage() {
  DiagnosticContext.arm(Set.of(A), 100, 110);
  DiagnosticContext.arm(Set.of(A, B), 108, 120);
  assertTrue(DiagnosticContext.isActiveFor(A, 115));
  assertTrue(DiagnosticContext.isActiveFor(B, 115));
  // The re-arm REPLACES the prior window - 100..107 is no longer covered by this new context.
  assertFalse(DiagnosticContext.isActiveFor(A, 100));
 }
}
