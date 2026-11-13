package io.github.brooswitminecraft.div;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WheelAuxStalenessTest {
 @Test void freshInputIsNeverStale() {
  assertFalse(WheelAuxStaleness.isStale(100, 100, 100));
  assertFalse(WheelAuxStaleness.isStale(100, 150, 100));
 }
 @Test void exactlyAtTheThresholdIsNotYetStale() {
  assertFalse(WheelAuxStaleness.isStale(0, 100, 100));
 }
 @Test void pastTheThresholdIsStale() {
  assertTrue(WheelAuxStaleness.isStale(0, 101, 100));
  assertTrue(WheelAuxStaleness.isStale(0, 1000, 100));
 }
}
