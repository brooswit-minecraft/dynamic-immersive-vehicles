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
 * track contact device (of any count, {@link #eligible}), a fixed-wing aircraft or blimp (flight
 * aerodynamics, see {@link FlightForces}) with no installed rotor (also {@link #eligible}), and a
 * float-only hull with at least one canFloat ground device and no wheel or track at all
 * ({@link #floatEligible}) - a boat or other pontoon-hulled vehicle, handled by its own buoyancy
 * force model rather than the ground solver. A vehicle with both a drivable ground device and a
 * canFloat device (e.g. an amphibious car) is classified eligible for the ground domain, which
 * then also applies buoyancy/water-drag/propeller-thrust forces to its float devices alongside
 * its wheel forces in the same tick (see PhysicsBridge's amphibious handling) - combined domain
 * forces, not a silent water-behavior exclusion for a vehicle that happens to have wheels. A
 * rotorcraft (helicopter thrust-vectoring is a different, unmodeled physics domain IV's own
 * native solver treats separately), a skid-only vehicle that neither drives nor floats (e.g. a
 * grounded skid helicopter), a vehicle actively hitched behind another, and an explicit per-car
 * opt-out are all excluded on purpose, not missed by accident. See README.md for the follow-up
 * tasks tracking each remaining gap.
 */
public final class Classification {
 private Classification() {}
 public static boolean isDrivableGroundDevice(JSONPart.JSONPartGroundDevice ground) {
  return ground != null && (ground.isWheel || ground.isTread);
 }
 /** A ground device that displaces water (pontoon/float), independent of whether it also drives. */
 public static boolean isFloatDevice(JSONPart.JSONPartGroundDevice ground) {
  return ground != null && ground.canFloat;
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
  if (optedOut || activelyTowed) return false;
  if (definition == null || definition.motorized == null) return false;
  if (isFlightVehicle(definition)) {
   // A helicopter (any installed rotor propeller) needs rotor lift/vectoring this bridge does
   // not model; leave it on IV's native physics rather than giving it zero wing lift and
   // forward-only thrust.
   return installedPropellers.stream().noneMatch(Classification::isRotor);
  }
  return installedGroundDevices.stream().anyMatch(Classification::isDrivableGroundDevice);
 }
 /**
  * A float-only hull: at least one installed ground device marked canFloat, and none drivable
  * by the ground solver. A vehicle with both (e.g. an amphibious car) stays on the ground domain
  * via {@link #eligible} instead - {@link #floatEligible} is the exclusive fallback for a hull
  * with nothing the ground solver can drive, not a check for "has a float device" in general.
  * An installed rotor propeller excludes it the same way it excludes a flight vehicle in
  * {@link #eligible} - IV's rotor-lift/thrust-vectoring solver is a different, unmodeled
  * physics domain regardless of which other domain the rest of the chassis would otherwise
  * qualify for.
  */
 public static boolean floatEligible(JSONVehicle definition, List<JSONPart.JSONPartGroundDevice> installedGroundDevices,
     List<JSONPart.JSONPartPropeller> installedPropellers, boolean activelyTowed, boolean optedOut) {
  if (optedOut || activelyTowed) return false;
  if (definition == null || definition.motorized == null) return false;
  if (isFlightVehicle(definition)) return false;
  if (installedPropellers.stream().anyMatch(Classification::isRotor)) return false;
  if (installedGroundDevices.stream().anyMatch(Classification::isDrivableGroundDevice)) return false;
  return installedGroundDevices.stream().anyMatch(Classification::isFloatDevice);
 }
}
