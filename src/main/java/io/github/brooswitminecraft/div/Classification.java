package io.github.brooswitminecraft.div;
import minecrafttransportsimulator.jsondefs.JSONPart;
import minecrafttransportsimulator.jsondefs.JSONVehicle;
import java.util.List;
/**
 * Pure category decision: does this vehicle definition, in this opt-out state, get a Sable body
 * at all? Operates only on IV's plain JSON definition types, so it needs no live entity, world,
 * or Sable runtime and can run as an ordinary unit test. Towed status plays no part in this
 * decision - see the class doc below for why.
 *
 * <p>This bridge currently models two categories: a ground vehicle with at least one wheel or
 * track contact device (of any count), and a fixed-wing aircraft or blimp (flight aerodynamics,
 * see {@link FlightForces}) with no installed rotor. A vehicle with no wheel/track device and no
 * aircraft/blimp flag (e.g. a pontoon-hulled boat - buoyancy is a different, unmodeled physics
 * domain), a rotorcraft (helicopter thrust-vectoring is a different, unmodeled physics domain
 * IV's own native solver treats separately), and an explicit per-car opt-out are all excluded on
 * purpose, not missed by accident. See README.md for the follow-up tasks tracking each gap.
 *
 * <p>A vehicle actively hitched behind another (a trailer) is eligible on the same terms as any
 * other vehicle in its own category, ground or flight - {@link PhysicsBridge} gives it its own
 * Sable body jointed to its towing vehicle's body (see {@link HitchJoint}; the joint math itself
 * is domain-agnostic, since it only connects two already-classified bodies), rather than
 * excluding it to IV's native position-snap. Only a category this bridge does not model at all
 * is still excluded regardless of towed status - currently just boats/float-only hulls, which
 * have no Sable body type on this branch to joint in the first place (a separate, not-yet-merged
 * task; see README.md).
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
     List<JSONPart.JSONPartPropeller> installedPropellers, boolean optedOut) {
  if (optedOut) return false;
  if (definition == null || definition.motorized == null) return false;
  if (isFlightVehicle(definition)) {
   // A helicopter (any installed rotor propeller) needs rotor lift/vectoring this bridge does
   // not model; leave it on IV's native physics rather than giving it zero wing lift and
   // forward-only thrust. A towed aircraft/blimp is otherwise eligible on the same terms as any
   // other flight vehicle - see the class doc for why towed status alone no longer excludes it.
   return installedPropellers.stream().noneMatch(Classification::isRotor);
  }
  // A towed ground vehicle (trailer) is eligible on the same terms as any other ground
  // vehicle - PhysicsBridge is the one that additionally requires its towing vehicle to already
  // have an active Sable body before actually constructing the trailer's, since that is what the
  // hitch joint connects to; that dependency needs the registry, not this pure classification.
  return installedGroundDevices.stream().anyMatch(Classification::isDrivableGroundDevice);
 }
}
