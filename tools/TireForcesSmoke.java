import io.github.brooswitminecraft.div.TireForces;
public class TireForcesSmoke {
 public static void main(String[] args) {
  var airborne=TireForces.friction(100,100,0,1);
  if(airborne.longitudinal()!=0 || airborne.lateral()!=0) throw new AssertionError("airborne traction");
  for(int x=-500;x<=500;x+=25) for(int y=-500;y<=500;y+=25) {
   var f=TireForces.friction(x,y,100,1);
   if(Math.hypot(f.longitudinal(),f.lateral())>100.0000001) throw new AssertionError("friction circle");
  }
  if(TireForces.suspension(.1,10,1000,1000)!=0) throw new AssertionError("ground pulling");
  if(TireForces.suspension(.2,0,10000,1000)!=2000) throw new AssertionError("spring support");
  System.out.println("PASS: airborne zero traction, 1681 friction-circle vectors, no ground pulling, spring support");
 }
}
