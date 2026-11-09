package io.github.brooswitminecraft.div;
import minecrafttransportsimulator.jsondefs.JSONPart;
import minecrafttransportsimulator.jsondefs.JSONVehicle;
import java.util.List;
/**
 * Pure category decision: does this vehicle definition, in this towing/opt-out state, get a
 * Sable body at all? Operates only on IV's plain JSON definition types, so it needs no live
 * entity, world, or Sable runtime and can run as an ordinary unit test.
 *
 * <p>This bridge currently models two categories: a ground vehicle with at least one wheel or
 * track contact device (of any count), and a fixed-wing aircraft or blimp (flight aerodynamics,
 * see {@link FlightForces}) with no installed rotor. A vehicle with no wheel/track device and no
 * aircraft/blimp flag (e.g. a pontoon-hulled boat - buoyancy is a different, unmodeled physics
 * domain), a rotorcraft (helicopter thrust-vectoring is a different, unmodeled physics domain
 * IV's own native solver treats separately), and an explicit per-car opt-out are all excluded on
 * purpose, not missed by accident. See README.md for the follow-up tasks tracking each gap.
 *
 * <p>A ground vehicle actively hitched behind another (a trailer) is now eligible on the same
 * terms as any other ground vehicle - {@link PhysicsBridge} gives it its own Sable body jointed
 * to its towing vehicle's body (see {@link HitchJoint}), rather than excluding it to IV's native
 * position-snap. A flight vehicle that is actively towed stays excluded: a towed aircraft/blimp
 * is not a category this bridge models, and is left on IV's native physics rather than guessed
 * at.
 */
public final class Classification {
 private Classification() {}
 public static boolean isDrivableGroundDevice(JSONPart.JSONPartGroundDevice ground) {
  return ground != null && (ground.isWheel || ground.isTread);
 }
 /** A rotor-lift propeller (helicopter main/tail rotor), as opposed to a plain forward-thrust
  * propeller - IV marks these explicitly and gives them their own thrust-vectoring solver this
  * bridge does not replicate. */
 public static boolean isRotor(JSONPart.JSONPartPropeller propeller) {
  return propeller != null && propeller.isRotor;
 }
 /** Aircraft and blimps use {@link FlightForces} instead of the raycast ground solver, and need
  * no wheel/track device to be eligible. */
 public static boolean isFlightVehicle(JSONVehicle definition) {
  return definition != null && definition.motorized != null
    && (definition.motorized.isAircraft || definition.motorized.isBlimp);
 }
 public static boolean eligible(JSONVehicle definition, List<JSONPart.JSONPartGroundDevice> installedGroundDevices,
     List<JSONPart.JSONPartPropeller> installedPropellers, boolean activelyTowed, boolean optedOut) {
  if (optedOut) return false;
  if (definition == null || definition.motorized == null) return false;
  if (isFlightVehicle(definition)) {
   // A towed flight vehicle (and a helicopter - any installed rotor propeller, which needs
   // rotor lift/vectoring this bridge does not model) stay on IV's native physics rather than
   // getting zero wing lift and forward-only thrust, or a hitch joint this bridge never builds
   // for the flight domain.
   if (activelyTowed) return false;
   return installedPropellers.stream().noneMatch(Classification::isRotor);
  }
  // A towed ground vehicle (trailer) is eligible on the same terms as any other ground
  // vehicle - PhysicsBridge is the one that additionally requires its towing vehicle to already
  // have an active Sable body before actually constructing the trailer's, since that is what the
  // hitch joint connects to; that dependency needs the registry, not this pure classification.
  return installedGroundDevices.stream().anyMatch(Classification::isDrivableGroundDevice);
 }
}
