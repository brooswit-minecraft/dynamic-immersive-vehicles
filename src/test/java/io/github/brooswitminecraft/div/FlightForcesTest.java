package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightForcesTest {
 @Test void dynamicPressureScalesWithVelocitySquared() {
  assertEquals(0.5*1.225*100, FlightForces.dynamicPressure(1.225,10), 1e-9);
 }

 @Test void zeroAngleOfAttackHasNoLift() {
  assertEquals(0, FlightForces.liftCoefficient(0, FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE));
 }

 @Test void liftCoefficientGrowsLinearlyBeforeStall() {
  double shallow = FlightForces.liftCoefficient(Math.toRadians(5), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE);
  double steeper = FlightForces.liftCoefficient(Math.toRadians(10), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE);
  assertTrue(steeper > shallow);
  assertEquals(steeper, 2*shallow, 1e-9, "linear region: doubling the angle doubles the coefficient");
 }

 @Test void liftCoefficientIsClampedBeyondStall() {
  double atStall = FlightForces.liftCoefficient(Math.toRadians(15), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE);
  double wayPastStall = FlightForces.liftCoefficient(Math.toRadians(80), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE);
  assertEquals(atStall, wayPastStall, 1e-9, "a hard stall cutoff, not a growing coefficient past the stall angle");
 }

 @Test void liftCoefficientIsAntisymmetric() {
  assertEquals(-FlightForces.liftCoefficient(Math.toRadians(7), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE),
    FlightForces.liftCoefficient(Math.toRadians(-7), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE), 1e-9);
 }

 // MINECRAFT-521: stallAngleDegrees/liftSlope are now caller-supplied (Overrides.Tuning fields),
 // not hardcoded here - a configured vehicle must actually see a different stall cap/slope.

 @Test void configuredStallAngleMovesWhereTheHardCutoffKicksIn() {
  double narrowStall = FlightForces.liftCoefficient(Math.toRadians(10), 5, FlightForces.DEFAULT_LIFT_SLOPE);
  double atFivedegrees = FlightForces.liftCoefficient(Math.toRadians(5), 5, FlightForces.DEFAULT_LIFT_SLOPE);
  assertEquals(atFivedegrees, narrowStall, 1e-9, "a 5deg stall cap clamps a 10deg angle of attack to its 5deg limit");
 }

 @Test void configuredLiftSlopeScalesTheLinearRegion() {
  double doubledSlope = FlightForces.liftCoefficient(Math.toRadians(5), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, 2*FlightForces.DEFAULT_LIFT_SLOPE);
  double defaultSlope = FlightForces.liftCoefficient(Math.toRadians(5), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE);
  assertEquals(2*defaultSlope, doubledSlope, 1e-9, "doubling liftSlope must double the resulting coefficient at the same angle");
 }

 @Test void boundedExtremeStallAngleAndLiftSlopeStayFiniteAndClamped() {
  // Finite/bounded-extreme coverage (ticket's own ask): the max validated stallAngleDegrees (45)
  // and liftSlope (10) must still produce a finite, correctly-clamped result, never NaN/infinite.
  double atCap = FlightForces.liftCoefficient(Math.toRadians(45), 45, 10);
  double pastCap = FlightForces.liftCoefficient(Math.toRadians(90), 45, 10);
  assertTrue(Double.isFinite(atCap) && Double.isFinite(pastCap));
  assertEquals(atCap, pastCap, 1e-9, "still a hard cutoff at the extreme end of the validated range");
 }

 @Test void noWingMeansNoInducedDrag() {
  // Zero/negative aspectRatio (a blimp hull, or a degenerate zero-wing-area config) skips the
  // induced term entirely AND returns the floored native value UNSCALED by
  // parasiticDragMultiplier - oswaldEfficiency/parasiticDragMultiplier are never consulted in
  // this branch, so out-of-normal-range values here (which validate() rejects for a real Tuning
  // but this plain-math method does not itself enforce) must not matter at all.
  assertEquals(0.03, FlightForces.dragCoefficient(1.2,0.03,0,1.0,FlightForces.DEFAULT_OSWALD_EFFICIENCY), 1e-9);
  assertEquals(0.03, FlightForces.dragCoefficient(1.2,0.03,-5,1.0,0), 1e-9, "a negative aspectRatio must also skip the induced term, not divide by a negative number");
 }

 @Test void higherLiftIncreasesInducedDragWithAWing() {
  double low = FlightForces.dragCoefficient(0.2,0.03,8,1.0,FlightForces.DEFAULT_OSWALD_EFFICIENCY);
  double high = FlightForces.dragCoefficient(1.0,0.03,8,1.0,FlightForces.DEFAULT_OSWALD_EFFICIENCY);
  assertTrue(high > low);
 }

 // MINECRAFT-536: parasiticDragMultiplier/oswaldEfficiency are now caller-supplied (Overrides.
 // Tuning fields) rather than a hardcoded 0.8 inside dragCoefficient itself; the existing
 // Math.max(0.02,...) native-floor is also now applied inside this method (review correction:
 // previously applied by the caller, which let a configured multiplier leak into the no-wing
 // branch's result - see the regression below).

 @Test void nativeDragCoefficientIsFlooredAtPointZeroTwo() {
  assertEquals(0.02, FlightForces.dragCoefficient(0,0.0,0,1.0,FlightForces.DEFAULT_OSWALD_EFFICIENCY), 1e-9, "a native dragCoefficient below the floor must be raised to 0.02");
  assertEquals(0.02, FlightForces.dragCoefficient(0,-1.0,0,1.0,FlightForces.DEFAULT_OSWALD_EFFICIENCY), 1e-9, "even a negative native dragCoefficient must be floored, not propagated");
  assertEquals(0.05, FlightForces.dragCoefficient(0,0.05,0,1.0,FlightForces.DEFAULT_OSWALD_EFFICIENCY), 1e-9, "a native dragCoefficient above the floor passes through unchanged");
 }

 @Test void configuredParasiticDragMultiplierDoesNotAffectNoWingVehicles() {
  // Review finding (MINECRAFT-536, PR #31 comment 6900635611): PhysicsBridge.stepFlight used to
  // multiply the floored native parasitic coefficient by parasiticDragMultiplier BEFORE calling
  // this method, so a configured multiplier changed a blimp/no-wing vehicle's parasitic drag too
  // - violating this ticket's fixed-wing-only scope. The multiplier is now applied inside this
  // method itself, strictly behind the aspectRatio>0 gate, so a no-wing result must be identical
  // regardless of how extreme the configured multiplier is.
  double unscaled = FlightForces.dragCoefficient(1.2,0.03,0,1.0,FlightForces.DEFAULT_OSWALD_EFFICIENCY);
  double withBigMultiplier = FlightForces.dragCoefficient(1.2,0.03,0,5.0,FlightForces.DEFAULT_OSWALD_EFFICIENCY);
  double withSmallMultiplier = FlightForces.dragCoefficient(1.2,0.03,-3,0.1,FlightForces.DEFAULT_OSWALD_EFFICIENCY);
  assertEquals(unscaled, withBigMultiplier, 1e-9, "a blimp/no-wing vehicle's parasitic drag must be completely unaffected by a configured parasiticDragMultiplier");
  assertEquals(unscaled, withSmallMultiplier, 1e-9, "same requirement for a negative aspectRatio and a below-1.0 multiplier");
 }

 @Test void configuredParasiticDragMultiplierScalesTheFlooredParasiticTermOnAWing() {
  // The positive counterpart to the no-wing regression above - a fixed wing (aspectRatio>0)
  // DOES get the multiplier applied, to the FLOORED native value.
  double aspectRatio = 8, nativeDrag = 0.03, liftCoeff = 0; // zero lift isolates the parasitic term alone
  double defaultMultiplier = FlightForces.dragCoefficient(liftCoeff,nativeDrag,aspectRatio,1.0,FlightForces.DEFAULT_OSWALD_EFFICIENCY);
  double doubledMultiplier = FlightForces.dragCoefficient(liftCoeff,nativeDrag,aspectRatio,2.0,FlightForces.DEFAULT_OSWALD_EFFICIENCY);
  assertEquals(2*defaultMultiplier, doubledMultiplier, 1e-9, "doubling parasiticDragMultiplier must double the parasitic term on an actual wing");
 }

 @Test void configuredOswaldEfficiencyScalesTheInducedTermInversely() {
  // induced = lift^2/(pi*aspectRatio*e) - halving e must double the induced contribution, same
  // "inverse scaling" FlightForces.dragCoefficient's own doc describes.
  double nativeDrag = 0.03, lift = 1.0, aspectRatio = 8;
  double defaultInduced = FlightForces.dragCoefficient(lift,nativeDrag,aspectRatio,1.0,FlightForces.DEFAULT_OSWALD_EFFICIENCY) - nativeDrag;
  double halvedEfficiencyInduced = FlightForces.dragCoefficient(lift,nativeDrag,aspectRatio,1.0,FlightForces.DEFAULT_OSWALD_EFFICIENCY/2) - nativeDrag;
  assertEquals(2*defaultInduced, halvedEfficiencyInduced, 1e-9, "halving oswaldEfficiency must double the induced-drag contribution");
 }

 @Test void boundedExtremeOswaldEfficiencyStaysFiniteAndNeverNegative() {
  // Finite/bounded-extreme coverage (ticket's own ask): the validated range's extremes (a tiny
  // strictly-positive efficiency, and the real physical ceiling 1.0) must still produce a
  // finite, non-negative drag coefficient, never NaN/infinite/negative.
  double tinyEfficiency = FlightForces.dragCoefficient(1.0,0.03,8,1.0,0.01);
  double ceilingEfficiency = FlightForces.dragCoefficient(1.0,0.03,8,1.0,1.0);
  assertTrue(Double.isFinite(tinyEfficiency) && tinyEfficiency >= 0.03);
  assertTrue(Double.isFinite(ceilingEfficiency) && ceilingEfficiency >= 0.03);
  assertTrue(tinyEfficiency > ceilingEfficiency, "a smaller oswaldEfficiency produces strictly more induced drag at the same lift/aspect ratio");
 }

 @Test void dragCoefficientNeverGoesBelowTheScaledParasiticFloorRegardlessOfLiftOrEfficiency() {
  // Ticket's own "no negative drag/energy addition" acceptance requirement: the induced term is
  // always lift^2 (never negative) divided by a strictly-positive pi*aspectRatio*efficiency, so
  // the total can never fall below the (floored, multiplier-scaled) parasitic term it is added
  // to, across the entire validated oswaldEfficiency/parasiticDragMultiplier range and an
  // arbitrary lift coefficient (including zero and a sign-flipped value, since lift is squared).
  double nativeDrag = 0.04;
  for (double lift : new double[]{0, 0.5, -0.5, 2.0, -2.0}) {
   for (double efficiency : new double[]{0.01, 0.5, 0.8, 1.0}) {
    for (double multiplier : new double[]{0.01, 0.5, 1.0, 5.0}) {
     double scaledParasitic = Math.max(0.02, nativeDrag) * multiplier;
     double result = FlightForces.dragCoefficient(lift,nativeDrag,8,multiplier,efficiency);
     assertTrue(Double.isFinite(result) && result >= scaledParasitic,
       "dragCoefficient(" + lift + "," + nativeDrag + ",8," + multiplier + "," + efficiency + ") must never drop below the scaled parasitic floor");
    }
   }
  }
 }

 @Test void dragForceScalesQuadraticallyWithAirspeedAtAFixedCoefficient() {
  // Ticket's own "quadratic speed drag" acceptance requirement: aeroForce composed with
  // dynamicPressure (q = 1/2*rho*v^2) at a fixed, speed-independent drag coefficient must scale
  // as v^2, not linearly - doubling airspeed must quadruple the resulting drag force.
  double coeff = FlightForces.dragCoefficient(0.3,0.03,8,1.0,FlightForces.DEFAULT_OSWALD_EFFICIENCY);
  double area = 3.0, airDensity = 1.225;
  double dragAt10 = FlightForces.aeroForce(coeff, area, FlightForces.dynamicPressure(airDensity,10));
  double dragAt20 = FlightForces.aeroForce(coeff, area, FlightForces.dynamicPressure(airDensity,20));
  assertEquals(4*dragAt10, dragAt20, 1e-9, "doubling airspeed must quadruple drag force (quadratic in speed)");
 }

 @Test void boundedProductionAircraftDragStaysFiniteAndSane() {
  // Ticket's own "bounded production aircraft probe" acceptance requirement: a representative
  // real-aircraft wing (10m span / 18m^2 area, matching FlightRotorRuntimeFixtureProbe's own
  // FW_WING_SPAN/FW_WING_AREA fixture constants - aspect ratio span^2/area ~= 5.56) at a
  // moderate, pre-stall angle of attack, with every drag-tuning field at its documented default,
  // must produce a finite, strictly-positive, boundedly-small drag coefficient - not an absurd
  // or degenerate number from a realistic production configuration.
  double wingSpan = 10.0, wingArea = 18.0;
  double aspectRatio = (wingSpan*wingSpan)/wingArea;
  double liftCoeff = FlightForces.liftCoefficient(Math.toRadians(5), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE);
  double nativeDrag = 0.04; // FlightRotorRuntimeFixtureProbe's own FW_DRAG_COEFF
  double dragCoeff = FlightForces.dragCoefficient(liftCoeff, nativeDrag, aspectRatio, 1.0, FlightForces.DEFAULT_OSWALD_EFFICIENCY);
  assertTrue(Double.isFinite(dragCoeff) && dragCoeff > 0, "a production-representative fixed-wing drag coefficient must be finite and positive");
  assertTrue(dragCoeff < 1.0, "a moderate pre-stall angle of attack on a realistic wing must not produce an absurdly large drag coefficient");
 }

 @Test void aeroForceScalesWithCoefficientAreaAndPressure() {
  assertEquals(1.2*20*50, FlightForces.aeroForce(1.2,20,50), 1e-9);
 }

 @Test void controlSurfaceForceIsZeroAtZeroDeflection() {
  assertEquals(0, FlightForces.controlSurfaceForce(0,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE));
 }

 @Test void controlSurfaceForceFlipsSignWithDeflection() {
  assertEquals(-FlightForces.controlSurfaceForce(0.1,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE),
    FlightForces.controlSurfaceForce(-0.1,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE), 1e-9);
 }

 // MINECRAFT-521: controlSurfaceSlope is now independently tunable from the wing's own liftSlope.

 @Test void configuredControlSurfaceSlopeScalesAuthorityIndependentlyOfLiftSlope() {
  double doubledAuthority = FlightForces.controlSurfaceForce(0.1,5,50,2*FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE);
  double defaultAuthority = FlightForces.controlSurfaceForce(0.1,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE);
  assertEquals(2*defaultAuthority, doubledAuthority, 1e-9, "doubling controlSurfaceSlope must double control-surface force");
 }

 @Test void boundedExtremeControlSurfaceSlopeStaysFinite() {
  double atCap = FlightForces.controlSurfaceForce(0.1,5,50,10);
  assertTrue(Double.isFinite(atCap) && atCap > 0);
 }

 // MINECRAFT-541: independent per-axis control authority - the 5-arg overload with an explicit
 // operator-configured authorityScale, same "explicit authority-multiplier overload" shape as
 // RotorForces.cyclicTorque/yawTorque's own authorityScale overload.

 @Test void controlSurfaceForceWithUnitScaleMatchesTheUnscaledOverload() {
  assertEquals(
    FlightForces.controlSurfaceForce(0.1,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE),
    FlightForces.controlSurfaceForce(0.1,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE,1.0), 1e-9);
 }

 @Test void controlSurfaceForceScalesLinearlyWithAuthorityScale() {
  double base = FlightForces.controlSurfaceForce(0.1,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE,1.0);
  assertEquals(2*base, FlightForces.controlSurfaceForce(0.1,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE,2.0), 1e-6);
  assertEquals(0.5*base, FlightForces.controlSurfaceForce(0.1,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE,0.5), 1e-6);
 }

 @Test void controlSurfaceForceAuthorityScaleIsIndependentOfControlSurfaceSlope() {
  // Ticket acceptance: "changing one axis does not scale the others" - the authority scale and
  // the shared controlSurfaceSlope are independent multiplicative factors, so doubling one must
  // not interact with (double, cancel, or otherwise depend on) the other's own value.
  double doubledSlopeOnly = FlightForces.controlSurfaceForce(0.1,5,50,2*FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE,1.0);
  double doubledScaleOnly = FlightForces.controlSurfaceForce(0.1,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE,2.0);
  double base = FlightForces.controlSurfaceForce(0.1,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE,1.0);
  assertEquals(2*base, doubledSlopeOnly, 1e-6);
  assertEquals(2*base, doubledScaleOnly, 1e-6);
  double both = FlightForces.controlSurfaceForce(0.1,5,50,2*FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE,2.0);
  assertEquals(4*base, both, 1e-6, "the two multipliers must compose, neither overriding the other");
 }

 @Test void controlSurfaceForceAuthorityScaleAtExtremeStaysFinite() {
  double atCap = FlightForces.controlSurfaceForce(0.1,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE,5.0);
  assertTrue(Double.isFinite(atCap) && atCap > 0);
 }

 // Review correction (comment 54143): the ticket's own "test zero/unit/extreme valid values"
 // acceptance means an authorityScale of exactly 0.0 itself, not merely zero deflection at a
 // nonzero scale - 0.0 is explicitly valid (Overrides.validate's bound is now [0,5], not (0,5]),
 // and must actually zero the resulting force even with a real, nonzero deflection.

 @Test void controlSurfaceForceIsZeroAtZeroAuthorityScaleEvenWithNonzeroDeflection() {
  assertEquals(0, FlightForces.controlSurfaceForce(0.2,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE,0.0), 1e-9,
    "an authorityScale of exactly 0.0 must zero out this axis's force even with a real deflection, the documented way to fully disable just one axis");
 }

 @Test void controlSurfaceForceIsZeroAtZeroDeflectionRegardlessOfAuthorityScale() {
  assertEquals(0, FlightForces.controlSurfaceForce(0,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE,3.0));
 }

 @Test void thrustIsZeroAtZeroThrottle() {
  assertEquals(0, FlightForces.thrust(0,10000));
 }

 @Test void thrustScalesLinearlyWithThrottle() {
  assertEquals(5000, FlightForces.thrust(0.5,10000), 1e-9);
 }

 @Test void ballastVolumeUnconfiguredMeansNoBuoyancyAtAll() {
  // Review finding (round 2): gravity suppression in pinned IV source is keyed to whether
  // ballastVolume is configured/active (ballastVolumeVar > 0), not to gravityFactor - whose own
  // runtime default actually resolves to full gravity (1) when a pack leaves it unset. Seam case
  // (a) from review: an isBlimp vehicle with ballastVolume=0 must get ordinary gravity, i.e. no
  // buoyant force from this method at all, regardless of ballastControl.
  assertEquals(0, FlightForces.blimpBuoyancy(1000,9.81,0,0));
  assertEquals(0, FlightForces.blimpBuoyancy(1000,9.81,0,1));
 }

 @Test void ballastVolumeActiveGivesFullNeutralBuoyancyRegardlessOfGravityFactor() {
  // Seam case (b) from review: ballastVolume>0 (ballast active) must net full weight back as
  // lift (centered ballast control), independent of any gravityFactor value - this method takes
  // no gravityFactor parameter at all, so there is nothing for a pack's explicit gravityFactor=1
  // (or any other value) to wrongly suppress; IV's own gravity suppression doesn't key off it
  // either, per the pinned source the review cited.
  double mass = 1000, gravity = 9.81;
  assertEquals(mass*gravity, FlightForces.blimpBuoyancy(mass,gravity,1,0), 1e-9);
  // IV's documented ballastVolume magnitude (emptyMass/1000) is just "nonzero" to this method -
  // the literal number must not matter, only activation.
  assertEquals(mass*gravity, FlightForces.blimpBuoyancy(mass,gravity,1.0,0), 1e-9);
 }

 @Test void representativeBlimpNetsNearZeroVerticalForceAtNeutralBallast() {
  // The integration-level seam the first review round asked for: a representative mass/ballast
  // combination (1,000 kg blimp, active ballastVolume, centered ballast control) must leave
  // buoyancy exactly matching Sable's own gravity pull on that mass - not off by three orders of
  // magnitude the way the original rho*V*g-on-ballastVolume calculation was.
  double mass = 1000, gravity = 9.81;
  double buoyant = FlightForces.blimpBuoyancy(mass,gravity,1,0);
  double sableGravityPull = mass*gravity;
  assertEquals(sableGravityPull, buoyant, 1e-9, "centered ballast control: exactly neutral");
 }

 @Test void blimpBuoyancyIncludesBallastTrim() {
  double mass = 1000, gravity = 9.81;
  assertEquals(mass*gravity + mass*gravity*0.1, FlightForces.blimpBuoyancy(mass,gravity,1,1), 1e-9);
 }

 @Test void ballastTrimIsZeroAtCenteredControl() {
  assertEquals(0, FlightForces.ballastTrim(1000,9.81,0));
 }

 @Test void ballastTrimIsASmallFractionOfWeightAtFullControl() {
  double mass = 1000, gravity = 9.81;
  double fullRelease = FlightForces.ballastTrim(mass,gravity,1);
  assertEquals(mass*gravity*0.1, fullRelease, 1e-9);
  assertTrue(fullRelease < mass*gravity, "trim must stay a fraction of weight, not overwhelm it");
 }

 @Test void ballastTrimFlipsSignWithControlDirection() {
  assertEquals(-FlightForces.ballastTrim(1000,9.81,0.6), FlightForces.ballastTrim(1000,9.81,-0.6), 1e-9);
 }

 @Test void ballastTrimClampsBeyondFullControl() {
  assertEquals(FlightForces.ballastTrim(1000,9.81,1), FlightForces.ballastTrim(1000,9.81,5), 1e-9);
 }
}
