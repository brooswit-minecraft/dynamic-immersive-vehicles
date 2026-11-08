package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TireForcesTest {
 @Test void airborneHasNoTraction() { assertEquals(new TireForces.Force(0,0), TireForces.friction(100,100,0,1)); }
 @Test void combinedForcesStayInsideCircle() { var f=TireForces.friction(300,400,100,1); assertEquals(100,Math.hypot(f.longitudinal(),f.lateral()),1e-9); }
 @Test void reboundNeverPullsGround() { assertEquals(0,TireForces.suspension(0.1,10,1000,1000)); }
 @Test void springSupportsLoad() { assertEquals(2000,TireForces.suspension(.2,0,10000,1000)); }
}
