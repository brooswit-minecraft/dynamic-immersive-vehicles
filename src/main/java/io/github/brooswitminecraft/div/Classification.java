package io.github.brooswitminecraft.div;
import minecrafttransportsimulator.jsondefs.JSONPart;
import minecrafttransportsimulator.jsondefs.JSONVehicle;
import java.util.List;
/**
 * Pure category decision: does this vehicle definition, in this towing/opt-out state, get a
 * Sable body at all? Operates only on IV's plain JSON definition types, so it needs no live
 * entity, world, or Sable runtime and can run as an ordinary unit test.
 *
 * <p>This bridge currently models three categories: a ground vehicle with at least one wheel or
 * track contact device (of any count), a fixed-wing aircraft or blimp (flight aerodynamics, see
 * {@link FlightForces}), and a rotorcraft - any aircraft with an installed rotor propeller
 * (helicopter thrust-vectoring, see {@link RotorForces}) - needing no wheel/track device either
 * way. A vehicle with no wheel/track device and no aircraft/blimp flag (e.g. a pontoon-hulled
 * boat - buoyancy is a different, unmodeled physics domain), a vehicle actively hitched behind
 * another, and an explicit per-car opt-out are all excluded on purpose, not missed by accident.
 * See README.md for the follow-up tasks tracking each gap.
 */
public final class Classification {
 private Classification() {}
 public static boolean isDrivableGroundDevice(JSONPart.JSONPartGroundDevice ground) {
  return ground != null && (ground.isWheel || ground.isTread);
 }
 /** A rotor-lift propeller (helicopter main/tail rotor), as opposed to a plain forward-thrust
  * propeller - IV marks these explicitly and gives them their own thrust-vectoring solver this
  * bridge replaces with {@link RotorForces} instead of the fixed-wing model. */
 public static boolean isRotor(JSONPart.JSONPartPropeller propeller) {
  return propeller != null && propeller.isRotor;
 }
 /** Any installed rotor propeller makes this a rotorcraft (helicopter): {@link RotorForces}
  * applies instead of {@link FlightForces}'s fixed-wing/blimp model, which has no wing to
  * generate lift from on a vehicle like this. */
 public static boolean isRotorcraft(List<JSONPart.JSONPartPropeller> installedPropellers) {
  return installedPropellers.stream().anyMatch(Classification::isRotor);
 }
 /** Aircraft and blimps use {@link FlightForces} or, for a rotorcraft, {@link RotorForces}
  * instead of the raycast ground solver, and need no wheel/track device to be eligible. */
 public static boolean isFlightVehicle(JSONVehicle definition) {
  return definition != null && definition.motorized != null
    && (definition.motorized.isAircraft || definition.motorized.isBlimp);
 }
 public static boolean eligible(JSONVehicle definition, List<JSONPart.JSONPartGroundDevice> installedGroundDevices,
     boolean activelyTowed, boolean optedOut) {
  if (optedOut || activelyTowed) return false;
  if (definition == null || definition.motorized == null) return false;
  // Fixed-wing, blimp, and rotorcraft are all eligible here - PhysicsBridge.Body picks the
  // matching force model (FlightForces vs RotorForces) once it has the live installed-propeller
  // list; this pure category decision does not need to distinguish them.
  if (isFlightVehicle(definition)) return true;
  return installedGroundDevices.stream().anyMatch(Classification::isDrivableGroundDevice);
 }
}
