package io.github.brooswitminecraft.div.experimental;
import io.github.brooswitminecraft.div.TireForces;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * EXPERIMENTAL - not wired into {@code PhysicsBridge}/{@code Body.stepGround}, not part of any
 * production code path. Kept here, compiled and tested, purely so an attempted fix for ticket
 * MINECRAFT-503's rollover root cause (an independent, single-shot, per-wheel lateral slip
 * correction that saturates friction and alternates sign every tick - real captured evidence in
 * ticket comments 53010/53011) is inspectable and reproducible by review without being mistaken
 * for a shipped change. See {@code MultiContactFrictionSolverExperimentTest} for the whole-
 * vehicle replay regression against this, and ticket comments 53017/53018/53019 for the full
 * history of what was tried, what review found wrong with each attempt, and the outstanding
 * question about this regression's own fidelity (fixed, non-raycast normal forces, omitting the
 * suspension's real response to tilt/load transfer - see that class's own doc for detail).
 *
 * <p>Three variants were tried on this ticket and are summarized here for anyone picking this up:
 * <ol>
 *  <li>Replace the lateral slip target's {@code mass/wheelCount} denominator with this class's
 *  {@link #contactLateralEffectiveMass}, in a single non-iterative pass (no PGS). Reverted: it
 *  under-corrects the case every wheel sees identical shared slip with no rotation, which
 *  {@code mass/wheelCount} handles exactly in that one-shot form.</li>
 *  <li>An exact simultaneous joint solve (build the real NxN "mutual effective mass" matrix and
 *  invert/solve it for all contacts' impulses at once). Found to be SINGULAR for real coplanar
 *  wheel contacts (confirmed via eigenvalues: rank 2 of 4) - review also pointed out a specific
 *  analytical error in how its nullspace was characterized (ticket comment 53019); not pursued
 *  further as a one-shot linear solve for that reason.</li>
 *  <li>This class: a bounded (fixed iteration count), projected-Gauss-Seidel-style iterative
 *  solve - the strategy reviewed and asked for in comment 53017. Implemented and unit-tested
 *  below (same per-contact effective mass, but applied incrementally across bounded passes,
 *  never inverting anything). A whole-vehicle multi-tick replay against this (see the paired
 *  experiment test) showed only a modest improvement over the pre-fix behavior on a SIMPLIFIED
 *  (fixed-normal-force) harness - not yet shown to resolve the real captured divergence, and
 *  review flagged that the harness's omission of load-transfer/suspension coupling may itself be
 *  responsible for some or all of that gap, not the solver. That is the open, unresolved question
 *  this class exists to let review re-examine directly, rather than taking prose at its word.</li>
 * </ol>
 */
public final class MultiContactFrictionSolver {
 private MultiContactFrictionSolver() {}
 /**
  * Real rigid-body contact-point effective mass for the lateral (slip) direction at one wheel's
  * contact point, 1/(1/mass + (point x right).I^-1.(point x right)) - standard rigid-body contact
  * mechanics. {@code point}/{@code right} are world-frame; {@code inverseOrientation} rotates a
  * world vector into the body's own principal-axis frame, where {@code inertiaBody} (diagonal
  * solid-box tensor) applies. Validated against real production angularVelocity data to ~1e-5
  * rad/s over 30 real ticks across two separate placements (ticket comment 53008).
  */
 public static double contactLateralEffectiveMass(double mass, Vector3dc point, Vector3dc right, Quaterniondc inverseOrientation, Vector3dc inertiaBody) {
  return contactEffectiveMass(mass,point,right,inverseOrientation,inertiaBody);
 }
 /**
  * Same formula as {@link #contactLateralEffectiveMass}, generalized to any contact-plane
  * direction - review (comment 53034) correctly noted the braking self-limit was still using
  * mass/wheelCount rather than a real directional effective mass the way lateral does, which is
  * the exact same potential error 53010/53011/53022 identified for lateral, just in the forward
  * direction instead of right.
  */
 public static double contactEffectiveMass(double mass, Vector3dc point, Vector3dc direction, Quaterniondc inverseOrientation, Vector3dc inertiaBody) {
  Vector3d torqueAxisBody=inverseOrientation.transform(new Vector3d(point).cross(direction));
  double rotationalTerm=torqueAxisBody.x*torqueAxisBody.x/inertiaBody.x()
    +torqueAxisBody.y*torqueAxisBody.y/inertiaBody.y()+torqueAxisBody.z*torqueAxisBody.z/inertiaBody.z();
  return 1.0/(1.0/mass+rotationalTerm);
 }
 /** One wheel's world-frame contact geometry/inputs for {@link #solveFriction}.
  * {@code frictionMultiplier}/{@code rollingResistanceMultiplier} (MINECRAFT-508) scale this
  * contact's own ground-surface response relative to dry pavement. {@code brakeMagnitude}
  * (MINECRAFT-504, comment 53603) is PER-WHEEL, not a single vehicle-wide scalar - a
  * front/steering wheel typically only ever carries the service-brake magnitude, while a rear
  * wheel can additionally carry the analog handbrake's own magnitude, composed by the CALLER
  * (PhysicsBridge) before constructing this record, not by this solver. The two shorter
  * constructors below default frictionMultiplier/rollingResistanceMultiplier to 1.0 (reproducing
  * today's exact pre-surface values byte-identically) and, for the 5-arg form, brakeMagnitude to
  * 0.0 (unbraked). */
 public record FrictionContact(Vector3d point, Vector3d forward, Vector3d right, double normal, double skidSteerMultiplier,
     double frictionMultiplier, double rollingResistanceMultiplier, double brakeMagnitude) {
  public FrictionContact(Vector3d point, Vector3d forward, Vector3d right, double normal, double skidSteerMultiplier) {
   this(point, forward, right, normal, skidSteerMultiplier, 1.0, 1.0, 0.0);
  }
  /** MINECRAFT-508's own 7-arg shape (explicit surface multipliers, no brake magnitude yet) -
   * defaults brakeMagnitude to 0.0 (unbraked), kept so that ticket's own existing call sites
   * need no changes. */
  public FrictionContact(Vector3d point, Vector3d forward, Vector3d right, double normal, double skidSteerMultiplier,
      double frictionMultiplier, double rollingResistanceMultiplier) {
   this(point, forward, right, normal, skidSteerMultiplier, frictionMultiplier, rollingResistanceMultiplier, 0.0);
  }
  public FrictionContact(Vector3d point, Vector3d forward, Vector3d right, double normal, double skidSteerMultiplier, double brakeMagnitude) {
   this(point, forward, right, normal, skidSteerMultiplier, 1.0, 1.0, brakeMagnitude);
  }
 }
 /** One wheel's accumulated longitudinal/lateral tire force after the bounded solve. */
 public record FrictionResult(double longitudinal, double lateral) {}
 /** Fixed iteration bound - cheap (iterations x wheelCount simple vector ops per tick). A hard
  * bound, not a convergence-dependent loop, so a tick's cost never varies. */
 public static final int FRICTION_SOLVER_ITERATIONS = 4;
 /**
  * Bounded multi-contact friction solve: a projected-Gauss-Seidel-style iteration across all
  * simultaneously active wheel contacts, instead of each wheel computing its lateral/
  * longitudinal target independently from one stale per-tick velocity snapshot.
  *
  * <p>Each pass, for every contact: measure slip from the shared {@code workingVelocity}/
  * {@code workingAngular} (which reflects every contact's own accumulated correction so far -
  * this is the actual cross-contact coupling), compute this wheel's target longitudinal
  * (unchanged drive/brake/rolling-resistance semantics) and lateral (using the real per-contact
  * {@link #contactLateralEffectiveMass}, not mass/wheelCount - mass/wheelCount as a per-ITERATION
  * step was tried and found numerically unstable under iteration against real geometry,
  * diverging rather than converging) force, clamp both together to the friction circle
  * (unchanged {@link TireForces#friction}), apply only the INCREMENT over this contact's own
  * previously accumulated force this tick, and fold that increment's impulse into the shared
  * working state so the next contact (and the next pass) sees it. {@code workingVelocity}/
  * {@code workingAngular} must already reflect this tick's normal/suspension impulses (staged by
  * the caller before calling this) - friction is solved on top of that, not before it.
  *
  * <p>This never requires inverting anything and does not assume any contact fully cancels its
  * own slip in a bounded number of passes - some of the body's translational slip can legitimately
  * end up converted to a small induced rotation instead.
  */
 public static FrictionResult[] solveFriction(FrictionContact[] contacts, double mass, Vector3dc inertiaBody,
     Quaterniondc orientation, Quaterniondc inverseOrientation,
     Vector3d workingVelocity, Vector3d workingAngular,
     double brakeForcePerWheel, double driveForcePerWheel, double friction, double lateralGripMultiplier, double dt, int iterations) {
  int n=contacts.length;
  // accLong/accLat track the TOTAL applied-so-far longitudinal/lateral force (post friction-
  // circle clamp) purely so each pass's impulse delta is correct - same role for both.
  // accBrakeConstraint is SEPARATE: only braking's own self-limiting component accumulates (see
  // below), bounded by its own brakeForcePerWheel cap independent of the combined friction
  // circle, so it cannot be driven by (or drive) the combined clamp's scaling.
  double[] accLong=new double[n], accLat=new double[n], accBrakeConstraint=new double[n];
  for (int pass=0; pass<iterations; pass++) {
   for (int i=0; i<n; i++) {
    var c=contacts[i];
    Vector3d contactVelocity=new Vector3d(workingAngular).cross(c.point()).add(workingVelocity);
    double speed=contactVelocity.dot(c.forward()), sideSpeed=contactVelocity.dot(c.right());
    // Rolling resistance is a continuous, velocity-proportional damping law (F=-k*v, k=20) -
    // recomputing it fresh from the current speed each pass is physically correct (same as a
    // real damper), not a one-shot "cancel everything" formula, so it does not need the
    // accumulate treatment below. MINECRAFT-508: k is scaled by this contact's own ground-
    // surface rollingResistanceMultiplier (1.0 reproduces today's exact k=20 byte-identically).
    double rollingResistance=-speed*20*c.rollingResistanceMultiplier();
    // Drive is a genuinely PRESCRIBED target (a constant commanded force, not derived from
    // current slip) - recomputing it fresh each pass and replacing the prior value is correct.
    // Braking's own min(brakeForcePerWheel, |speed|*mass/wheelCount/dt) is NOT purely prescribed
    // (review comment 53025): its second branch has the same "cancel in one DT" character the
    // lateral target has, so it needs the SAME accumulate treatment lateral got in 53022 - but
    // its OWN bound is brakeForcePerWheel, not the combined friction circle, so it accumulates
    // into its own small accumulator capped at that bound BEFORE being combined with drive/
    // rolling-resistance and handed to the shared friction-circle clamp (which still protects
    // the combined lateral+longitudinal budget exactly as before). Capping the ACCUMULATOR
    // itself (not re-deriving a fresh min() each pass) correctly handles both regimes: at high
    // speed the first pass already saturates the cap and stays pinned there (stable, no
    // overshoot/growth); at low speed the increment shrinks with speed and accumulates smoothly
    // toward full cancellation instead of oscillating like the old single-shot lateral code did.
    // PER-CONTACT brake magnitude (ticket MINECRAFT-504, comment 53603) - each wheel's own
    // c.brakeMagnitude(), not a single vehicle-wide scalar, so a rear/handbrake-capable wheel
    // can brake while a front wheel does not (or vice versa for an asymmetric vehicle).
    boolean braking=c.brakeMagnitude()>0.0;
    double scaledBrakeForcePerWheel=brakeForcePerWheel*c.brakeMagnitude();
    double prescribedLongitudinal; double brakeIncrement;
    if (braking) {
     prescribedLongitudinal=0;
     // Real forward-direction contact effective mass (review comment 53034), not mass/wheelCount -
     // the exact same correction lateral already got, now applied here too.
     double longitudinalEffectiveMass=contactEffectiveMass(mass,c.point(),c.forward(),inverseOrientation,inertiaBody);
     brakeIncrement=-Math.signum(speed)*Math.abs(speed)*longitudinalEffectiveMass/dt*c.skidSteerMultiplier();
    } else { prescribedLongitudinal=driveForcePerWheel*c.skidSteerMultiplier(); brakeIncrement=0; }
    double candidateBrakeConstraint=Math.max(-scaledBrakeForcePerWheel,Math.min(scaledBrakeForcePerWheel,accBrakeConstraint[i]+brakeIncrement));
    accBrakeConstraint[i]=candidateBrakeConstraint;
    double longitudinalTarget=prescribedLongitudinal+rollingResistance+candidateBrakeConstraint;
    // Lateral is an ACCUMULATED CONSTRAINT CORRECTION, not a prescribed target (review comment
    // 53022): -sideSpeed*effectiveMass/dt is "the additional force needed, beyond what's already
    // been applied this tick, to cancel the REMAINING measured slip" - a genuine per-pass
    // INCREMENT on top of what earlier passes already accumulated, not a replacement for it. The
    // earlier (buggy) version replaced accLat with this pass's fresh, already-shrunk value,
    // which discarded prior passes' correction every other pass instead of converging toward it.
    double effectiveMass=contactLateralEffectiveMass(mass,c.point(),c.right(),inverseOrientation,inertiaBody);
    double lateralIncrement=-sideSpeed*effectiveMass/dt;
    double candidateLateral=accLat[i]+lateralIncrement;
    // Ticket 53331/53332/53335: review correctly rejected scaling the per-pass increment (or
    // the accumulator) as unsound - either merely damps PGS convergence RATE, so a high
    // iteration count (the real installed baseline's own 64, confirmed via 53333) restores
    // nearly full grip regardless, and scaling the accumulator additionally compounds
    // geometrically across passes. Using the ANISOTROPIC clamp instead (TireForces.
    // frictionAnisotropic) bounds the CONVERGED STEADY STATE itself via a smaller lateral
    // semi-axis, independent of iteration count - lateralGripMultiplier=1.0 is algebraically
    // byte-identical to the original circular TireForces.friction (see that method's own doc).
    // MINECRAFT-508: this contact's own ground-surface frictionMultiplier scales the shared mu
    // (1.0 reproduces today's exact `friction` value byte-identically).
    var tire=TireForces.frictionAnisotropic(longitudinalTarget,candidateLateral,c.normal(),friction*c.frictionMultiplier(),lateralGripMultiplier);
    double deltaLong=tire.longitudinal()-accLong[i], deltaLat=tire.lateral()-accLat[i];
    accLong[i]=tire.longitudinal(); accLat[i]=tire.lateral();
    Vector3d impulse=new Vector3d(c.forward()).mul(deltaLong*dt).fma(deltaLat*dt,c.right());
    workingVelocity.add(new Vector3d(impulse).div(mass));
    Vector3d torqueBody=inverseOrientation.transform(new Vector3d(c.point()).cross(impulse));
    Vector3d domegaBody=new Vector3d(torqueBody.x/inertiaBody.x(),torqueBody.y/inertiaBody.y(),torqueBody.z/inertiaBody.z());
    workingAngular.add(orientation.transform(domegaBody));
   }
  }
  FrictionResult[] results=new FrictionResult[n];
  for (int i=0; i<n; i++) results[i]=new FrictionResult(accLong[i],accLat[i]);
  return results;
 }
}
