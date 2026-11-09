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
 * track contact device (of any count), and an aircraft or blimp (flight aerodynamics, see
 * {@link FlightForces}). A vehicle with no wheel/track device and no aircraft/blimp flag (e.g. a
 * pontoon-hulled boat or a skid-only helicopter - buoyancy and rotor aerodynamics are a different,
 * unmodeled physics domain), a vehicle actively hitched behind another, and an explicit per-car
 * opt-out are all excluded on purpose, not missed by accident. See README.md for the follow-up
 * tasks tracking each gap.
 */
public final class Classification {
 private Classification() {}
 public static boolean isDrivableGroundDevice(JSONPart.JSONPartGroundDevice ground) {
  return ground != null && (ground.isWheel || ground.isTread);
 }
 /** Aircraft and blimps use {@link FlightForces} instead of the raycast ground solver, and need
  * no wheel/track device to be eligible. */
 public static boolean isFlightVehicle(JSONVehicle definition) {
  return definition != null && definition.motorized != null
    && (definition.motorized.isAircraft || definition.motorized.isBlimp);
 }
 public static boolean eligible(JSONVehicle definition, List<JSONPart.JSONPartGroundDevice> installedGroundDevices, boolean activelyTowed, boolean optedOut) {
  if (optedOut || activelyTowed) return false;
  if (definition == null || definition.motorized == null) return false;
  if (isFlightVehicle(definition)) return true;
  return installedGroundDevices.stream().anyMatch(Classification::isDrivableGroundDevice);
 }
}
