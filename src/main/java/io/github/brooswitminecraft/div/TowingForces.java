package io.github.brooswitminecraft.div;
/**
 * The one piece of IV's own towing model a towing vehicle's Sable body must still replicate:
 * {@code getRecursiveTowingThrust()}, the summed engine thrust of everything it is towing. IV
 * normally adds this straight to its own thrustForce every tick (see
 * EntityVehicleF_Physics#getForcesAndMotions), but that method never runs for a vehicle
 * PhysicsBridge has taken over, so a towing vehicle would otherwise feel lighter than it should.
 * Applied as a center-of-mass impulse - matching where IV adds it - since it represents
 * drivetrain effort compensating for what is being towed, not tire grip.
 *
 * <p>This does not give the towed vehicle itself a coupled Sable body or hitch constraint; see
 * README.md "Scope" for what that would require and why it remains a separate, tracked gap.
 */
public final class TowingForces {
 private TowingForces() {}
 public static double forwardImpulse(double recursiveTowingThrust, double dt) {
  return recursiveTowingThrust * dt;
 }
}
