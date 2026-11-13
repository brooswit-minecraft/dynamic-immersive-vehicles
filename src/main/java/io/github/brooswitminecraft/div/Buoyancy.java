package io.github.brooswitminecraft.div;
/**
 * SI units throughout. Pure force math for float-only (pontoon/skid-with-canFloat) hulls - a
 * different physics domain than TireForces' raycast spring/damper ground solver, so it needs
 * its own model: submersion depth against a local water surface, Archimedes buoyant force,
 * quadratic water drag, and propeller thrust that only exists while the propeller itself is
 * underwater. Operates on plain numbers, so it needs no live entity, world, or Sable runtime.
 */
public final class Buoyancy {
 private Buoyancy() {}
 public static final double WATER_DENSITY_KG_M3 = 1000.0;
 public static final double GRAVITY = 9.81;
 /** Fraction (0..1) of a vertical span [bottom,top] lying at or below the water surface. */
 public static double submergedFraction(double bottom, double top, double waterSurfaceY) {
  if (top <= bottom) return 0;
  return Math.max(0, Math.min(1, (waterSurfaceY - bottom) / (top - bottom)));
 }
 /** Upward Archimedes force (N) for a hull of the given full displacement volume (m^3). */
 public static double buoyantForce(double submergedFraction, double hullVolume) {
  return submergedFraction * hullVolume * WATER_DENSITY_KG_M3 * GRAVITY;
 }
 /** Quadratic water drag magnitude (N), opposing motion, scaled by how submerged the hull is. */
 public static double drag(double speed, double submergedFraction, double dragCoefficient) {
  return submergedFraction * dragCoefficient * speed * speed;
 }
 /** Quadratic rotational water drag torque magnitude (N*m), opposing the hull's own angular
  * velocity and scaled by how submerged it is - the rotational analogue of {@link #drag} for
  * roll/pitch/yaw. MINECRAFT-518: today a floating hull gets a righting torque only as an
  * emergent side effect of {@code drag} applied at each float device's own off-center position
  * (see {@code BuoyantForces}'s class doc); nothing damps rotation itself, so a hull knocked
  * into a spin (e.g. by a collision) has no hydrodynamic resistance arresting that spin beyond
  * those per-float linear-drag side effects. Zero {@code angularDragCoefficient} (the documented
  * fallback) reproduces that exact prior behavior - this is an additive stability knob, not a
  * replacement for the existing per-float righting torque. */
 public static double angularDrag(double angularSpeed, double submergedFraction, double angularDragCoefficient) {
  return submergedFraction * angularDragCoefficient * angularSpeed * angularSpeed;
 }
 /** Propeller thrust (N); a propeller produces no thrust at all unless it is both underwater
  * and itself active (IV's own isActiveVar - not destroyed/removed) - an inactive or damaged
  * propeller stays silent regardless of throttle or submersion. */
 public static double propellerThrust(double throttleSigned, double propellerForce, boolean propellerSubmerged, boolean propellerActive) {
  return (propellerSubmerged && propellerActive) ? throttleSigned * propellerForce : 0;
 }
 /** Splits the shared propeller-force budget evenly across only the propellers actually
  * operational this tick (driven by a running, linked engine, active, and turning) - a
  * non-operational propeller must not dilute the share a real one gets just by existing on
  * the chassis. Zero if none are operational. */
 public static double perOperationalPropellerForce(double propellerForceBudget, int operationalCount) {
  return operationalCount <= 0 ? 0 : propellerForceBudget / operationalCount;
 }
 /** The exact per-propeller selection test {@code BuoyantForces} applies before a propeller
  * counts toward {@link #perOperationalPropellerForce}'s denominator at all: linked to a
  * currently-running engine ({@code linkedToRunningEngine} - from
  * {@code PartEngineAccessor.linkedPropellers}, not reachable through any public API), not
  * destroyed/removed ({@code active} - IV's own {@code isActiveVar}), actually turning
  * ({@code currentRpm} away from zero - from {@code PartPropellerAccessor}, also not public),
  * and actually producing nonzero {@link #signedPropellerDrive} - a linked, active, spinning
  * propeller with zero computed pitch/direction (feathered, or caught mid-swing through
  * dynamic pitch) must not count toward the shared budget's denominator either: it produces no
  * thrust of its own ({@code signedPropellerDrive} is 0, so {@link #propellerThrust} is 0) but
  * would otherwise still dilute the share a propeller that IS actually thrusting gets, just by
  * spinning. All four conditions are checked independently, so no single one can substitute
  * for the others. */
 public static boolean isPropellerOperational(boolean linkedToRunningEngine, boolean active, double currentRpm, double signedDrive) {
  return linkedToRunningEngine && active && Math.abs(currentRpm) > 1e-3 && signedDrive != 0;
 }
 /** Signed drive for one operational propeller: the sign of IV's own computed thrust
  * direction ({@code PartPropeller.desiredLinearVelocity}, a public field driven by
  * {@code currentPitch} - a fixed, JSON-defined sign for a static-pitch propeller, or one
  * swung between forward and reverse by {@code vehicle.reverseThrustVar} for a dynamic-pitch
  * one), times vehicle throttle for magnitude. A propeller has no gearbox of its own:
  * transmission gear state ({@code PartEngine.currentGearVar}) selects direction for driven
  * wheels, not for a propeller linked to the same engine, so it must not be consulted here -
  * using it produced wrong-direction or falsely-zero thrust whenever gear and propeller pitch
  * disagreed (e.g. an engine in neutral still spinning a propeller, or a dynamic-pitch
  * propeller reversed via {@code reverseThrustVar} while the transmission stayed in a forward
  * gear). Zero {@code desiredLinearVelocity} (no computed direction yet) yields zero drive. */
 public static double signedPropellerDrive(double desiredLinearVelocity, double throttle) {
  return Math.signum(desiredLinearVelocity) * throttle;
 }
}
