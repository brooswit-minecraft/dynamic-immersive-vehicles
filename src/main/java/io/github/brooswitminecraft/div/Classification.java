package io.github.brooswitminecraft.div;
import minecrafttransportsimulator.jsondefs.JSONPart;
import minecrafttransportsimulator.jsondefs.JSONVehicle;
import java.util.List;
/**
 * Pure category decision: does this vehicle definition, in this towing/opt-out state, get a
 * Sable body at all? Operates only on IV's plain JSON definition types, so it needs no live
 * entity, world, or Sable runtime and can run as an ordinary unit test.
 *
 * <p>This bridge currently models four categories: a ground vehicle with at least one wheel or
 * track contact device (of any count, {@link #eligible}), a fixed-wing aircraft or blimp (flight
 * aerodynamics, see {@link FlightForces}) or a rotorcraft (any installed rotor propeller, see
 * {@link RotorForces} - checked directly against the installed part, independent of the
 * {@code isAircraft}/{@code isBlimp} flag, so a rotor-carrying vehicle with neither flag set is
 * still a rotorcraft) (also {@link #eligible}), and a float-only hull with at least one
 * canFloat ground device and no wheel or track at all ({@link #floatEligible}) - a boat or other
 * pontoon-hulled vehicle, handled by its own buoyancy force model rather than the ground/flight
 * solvers. A vehicle with a drivable ground device or a flight flag AND a canFloat device (e.g.
 * an amphibious car, or a float-equipped seaplane/rotorcraft) stays eligible for its ground or
 * flight domain, which then also applies buoyancy/water-drag/underwater-propeller forces to its
 * float devices alongside its wheel or flight forces in the same tick (see PhysicsBridge's
 * amphibious handling) - combined domain forces, not a silent water-behavior exclusion for a
 * vehicle that happens to drive or fly. A skid-only vehicle that neither drives nor floats (e.g.
 * a grounded skid helicopter with no canFloat skid), a vehicle actively hitched behind another,
 * and an explicit per-car opt-out are all excluded on purpose, not missed by accident. See
 * README.md for the follow-up tasks tracking each remaining gap.
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
 /**
  * Whether {@link BuoyantForces}'s underwater-propeller-thrust pass should apply to this
  * propeller, as opposed to {@code PhysicsBridge.Body.stepRotor}'s own collective/cyclic thrust
  * model owning it instead. This is the exact partition that keeps a floating rotorcraft's
  * thrust counted exactly once: {@code needsUnderwaterPropellerThrust(p) == !isRotor(p)} for
  * every {@code p}, so a propeller is never claimed by both force models and never dropped by
  * neither.
  */
 public static boolean needsUnderwaterPropellerThrust(JSONPart.JSONPartPropeller propeller) {
  return !isRotor(propeller);
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
     List<JSONPart.JSONPartPropeller> installedPropellers, boolean activelyTowed, boolean optedOut) {
  if (optedOut || activelyTowed) return false;
  if (definition == null || definition.motorized == null) return false;
  // An installed rotor propeller always makes this a rotorcraft, independent of the
  // isAircraft/isBlimp flag: review found a vehicle with an actual installed isRotor propeller
  // but no aircraft/blimp flag was previously either deactivated (no wheels) or stuck on the
  // ground solver (with wheels), its rotor never getting lift/control. Rotor physics must be
  // driven by the actual installed part, not gated on a definition flag it doesn't need.
  if (isRotorcraft(installedPropellers)) return true;
  // Fixed-wing and blimp are eligible here too - PhysicsBridge.Body picks FlightForces once it
  // has the live installed-propeller list; this pure category decision does not need to
  // distinguish them from each other.
  if (isFlightVehicle(definition)) return true;
  return installedGroundDevices.stream().anyMatch(Classification::isDrivableGroundDevice);
 }
 /**
  * A float-only hull: at least one installed ground device marked canFloat, and none drivable
  * by the ground solver, and not a flight vehicle. A vehicle with both a drivable device (or a
  * flight flag) and a canFloat device (e.g. an amphibious car, or a float-equipped seaplane/
  * rotorcraft) stays on the ground/flight domain via {@link #eligible} instead -
  * {@link #floatEligible} is the exclusive fallback for a hull with nothing the ground solver
  * can drive and nothing the flight/rotor model can fly, not a check for "has a float device"
  * in general. An installed rotor propeller excludes it directly (the same check {@link #eligible}
  * makes first) - IV's rotor-lift/thrust-vectoring solver is a different, unmodeled physics
  * domain regardless of which other domain the rest of the chassis would otherwise qualify for,
  * and {@link #eligible} already returns true for any installed rotor regardless of the
  * aircraft/blimp flag, so this method would never be reached for one via the {@code !ground}
  * guard in {@code PhysicsBridge.active} - this check is kept anyway as a direct, self-contained
  * guarantee rather than relying on call-site ordering.
  */
 public static boolean floatEligible(JSONVehicle definition, List<JSONPart.JSONPartGroundDevice> installedGroundDevices,
     List<JSONPart.JSONPartPropeller> installedPropellers, boolean activelyTowed, boolean optedOut) {
  if (optedOut || activelyTowed) return false;
  if (definition == null || definition.motorized == null) return false;
  if (isFlightVehicle(definition)) return false;
  if (isRotorcraft(installedPropellers)) return false;
  if (installedGroundDevices.stream().anyMatch(Classification::isDrivableGroundDevice)) return false;
  return installedGroundDevices.stream().anyMatch(Classification::isFloatDevice);
 }
}
