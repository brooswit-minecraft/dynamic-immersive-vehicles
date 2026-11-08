package io.github.brooswitminecraft.div;
import minecrafttransportsimulator.jsondefs.JSONPart;
import minecrafttransportsimulator.jsondefs.JSONVehicle;
import java.util.List;
/**
 * Pure category decision: does this vehicle definition, in this towing/opt-out state, get a
 * Sable body at all? Operates only on IV's plain JSON definition types, so it needs no live
 * entity, world, or Sable runtime and can run as an ordinary unit test.
 *
 * <p>This bridge currently models one category: a ground vehicle with at least one wheel or
 * track contact device, of any count. Aircraft and blimps (no lift/drag/control-surface model
 * here), a vehicle with no wheel/track device at all (e.g. a pontoon-hulled boat or a skid-only
 * helicopter - buoyancy and rotor aerodynamics are a different, unmodeled physics domain), a
 * vehicle actively hitched behind another, and an explicit per-car opt-out are all excluded on
 * purpose, not missed by accident. See README.md for the follow-up tasks tracking each gap.
 */
public final class Classification {
 private Classification() {}
 public static boolean isDrivableGroundDevice(JSONPart.JSONPartGroundDevice ground) {
  return ground != null && (ground.isWheel || ground.isTread);
 }
 public static boolean eligible(JSONVehicle definition, List<JSONPart.JSONPartGroundDevice> installedGroundDevices, boolean activelyTowed, boolean optedOut) {
  if (optedOut || activelyTowed) return false;
  if (definition == null || definition.motorized == null) return false;
  if (definition.motorized.isAircraft || definition.motorized.isBlimp) return false;
  return installedGroundDevices.stream().anyMatch(Classification::isDrivableGroundDevice);
 }
}
