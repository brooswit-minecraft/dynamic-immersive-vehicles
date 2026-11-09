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
 * track contact device, of any count ({@link #eligible}), and a float-only hull with at least
 * one canFloat ground device and no wheel or track at all ({@link #floatEligible}) - a boat or
 * other pontoon-hulled vehicle, handled by its own buoyancy force model rather than the ground
 * solver. Aircraft and blimps (no lift/drag/control-surface model here), a skid-only vehicle
 * that neither drives nor floats (e.g. a grounded skid helicopter), a vehicle actively hitched
 * behind another, and an explicit per-car opt-out are all excluded on purpose, not missed by
 * accident. See README.md for the follow-up tasks tracking each remaining gap.
 */
public final class Classification {
 private Classification() {}
 public static boolean isDrivableGroundDevice(JSONPart.JSONPartGroundDevice ground) {
  return ground != null && (ground.isWheel || ground.isTread);
 }
 private static boolean baseEligible(JSONVehicle definition, boolean activelyTowed, boolean optedOut) {
  if (optedOut || activelyTowed) return false;
  if (definition == null || definition.motorized == null) return false;
  return !definition.motorized.isAircraft && !definition.motorized.isBlimp;
 }
 public static boolean eligible(JSONVehicle definition, List<JSONPart.JSONPartGroundDevice> installedGroundDevices, boolean activelyTowed, boolean optedOut) {
  if (!baseEligible(definition, activelyTowed, optedOut)) return false;
  return installedGroundDevices.stream().anyMatch(Classification::isDrivableGroundDevice);
 }
 /**
  * A float-only hull: at least one installed ground device marked canFloat, and none drivable
  * by the ground solver (a vehicle with both, e.g. an amphibious car, stays on the ground
  * solver via {@link #eligible} instead - this method is the exclusive fallback for it).
  */
 public static boolean floatEligible(JSONVehicle definition, List<JSONPart.JSONPartGroundDevice> installedGroundDevices, boolean activelyTowed, boolean optedOut) {
  if (!baseEligible(definition, activelyTowed, optedOut)) return false;
  if (installedGroundDevices.stream().anyMatch(Classification::isDrivableGroundDevice)) return false;
  return installedGroundDevices.stream().anyMatch(g -> g != null && g.canFloat);
 }
}
