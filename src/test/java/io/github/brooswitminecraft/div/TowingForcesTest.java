package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TowingForcesTest {
 @Test void noTowedThrustMeansNoImpulse() { assertEquals(0, TowingForces.forwardImpulse(0,.05)); }
 @Test void towedEngineThrustScalesByTimestep() { assertEquals(50, TowingForces.forwardImpulse(1000,.05)); }
 @Test void negativeThrustStaysNegative() { assertEquals(-25, TowingForces.forwardImpulse(-500,.05)); }
}
