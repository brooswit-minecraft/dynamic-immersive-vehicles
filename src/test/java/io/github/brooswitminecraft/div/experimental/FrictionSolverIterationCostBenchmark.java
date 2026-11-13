package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * Warmed, repeated CPU-cost measurement for {@code MultiContactFrictionSolver.solveFriction} at
 * several iteration counts - ticket comment 53127/53130's explicit ask, replacing the earlier
 * single-sample JVM timings in {@code WindowTwoIterationCountComparisonTest} (correctly flagged
 * there as not a rigorous benchmark). Not a JMH benchmark (no fork isolation, no iteration-level
 * statistics beyond min/median/mean) but a large warmup phase followed by many measured calls on
 * a FIXED representative 4-contact input, which is enough to separate real per-call cost from
 * JIT warmup noise - the single-sample numbers mixed the two.
 *
 * <p>Diagnostic only, no production code touched.
 */
class FrictionSolverIterationCostBenchmark {
 private static final double MASS=350.0, FRICTION=1.0, DT=0.05, BRAKE_FORCE=12000.0;
 /** SYNTHETIC contacts (ticket comment 53131's correction) - a 4-active-contact braking scenario
  * using the real ATV's x/z LOCAL_OFFSETS values and MultiStateSolverConvergenceTest's t=46
  * normal loads, but the y-coordinates here are an arbitrary placeholder, NOT the real COM-
  * relative anchor (which subtracts CENTER and adds half the suspension travel - see
  * {@code PhysicsBridge.Body}'s constructor). Good enough for a cost measurement, which only
  * cares about contact COUNT and iteration count, not exact geometry - NOT a claim that these
  * are the real contact points. */
 private static MultiContactFrictionSolver.FrictionContact[] representativeContacts() {
  double[] normals={878.36, 1035.55, 1235.91, 684.68};
  Vector3d[] points={new Vector3d(0.35,-0.15,-0.0625), new Vector3d(-0.375,-0.15,1.25), new Vector3d(0.375,-0.15,1.25), new Vector3d(-0.375,-0.15,-0.0625)};
  var contacts=new MultiContactFrictionSolver.FrictionContact[4];
  for (int i=0;i<4;i++) contacts[i]=new MultiContactFrictionSolver.FrictionContact(points[i], new Vector3d(0,0,1), new Vector3d(1,0,0), normals[i], 1.0,1.0);
  return contacts;
 }
 private static long measure(int iterations, int calls) {
  var inertiaBody=new Vector3d(350.0/3*(0.4411*0.4411+1.05*1.05), 350.0/3*(0.425*0.425+1.05*1.05), 350.0/3*(0.425*0.425+0.4411*0.4411));
  Quaterniond orientation=new Quaterniond(), inverse=new Quaterniond();
  long total=0;
  for (int c=0;c<calls;c++) {
   var contacts=representativeContacts();
   Vector3d velocity=new Vector3d(0.6,0,1.2), angular=new Vector3d(0.01,0,0.02);
   long start=System.nanoTime();
   MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,velocity,angular,BRAKE_FORCE/4.0,0,FRICTION,1.0,DT,iterations);
   total += System.nanoTime()-start;
  }
  return total;
 }

 @Test void warmedRepeatedCostComparisonAcrossIterationCounts() {
  int warmupCalls=20000, measuredCalls=20000;
  int[] iterationCounts={4,32,64,128};
  // Warm up the JIT at each iteration count separately before measuring any of them, so an
  // earlier count's warmup doesn't unfairly help/hurt a later one's first measured calls.
  for (int iterations : iterationCounts) measure(iterations, warmupCalls);
  for (int iterations : iterationCounts) {
   long totalNanos=measure(iterations, measuredCalls);
   double avgNanosPerCall=(double) totalNanos / measuredCalls;
   System.out.println("[FrictionSolverIterationCostBenchmark] iterations="+iterations
     +" avgNanosPerCall="+avgNanosPerCall+" avgMicrosPerCall="+(avgNanosPerCall/1000.0)
     +" totalForAllActiveTicksIfCalledOncePerTickFor1Hour(20tps)="+(avgNanosPerCall*20*3600/1_000_000_000.0)+"s of CPU");
  }
 }
}
