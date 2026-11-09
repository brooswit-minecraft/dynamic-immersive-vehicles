package io.github.brooswitminecraft.div;
/**
 * The one piece of IV's own towing model a towing vehicle's Sable body must still replicate:
 * {@code getRecursiveTowingThrust()}, the summed engine thrust of everything it is towing. IV
 * normally adds this straight to its own totalForce every tick and then integrates
 * {@code motion.addScaled(totalForce, 1/currentMass)} - one tick's worth of IV's own motion
 * units, not a continuous-time force in newtons - but that integration never runs for a vehicle
 * PhysicsBridge has taken over (see VehicleForcesMixin), so a towing vehicle would otherwise
 * feel lighter than it should.
 *
 * <p>PhysicsBridge already establishes the IV-motion&lt;-&gt;Sable-velocity conversion on
 * activation and writeback: {@code sableVelocity = ivMotion * speedFactor * 20}. Applying that
 * same factor to IV's per-tick motion delta ({@code thrust / currentMass}) gives the Sable
 * velocity delta this force must produce; multiplying by this body's own mass (which may be a
 * {@code tuning.massKg} override, not {@code currentMass}) turns that into the matching impulse:
 * {@code impulse = thrust * speedFactor * 20 * (sableMass / currentMass)}. Applied as a center-
 * of-mass impulse - matching where IV adds it - since it represents drivetrain effort
 * compensating for what is being towed, not tire grip.
 *
 * <p>This does not give the towed vehicle itself a coupled Sable body or hitch constraint; see
 * README.md "Scope" for what that would require and why it remains a separate, tracked gap.
 */
public final class TowingForces {
 private TowingForces() {}
 public static double impulse(double recursiveTowingThrust, double speedFactor, double sableMass, double ivCurrentMass) {
  return recursiveTowingThrust * speedFactor * 20 * (sableMass / Math.max(1, ivCurrentMass));
 }
}
