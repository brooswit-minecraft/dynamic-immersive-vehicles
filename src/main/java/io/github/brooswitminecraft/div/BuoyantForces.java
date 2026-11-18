package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.Pose3d;
import io.github.brooswitminecraft.div.mixin.PartEngineAccessor;
import io.github.brooswitminecraft.div.mixin.PartPropellerAccessor;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartEngine;
import minecrafttransportsimulator.entities.instances.PartGroundDevice;
import minecrafttransportsimulator.entities.instances.PartPropeller;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Buoyancy/water-drag/propeller-thrust force application, shared by {@link BuoyantBody} (a
 * float-only hull's entire domain) and {@link PhysicsBridge}'s ground/flight {@code Body} (an
 * amphibious vehicle - a drivable wheel/track, or a rotorcraft/fixed-wing/blimp, with at least
 * one canFloat device - gets both its ground/flight domain's own forces and these, applied in
 * the same tick, rather than this bridge silently dropping its water behavior just because it
 * also drives or flies; see Classification.floatEligible's javadoc). Archimedes buoyant force
 * and quadratic water drag are sampled per installed canFloat device at that device's own world
 * position against the live Minecraft water surface, which naturally produces a righting torque
 * from uneven submersion - no separate stability code needed. Installed non-rotor propellers add
 * forward/reverse thrust, steered by rudder input - MINECRAFT-544: bounded by {@code
 * tuning.marineSteerDegrees}, an independent limit from the ground {@code tuning.maxSteerDegrees}
 * that {@link Overrides} resolves to inherit maxSteerDegrees's own resolved value when left
 * unconfigured, so wheel steering and water-propeller steering can diverge without one affecting
 * the other - allocated per actual engine-to-propeller
 * linkage (not reachable through IV's public API - see {@link PartEngineAccessor}/
 * {@link PartPropellerAccessor}) rather than one vehicle-wide signal applied to every installed
 * propeller; an installed rotor propeller is excluded here deliberately - its thrust is already
 * supplied by {@code PhysicsBridge.Body.stepRotor}'s own collective/cyclic model regardless of
 * water, so counting it again here would double-apply the same propulsive force. MINECRAFT-518
 * additionally applies an optional rotational (roll/pitch/yaw) water-drag damping, averaged
 * across every float's own submerged fraction - a dedicated damping term for rotation itself, on
 * top of (never a replacement for) the per-float righting torque above; its tuning.angularWaterDrag
 * coefficient defaults to 0 (no change from prior behavior) unless an operator configures it.
 * Applied as a direct, implicit (unconditionally stable) velocity retention via {@link
 * Buoyancy#angularDragRetention} - never as an inertia-converted impulse - per review finding
 * 53679 (see that method's own doc for why an impulse-based approach was unsafe).
 * MINECRAFT-535 further extends each float's own (translational, point-velocity) water drag
 * from a single isotropic coefficient to bounded body-local longitudinal/lateral/vertical
 * multipliers on top of it - see {@link Buoyancy#directionalDragForce}'s own doc; this is the
 * linear/translational analogue sitting alongside (never replacing) the angular drag above,
 * which was already direction-independent (roll/pitch/yaw are not given separate coefficients
 * here, only this ticket's own longitudinal/lateral/vertical point-velocity axes).
 */
final class BuoyantForces {
 private BuoyantForces() {}

 /**
  * MINECRAFT-545/547 (John's comments 55116/55129/55133): BOUNDED BY DEFAULT as of this change -
  * the opt-in candidate flag ({@code div.sequentialDragBound}) is retired; this is now an
  * explicit opt-OUT kill-switch for emergency rollback to the OLD unconditional integration only
  * ({@code -Ddiv.unboundedDragOverride=true}), never set in any shipped/default configuration.
  *
  * <p><b>Why this is safe to make the default (the derivation, not merely "the candidate passed
  * a native run"):</b> {@code Buoyancy.directionalDragForce} is a quadratic drag law,
  * {@code F = -fraction*coefficient*multiplier*speed*v}, applied via a single explicit (forward-
  * Euler) impulse per tick - {@code impulse = F*dt}, added directly to velocity via
  * {@code v' = v + impulse*k} where {@code k} is the effective inverse mass along the impulse
  * direction at the application point ({@code MassData.getInverseNormalMass(position,
  * direction)}, already accounting for the lever-arm/rotational coupling). Such a step is
  * linearly stable (does not overshoot the point-velocity component it opposes past zero, i.e.
  * does not REVERSE it) exactly when {@code |impulse|*k <= |s|} where {@code s} is that
  * component's own CURRENT value (this is the textbook explicit-Euler bound for
  * {@code dv/dt=-k'*v}, already documented adjacent to {@code directionalDragForce} itself).
  * {@code Buoyancy.maxNonReversingImpulseMagnitude(s,k) = |s|/k} is exactly this threshold -
  * ALREADY DERIVED (and independently re-verified here, not merely copied) directly from
  * mass (via {@code k}), the CURRENT predicted velocity ({@code s}), and the force actually
  * about to be applied this step - not a heuristic safety margin. Below that threshold, drag
  * never overshoots past zero regardless of speed, so the fix is EXACTLY the dissipative bound
  * the physics itself requires, no more aggressive and no less.
  *
  * <p><b>Qualified claim about ordinary (low-speed) behavior (CORRECTED per John's comment
  * 55138, item 2 - "'true no-op/unaffected' claim is false for whole integration"):</b> the
  * BOUND/CAP itself ({@link ProposedBoundedDragIntegration#boundedDragForce} returning something
  * OTHER than the raw force it was given) only ever engages when that raw force's own impulse
  * would already exceed {@code |s|/k} - confirmed directly
  * ({@code ProposedBoundedDragIntegrationTest.theRealDragFormulaAtOrdinaryLowSpeedIsUnaffectedByBounding})
  * that at ordinary low speed this specific cap check is a genuine no-op. That is NOT the same
  * claim as "the whole integration is unaffected at low speed", and the prior version of this
  * doc wrongly conflated the two: this method now reads FRESH per-float velocity from the
  * handle for EACH float, sequentially (every float's own impulse is applied to the real native
  * handle immediately - confirmed native-synchronous, see {@link SequentialImpulseEnergyLedger}'s
  * own class doc), whereas the OLD/unconditional path computes every float's drag from ONE
  * SHARED snapshot taken once before the per-float loop runs (the caller-supplied
  * {@code velocity}/{@code angular} parameters, never re-read mid-loop). With more than one
  * float, this reordering can change the raw numeric result even when the cap never binds -
  * confirmed and QUANTIFIED, not merely asserted, by
  * {@code ProposedBoundedDragIntegrationTest.multipleOffCenterAnisotropicFloatsShowAMeasurableButSmallDifferenceFromSharedSnapshotEvenWithoutCapping}
  * (a representative two-float case at ordinary speed). What IS preserved, confirmed by that
  * same test: the underlying physical law - total energy change from drag stays non-positive,
  * no point-velocity component is ever reversed past zero - regardless of this reordering
  * effect. The claim this doc makes is therefore the NARROWER, accurate one: the CAP is a no-op
  * at low speed; the SEQUENTIAL RECOMPUTATION is a small but genuinely nonzero reordering effect
  * present regardless of speed, bounded in magnitude and never violating dissipativity.
  *
  * <p><b>Why this generalizes beyond the float-only towing scenario this ticket is about:</b>
  * the bound depends only on {@code massData}/{@code arm}/the CURRENT velocity/the force already
  * computed - the SAME quadratic drag law applies uniformly to every float on every buoyant
  * body (ground vehicles that float, boats, amphibious craft - anything routing through
  * {@code BuoyantForces.apply}), not specially to this ticket's own fixture. It never touches
  * {@code stepGround}/{@code stepFlight}/{@code stepRotor} at all (those are separate methods,
  * confirmed unchanged) - only the water-drag contribution inside THIS method's own per-float
  * loop. Buoyancy itself ({@code buoyancyOnlyForce}) is never touched, scaled, or bounded by
  * any of this - only the SEPARATE drag contribution.
  *
  * <p>{@code massData} is now always built at both real call sites ({@code BuoyantBody} already
  * did unconditionally; {@code PhysicsBridge.Body}'s own construction gate is updated alongside
  * this flag) rather than only when the old opt-in flag was set.
  */
 static final boolean UNBOUNDED_DRAG_OVERRIDE = Boolean.getBoolean("div.unboundedDragOverride");

 /** Applies this tick's forces to handle and returns whether anything was submerged/thrusting
  * (the caller's cue to wake the Sable body). */
 /**
  * {@code massData} (MINECRAFT-545/547, John's comment 54706, usage updated for 55129): the
  * body's own solid-box {@code MassData}, needed by the bounded-drag path below (now the
  * DEFAULT, not an opt-in candidate) - both real call sites (BuoyantBody, PhysicsBridge.Body)
  * build one correctly (BuoyantBody's own {@code box} is already a
  * {@code ShapeAwareBoxPhysicsObject} with a correct {@code getMassTracker()}; PhysicsBridge.Body's
  * own {@code box} is a plain {@code BoxPhysicsObject}, whose {@code getMassTracker()} would
  * silently give Sable's shape-ignorant {@code mass/6} fallback - so it builds one directly from
  * its own {@code mass}/{@code halfExtents} fields via {@link BoxInertia#solidBoxMassData}
  * instead). Only read below when {@link #UNBOUNDED_DRAG_OVERRIDE} is set (never in any
  * shipped/default configuration) is this parameter unused.
  */
 static boolean apply(ServerLevel level, EntityVehicleF_Physics v, Overrides.Tuning tuning, Vector3d center,
     double hullVolume, Pose3d pose, Quaterniond q, Quaterniond inverse, Vector3d velocity, Vector3d angular,
     RigidBodyHandle handle, MassData massData) {
  var floats = floats(v);
  double volumePerFloat = floats.isEmpty() ? 0 : hullVolume / floats.size();
  boolean awake = false;
  double totalFraction = 0;
  // MINECRAFT-545/547 (John's comments 54469/54483/54494): invocation/enumeration marker, gated
  // by the existing opt-in GroundContactDiagnostics flag AND the shared DiagnosticContext (body
  // ID + explicit gameTime window, armed by the caller BEFORE this tick's force hook runs - NOT
  // bounded merely by the global flag, per 54494's correction that the global flag alone is not
  // fixture/window gating) - not unconditional/general-production logging. Fires once per
  // apply() call regardless of float count, so a reader can distinguish "apply never invoked this
  // tick for this body" (no line at all) from "invoked with zero floats" (line present,
  // floatCount=0) from "invoked, floats present, but every one skipped" (this line present with
  // floatCount>0, no per-float non-skip line below) - a missing per-float line alone is not proof
  // of which case occurred.
  boolean contextActive = DiagnosticContext.isActiveFor(v.uniqueUUID, level.getGameTime());
  if (GroundContactDiagnostics.ENABLED && contextActive) com.mojang.logging.LogUtils.getLogger().info(
    "[DIV buoyantForcesDiagnostic] v=" + v.uniqueUUID + " gameTime=" + level.getGameTime()
      + " applyInvoked=true floatCount=" + floats.size()
      + " poseRefreshGameTime=" + PhysicsBridge.debugLastPoseUpdateGameTime(v));
  int floatIndex = -1;
  for (var flt : floats) {
   floatIndex++;
   double radius = Math.max(.1, flt.getHeight() / 2);
   Vector3d anchor = new Vector3d(flt.localOffset.x, flt.localOffset.y, flt.localOffset.z).sub(center);
   Vector3d arm = q.transform(new Vector3d(anchor));
   Vector3d point = new Vector3d(pose.position()).add(arm);
   int queryFloorX = Mth.floor(point.x), queryFloorZ = Mth.floor(point.z);
   // MINECRAFT-545/547 (John's comment 54486): ONE scanForWater call supplies both the fraction
   // computation below and the diagnostic line's own detail - no second fluid query.
   var scan = scanForWater(level, point.x, point.z, point.y + radius + 1);
   double waterSurface = scan.surfaceY();
   double fraction = Buoyancy.submergedFraction(point.y - radius, point.y + radius, waterSurface);
   // MINECRAFT-545/547 (John's comments 54469/54483/54486/54494) - diagnostic-only, gated by the
   // SAME GroundContactDiagnostics flag AND DiagnosticContext as the invocation marker above -
   // fired BEFORE the skip below so it covers every float every tick in the armed window (not
   // just the ones that go on to apply a force). Logs each pose/point/orientation component
   // individually at full double precision via String.valueOf(double) - NOT JOML's own toString,
   // which truncates to limited significant digits/scientific notation (per 54494's correction) -
   // plus explicit floor(x)/floor(z) (restored - previously dropped), this float's own index/
   // geometry, the scan's own bounds (fromYFloor through bottomY, INCLUSIVE on both ends - up to
   // maxPossibleIterations Y values, 25 for this scan's 24-block span), and its REAL result:
   // either the found block's own Y and fluid height separately (so a found-but-still-above-the-
   // float case is visible, not conflated with no-water-found), or NO_WATER_FOUND_IN_RANGE for
   // the documented NEGATIVE_INFINITY sentinel, plus the ACTUAL iterations performed (early-return
   // preserved - not the scan's maximum capacity regardless of where it matched).
   if (GroundContactDiagnostics.ENABLED && contextActive) com.mojang.logging.LogUtils.getLogger().info(
     "[DIV buoyantForcesDiagnostic] v=" + v.uniqueUUID + " gameTime=" + level.getGameTime()
       + " floatIndex=" + floatIndex + " floatLocalOffset=(" + flt.localOffset.x + "," + flt.localOffset.y + "," + flt.localOffset.z + ")"
       + " radius=" + radius + " center=" + center
       + " consumedPosePositionX=" + String.valueOf(pose.position().x()) + " consumedPosePositionY=" + String.valueOf(pose.position().y()) + " consumedPosePositionZ=" + String.valueOf(pose.position().z())
       + " consumedPoseOrientationX=" + String.valueOf(q.x) + " consumedPoseOrientationY=" + String.valueOf(q.y) + " consumedPoseOrientationZ=" + String.valueOf(q.z) + " consumedPoseOrientationW=" + String.valueOf(q.w)
       + " poseRefreshGameTime=" + PhysicsBridge.debugLastPoseUpdateGameTime(v)
       + " pointX=" + String.valueOf(point.x) + " pointY=" + String.valueOf(point.y) + " pointZ=" + String.valueOf(point.z)
       + " queryFloorX=" + queryFloorX + " queryFloorZ=" + queryFloorZ
       + " queryFromYFloor=" + scan.fromYFloor() + " scanBottomY=" + scan.bottomY() + " scanIterations=" + scan.iterations() + " scanMaxPossibleIterations=" + maxPossibleIterations(scan.fromYFloor(), scan.bottomY())
       + " scanFound=" + scan.found() + " foundBlockY=" + (scan.found() ? String.valueOf(scan.foundBlockY()) : "N/A")
       + " foundFluidHeight=" + (scan.found() ? String.valueOf(scan.fluidHeight()) : "N/A")
       + " waterSurfaceResult=" + (scan.found() ? String.valueOf(waterSurface) : "NO_WATER_FOUND_IN_RANGE")
       + " fraction=" + fraction + " skip=" + (fraction <= 0));
   flt.contactThisTick = fraction > 0;
   totalFraction += fraction;
   if (fraction <= 0) continue;
   flt.animateAsOnGround = true;
   awake = true;
   Vector3d localVelocity = new Vector3d(angular).cross(arm).add(velocity);
   double speed = localVelocity.length();
   Vector3d force = new Vector3d(0, Buoyancy.buoyantForce(fraction, volumePerFloat), 0);
   // MINECRAFT-545/547 (John's comment 54357: "capture actual buoyancy/drag inputs and applied
   // force/torque contributions... identifying unavailable native terms") - diagnostic-only
   // snapshot of the buoyancy force ALONE, before drag is added below, so the two contributions
   // can be reported separately rather than only as their combined sum. Not a behavior change -
   // read-only copy of a value already computed above.
   Vector3d buoyancyOnlyForce = new Vector3d(force);
   // MINECRAFT-535: directional water drag - this point's own velocity, transformed into the
   // hull's body-local basis (x=lateral/port-starboard, y=vertical/heave, z=longitudinal/bow-
   // stern, matching the same basis `forward` below is built in), scaled per axis by
   // Buoyancy.directionalDragForce before being transformed back to world frame to accumulate
   // into `force` alongside buoyancy. tuning.longitudinal/lateral/verticalWaterDragMultiplier
   // each default to 1.0 (Overrides.FALLBACK), which reproduces the exact prior isotropic
   // -dragMagnitude/speed*localVelocity force component-for-component - see
   // Buoyancy.directionalDragForce's own doc for why.
   // MINECRAFT-545/547 (John's comment 54706): extracted into a reusable lambda, taking an
   // arbitrary WORLD-frame point velocity rather than always `localVelocity`, SOLELY so the
   // bounded-drag branch below (now the default, per 55129) can recompute drag fresh from a
   // virtual post-buoyancy point velocity instead of reusing this (pre-buoyancy) one - the
   // UNCONDITIONAL call immediately below still passes `localVelocity`, so that raw value stays
   // byte-for-byte identical to before this file's own candidate-era refactor (it is still
   // computed and logged for comparison, only no longer what gets APPLIED by default).
   java.util.function.Function<org.joml.Vector3dc, Vector3d> dragWorldForceFrom = (pointVelocityWorld) -> {
    double spd = pointVelocityWorld.length();
    Vector3d result = new Vector3d(0, 0, 0);
    if (spd > 1e-6) {
     Vector3d bodyVel = inverse.transform(new Vector3d(pointVelocityWorld));
     double lon = Buoyancy.directionalDragForce(spd, bodyVel.z, fraction, tuning.waterDrag, tuning.longitudinalWaterDragMultiplier);
     double lat = Buoyancy.directionalDragForce(spd, bodyVel.x, fraction, tuning.waterDrag, tuning.lateralWaterDragMultiplier);
     double vert = Buoyancy.directionalDragForce(spd, bodyVel.y, fraction, tuning.waterDrag, tuning.verticalWaterDragMultiplier);
     result.add(q.transform(new Vector3d(lat, vert, lon)));
    }
    return result;
   };
   if (speed > 1e-6) force.add(dragWorldForceFrom.apply(localVelocity));

   // MINECRAFT-545/547 (John's comments 55116/55129/55133): BOUNDED BY DEFAULT now - see
   // UNBOUNDED_DRAG_OVERRIDE's own doc for the full derivation of why this is safe as the
   // default, not merely "the candidate passed one native run". `forceToApply` stays IDENTICAL
   // to the raw unconditional `force` above ONLY if the override kill-switch is explicitly set
   // (never in any shipped/default configuration). Otherwise: reads FRESH per-float velocity
   // from the handle (not this method's own once-per-tick `velocity`/`angular` parameters - the
   // confirmed staleness gap, VERIFICATION.md "Corrections per 54700"), converts to LOCAL frame
   // (matching massData's own body-local tensor frame), recomputes drag fresh from a VIRTUAL
   // post-buoyancy state (buoyancy's own impulse applied to a COPY of velocity, never the real
   // body - VERIFICATION.md "Corrections per 54706"), and bounds that recomputed drag against
   // that same virtual state - buoyancy itself is never touched/scaled, only ever drag.
   //
   // MINECRAFT-545/547 (John's comment 55138, item 3 - "null massData currently silently selects
   // old unstable force even under bounded default"): the ORIGINAL version of this condition was
   // `!UNBOUNDED_DRAG_OVERRIDE && massData != null` - if the override was NOT set (bounded
   // behavior intended) but massData was unexpectedly null for some reason, this SILENTLY fell
   // through to the old unconditional force, with no signal that the intended default behavior
   // did not actually happen. Both real call sites (BuoyantBody.step, unconditionally via
   // box.getMassTracker(); PhysicsBridge.Body.step, constructed inline whenever this method is
   // even reached - see that call site's own doc) are confirmed, by direct reading, to ALWAYS
   // supply a real massData whenever the override is not set - so a null value here, with the
   // override not set, is not a legitimate case to silently tolerate; it indicates a genuine
   // caller-contract violation (a bug at the call site, not a signal to fall back). Fails loud
   // now instead - fixed contract tested directly in BuoyantForcesTest (new) /
   // ProposedBoundedDragIntegrationTest.
   Vector3d forceToApply = force;
   if (!UNBOUNDED_DRAG_OVERRIDE) {
    if (massData == null) {
     throw new IllegalStateException("BuoyantForces.apply: massData must be non-null whenever UNBOUNDED_DRAG_OVERRIDE is false (the bounded path is the default) - both real call sites are expected to always supply one; a null value here is a caller-contract violation, not a legitimate fallback to the old unconditional force.");
    }
    Vector3d localArm = anchor; // anchor is already body-local, pre-q-transform - no extra conversion needed
    Vector3d freshLocalLinear = inverse.transform(handle.getLinearVelocity(new Vector3d()));
    Vector3d freshLocalAngular = inverse.transform(handle.getAngularVelocity(new Vector3d()));
    Vector3d localBuoyancyOnly = inverse.transform(new Vector3d(buoyancyOnlyForce));
    ProposedFreshVelocityDragCallSite.DragForceFromVelocity recomputeDragLocal = (localLinVel, localAngVel) -> {
     Vector3d localPointVelocity = new Vector3d(localAngVel).cross(localArm, new Vector3d()).add(new Vector3d(localLinVel));
     Vector3d worldPointVelocity = q.transform(new Vector3d(localPointVelocity));
     Vector3d worldDragForce = dragWorldForceFrom.apply(worldPointVelocity);
     return inverse.transform(worldDragForce);
    };
    Vector3d combinedLocal = ProposedFreshVelocityDragCallSite.virtualBuoyancyFirstCombinedForce(
      massData, localArm, localBuoyancyOnly, recomputeDragLocal, freshLocalLinear, freshLocalAngular, PhysicsBridge.DT);
    forceToApply = q.transform(combinedLocal);
    // MINECRAFT-545/547 (John's comment 54709, field names updated for 55129 - this is now the
    // DEFAULT path, not a candidate): gated with contextActive too (matching the neighboring
    // fixture/window-gated diagnostics in this method - a prior version checked only
    // GroundContactDiagnostics.ENABLED, emitting unscoped evidence). Reports THIS (now default)
    // path's own buoyancy/bounded-drag breakdown explicitly (not just the combined force),
    // since the diagnostic block below still separately reports the OLD/unconditional model's
    // own breakdown for comparison - a reviewer needs both, separately labeled.
    if (GroundContactDiagnostics.ENABLED && contextActive) {
     Vector3d defaultBuoyancyWorld = q.transform(new Vector3d(localBuoyancyOnly));
     Vector3d defaultBoundedDragWorld = new Vector3d(forceToApply).sub(defaultBuoyancyWorld);
     com.mojang.logging.LogUtils.getLogger().info(
      "[DIV buoyantForcesDiagnostic] v=" + v.uniqueUUID + " gameTime=" + level.getGameTime()
        + " sequentialDragBoundDefault=true floatLocalOffset=(" + flt.localOffset.x + "," + flt.localOffset.y + "," + flt.localOffset.z + ")"
        + " unconditionalForce=" + force
        + " defaultBuoyancyOnlyForce=" + defaultBuoyancyWorld + " defaultBoundedDragForce=" + defaultBoundedDragWorld
        + " defaultForceToApply=" + forceToApply);
    }
   }
   // MINECRAFT-545/547 (John's comment 54357) - diagnostic-only, gated behind the existing
   // GroundContactDiagnostics flag (same opt-in every other per-tick diagnostic in this codebase
   // already uses). Logs this float's own submerged fraction, world-space arm, buoyancy-only
   // force, drag-only force (= combined - buoyancy, computed here only for the log line), and the
   // actual impulse about to be applied - the SAME values `applyImpulseAtPoint` below uses, not a
   // re-derivation. Explicitly does NOT capture: the native solver's own resulting velocity change
   // from this impulse (applied synchronously into Sable's own native state, not read back here),
   // nor any OTHER force source (gravity, the joint's own impulse, propeller thrust below) -
   // those remain unavailable native/other-source terms, not claimed to be covered by this line.
   // MINECRAFT-545/547 (John's comments 54359/54365: "lacks tick/substep/event association",
   // "do not assert [substep] scheduling merely from call order; log actual known event phase
   // and unavailable substep index separately") - gameTime is this call's own Minecraft-tick
   // marker (level.getGameTime()). Re-checked the actual ordering rather than re-asserting the
   // prior (WRONG-direction) claim that this runs "before" native substeps: ServerLevelMixin
   // injects Sable's own native step() at the HEAD of ServerLevel.tick(), strictly BEFORE entity
   // ticking - and this method runs from BuoyantBody.step, called via
   // VehicleMovementMixin.div$move during entity ticking. So THIS tick's native substeps have
   // ALREADY run by the time this line executes; the impulse applied here lands in native state
   // to be consumed by the NEXT tick's native substeps, not this tick's (already-completed) ones.
   // That is the one known event-phase fact - stated on its own, separate from substepIndex,
   // which stays explicitly UNAVAILABLE (no accessor exposes which of config.substepsPerTick
   // substeps last touched/will touch this body).
   if (GroundContactDiagnostics.ENABLED) {
    // MINECRAFT-545/547 (John's comment 54709): explicitly labeled "unconditional" - this is
    // ALWAYS the OLD/unconditional model's own buoyancy/drag breakdown (computed above
    // regardless of UNBOUNDED_DRAG_OVERRIDE), kept purely for comparison - it is no longer what
    // gets applied by default (see 55129). appliedImpulse below reflects the DEFAULT bounded
    // result (forceToApply) whenever the override is not set - see the default path's own
    // separate diagnostic line above for ITS OWN buoyancy/drag breakdown.
    Vector3d unconditionalDragOnlyForce = new Vector3d(force).sub(buoyancyOnlyForce);
    com.mojang.logging.LogUtils.getLogger().info("[DIV buoyantForcesDiagnostic] v=" + v.uniqueUUID
      + " gameTime=" + level.getGameTime()
      + " knownEventPhase=BuoyantForces.apply(after-this-ticks-native-step,before-next-ticks) substepIndex=UNAVAILABLE"
      + " floatLocalOffset=(" + flt.localOffset.x + "," + flt.localOffset.y + "," + flt.localOffset.z + ")"
      + " submergedFraction=" + fraction + " worldArm=" + arm
      + " unconditionalBuoyancyOnlyForce=" + buoyancyOnlyForce + " unconditionalDragOnlyForce=" + unconditionalDragOnlyForce
      + " appliedImpulse=" + new Vector3d(forceToApply).mul(PhysicsBridge.DT));
   }
   handle.applyImpulseAtPoint(inverse.transform(new Vector3d(arm)), inverse.transform(new Vector3d(forceToApply).mul(PhysicsBridge.DT)));
  }
  // MINECRAFT-518: rotational (roll/pitch/yaw) water drag, opposing the hull's own angular
  // velocity - the rotational analogue of each float's own linear drag term above, averaged
  // across every float's submerged fraction (0 with no floats at all, matching the untouched
  // volumePerFloat/awake behavior for that case). tuning.angularWaterDrag defaults to 0 (see
  // Overrides.FALLBACK), which reproduces the exact prior behavior of no dedicated rotational
  // damping term - this augments, never replaces, the per-float righting torque above.
  //
  // Review finding (John, comment 53679): applying this as an inertia-converted IMPULSE could
  // overshoot past zero and reverse/amplify the hull's spin for a light hull and a coefficient
  // within this field's own valid range - see Buoyancy.angularDragRetention's own doc for the
  // worked example and the fix. Fixed by scaling the hull's own CURRENT angular velocity
  // directly by a retention fraction mathematically guaranteed to stay in (0,1] (never an
  // impulse divided by an inertia this bridge cannot read at all via RigidBodyHandle), via
  // addLinearAndAngularVelocity (a direct velocity add/scale, not a force/impulse) - world-frame,
  // like `angular` itself already is, so no inverse-orientation transform is needed here (unlike
  // the per-float impulses above, which are LOCAL-frame).
  //
  // Second review finding (John, comment 53689): `angular` is the CALLER's snapshot, taken
  // before this method's own per-float impulses (and, for an amphibious Body, that domain's own
  // ground/flight forces) were applied this same tick. If the underlying Sable/Rapier pipeline
  // mutates the handle's stored velocity synchronously when an impulse is applied (not merely
  // queuing it for the next physics step), scaling the STALE snapshot and adding that delta onto
  // whatever the handle's velocity has already become this tick could still land on a result that
  // over/under-damps relative to the body's actual current spin - a real staleness/ordering risk,
  // independent of (and smaller in degree than) the magnitude-unboundedness defect comment 53679
  // already fixed. Fixed by reading the handle's own angular velocity FRESH, immediately before
  // this computation, rather than trusting the parameter captured at the top of this method -
  // correct regardless of whether Sable applies impulses synchronously or defers them, and costs
  // one extra getter call.
  Vector3d currentAngular = handle.getAngularVelocity(new Vector3d());
  double angularSpeed = currentAngular.length();
  if (!floats.isEmpty() && angularSpeed > 1e-6 && tuning.angularWaterDrag > 0) {
   double averageFraction = totalFraction / floats.size();
   if (averageFraction > 0) {
    double retention = Buoyancy.angularDragRetention(angularSpeed, averageFraction, tuning.angularWaterDrag, PhysicsBridge.DT);
    Vector3d deltaAngular = new Vector3d(currentAngular).mul(retention - 1.0);
    // MINECRAFT-545/547 (John's comment 54359) - diagnostic-only: this IS the rotational
    // water-drag term's own contribution for this tick, applied as a direct velocity delta (not
    // an impulse) via addLinearAndAngularVelocity below - the one OTHER force/torque source,
    // besides the per-float buoyancy/linear-drag already logged above, this method applies.
    if (GroundContactDiagnostics.ENABLED) com.mojang.logging.LogUtils.getLogger().info(
      "[DIV buoyantForcesDiagnostic] v=" + v.uniqueUUID + " gameTime=" + level.getGameTime()
        + " knownEventPhase=BuoyantForces.apply(after-this-ticks-native-step,before-next-ticks) substepIndex=UNAVAILABLE"
        + " rotationalDragDeltaAngular=" + deltaAngular + " retention=" + retention);
    handle.addLinearAndAngularVelocity(new Vector3d(), deltaAngular);
   }
  } else if (GroundContactDiagnostics.ENABLED) {
   // MINECRAFT-545/547 (John's comment 54359) - explicit confirmation, not silent absence: this
   // tick's rotational water-drag term did NOT fire for this body (tuning.angularWaterDrag
   // defaults to 0 via Overrides.FALLBACK, and this fixture sets no override), so the per-float
   // buoyancy/linear-drag impulses logged above are the ONLY force/torque source this method
   // itself applies this tick - confirmed, not merely assumed from the default's own value.
   com.mojang.logging.LogUtils.getLogger().info(
     "[DIV buoyantForcesDiagnostic] v=" + v.uniqueUUID + " gameTime=" + level.getGameTime()
       + " knownEventPhase=BuoyantForces.apply(after-this-ticks-native-step,before-next-ticks) substepIndex=UNAVAILABLE"
       + " rotationalDrag=INACTIVE_THIS_TICK angularWaterDrag=" + tuning.angularWaterDrag);
  }
  // Per-propeller drive, keyed by actual engine linkage (PartEngineAccessor.linkedPropellers -
  // IV's own private field, not reachable through any public API) rather than one vehicle-wide
  // signal applied to every installed propeller regardless of whether a given engine drives it
  // at all. If two running engines both link the same propeller, the first one found sets it -
  // that propeller is not double-driven by being added to twice. Direction comes from the
  // propeller's own desiredLinearVelocity (see Buoyancy.signedPropellerDrive), not the engine's
  // transmission gear - a propeller has no gearbox of its own, and a gear-based sign disagreed
  // with IV's real forward/reverse state whenever an engine sat in neutral while still turning
  // a propeller, or a dynamic-pitch propeller was reversed via reverseThrustVar.
  Map<PartPropeller, Double> drivenBy = new IdentityHashMap<>();
  for (var part : v.allParts) {
   if (!(part instanceof PartEngine engine) || !engine.running) continue;
   for (var linked : ((PartEngineAccessor)(Object)engine).div$linkedPropellers()) {
    drivenBy.putIfAbsent(linked, Buoyancy.signedPropellerDrive(linked.desiredLinearVelocity, v.throttleVar.currentValue));
   }
  }
  // MINECRAFT-544: marineSteerDegrees is an independent bound from the ground maxSteerDegrees -
  // it inherits maxSteerDegrees's own resolved value when left unconfigured (see
  // Overrides.resolveFields), so an amphibious vehicle's water-propeller thrust direction stays
  // byte-identical to before this ticket until an operator configures a separate marine limit.
  double steer = Buoyancy.propellerSteerAngleRadians(v.rudderInputVar.currentValue, EntityVehicleF_Physics.MAX_RUDDER_ANGLE, tuning.marineSteerDegrees);
  Vector3d forward = q.transform(Buoyancy.propellerThrustDirection(steer));
  var propellers = nonRotorPropellers(v);
  // Buoyancy.isPropellerOperational is the exact pure predicate this selection applies - only
  // an operational propeller counts toward the shared thrust budget's denominator, so a
  // merely-active-but-unlinked, linked-but-stalled, or linked-active-turning-but-zero-drive
  // (feathered/zero-pitch) propeller does not dilute the share a truly operational one gets
  // just by existing on the chassis.
  var operational = propellers.stream().filter(p -> Buoyancy.isPropellerOperational(
      drivenBy.containsKey(p), p.isActiveVar.isActive, ((PartPropellerAccessor)(Object)p).div$currentRPM(),
      drivenBy.getOrDefault(p, 0.0)))
    .toList();
  double perPropellerForce = Buoyancy.perOperationalPropellerForce(tuning.propellerForce, operational.size());
  for (var propeller : operational) {
   Vector3d anchor = new Vector3d(propeller.localOffset.x, propeller.localOffset.y, propeller.localOffset.z).sub(center);
   Vector3d arm = q.transform(new Vector3d(anchor));
   Vector3d point = new Vector3d(pose.position()).add(arm);
   boolean submerged = point.y <= waterSurfaceAt(level, point.x, point.z, point.y + 2);
   double thrust = Buoyancy.propellerThrust(drivenBy.get(propeller), perPropellerForce, submerged, true);
   if (thrust == 0) continue;
   awake = true;
   Vector3d force = new Vector3d(forward).mul(thrust);
   handle.applyImpulseAtPoint(inverse.transform(arm), inverse.transform(new Vector3d(force).mul(PhysicsBridge.DT)));
  }
  return awake;
 }

 /** MINECRAFT-545/547 (John's comment 54486): captures the full scan detail - the found block's
  * own Y and fluid height separately from the combined surface Y, plus the scan's own bounds -
  * in the SAME single pass {@link #waterSurfaceAt} always performed, so a caller wanting this
  * detail for logging never needs a second query. {@code iterations} is the REAL count of Y
  * values actually visited before returning (early-return preserved, per 54494's correction that
  * the prior version always reported the maximum capacity even on an early match) - distinct
  * from {@link #maxPossibleIterations}, the scan's own upper bound regardless of where/whether a
  * match occurs. */
 record WaterScanResult(boolean found, int foundBlockY, double fluidHeight, double surfaceY, int fromYFloor, int bottomY, int iterations) {}

 /** Pure: the scan's inclusive lower bound, exactly as the loop below computes it (24 blocks
  * below {@code fromYFloor}, inclusive of both endpoints). */
 static int scanBottomY(int fromYFloor) { return fromYFloor - 24; }

 /** Pure: the MAXIMUM possible Y values the loop below could visit if no match is ever found -
  * {@code fromYFloor} down to {@code bottomY} INCLUSIVE on both ends, so 25 (not 24) for the
  * scan's own 24-block span. NOT the actual per-call count on an early match - see
  * {@link WaterScanResult#iterations}. */
 static int maxPossibleIterations(int fromYFloor, int bottomY) { return fromYFloor - bottomY + 1; }

 /** MINECRAFT-545/547 (John's comment 54494): the water-presence check extracted behind a pure
  * callback seam, so the scan's own loop/early-return/iteration-counting logic is unit-testable
  * with a fake lookup - no {@code ServerLevel} dependency - while production code (below) still
  * supplies the real {@code level.getFluidState} call. Returns the found block's fluid height, or
  * {@code null} if {@code y} is not water. */
 @FunctionalInterface interface FluidHeightLookup { Double waterHeightAt(int y); }

 /** Pure (given its lookup): scans straight down from {@code fromYFloor} (inclusive) for up to
  * {@link #maxPossibleIterations} Y values, stopping at the FIRST water match - preserving the
  * original early-return and per-y query count exactly, with {@code iterations} reporting the
  * REAL number of lookups actually performed, not the scan's maximum capacity. */
 static WaterScanResult scanForWater(int fromYFloor, FluidHeightLookup lookup) {
  int bottom = scanBottomY(fromYFloor);
  int iterations = 0;
  for (int y = fromYFloor; y >= bottom; y--) {
   iterations++;
   Double height = lookup.waterHeightAt(y);
   if (height != null) return new WaterScanResult(true, y, height, y + height, fromYFloor, bottom, iterations);
  }
  return new WaterScanResult(false, 0, 0, Double.NEGATIVE_INFINITY, fromYFloor, bottom, iterations);
 }

 /** Production wrapper: the ONE real {@code level.getFluidState} call per visited Y, via the
  * pure {@link #scanForWater(int, FluidHeightLookup)} above - no second query. */
 private static WaterScanResult scanForWater(ServerLevel level, double x, double z, double fromY) {
  var pos = new BlockPos.MutableBlockPos(Mth.floor(x), 0, Mth.floor(z));
  return scanForWater(Mth.floor(fromY), y -> {
   pos.setY(y);
   var fluid = level.getFluidState(pos);
   return fluid.is(FluidTags.WATER) ? (double) fluid.getHeight(level, pos) : null;
  });
 }

 /** First water surface Y found scanning straight down from fromY, or NEGATIVE_INFINITY if none
  * - the propeller-submersion check's own single-value need; delegates to {@link #scanForWater}
  * so both call sites share the one real scan implementation. */
 private static double waterSurfaceAt(ServerLevel level, double x, double z, double fromY) {
  return scanForWater(level, x, z, fromY).surfaceY();
 }

 /** Spares (MTS's own VehicleGroundDeviceBox excludes them from support the same way) carry
  * no hull support of their own - counting one would both activate buoyancy at its spare
  * mounting location and dilute volumePerFloat away from the floats actually holding the hull
  * up. */
 private static List<PartGroundDevice> floats(EntityVehicleF_Physics v) {
  return v.allParts.stream().filter(p -> p instanceof PartGroundDevice g && !g.isSpare && g.definition != null
      && Classification.isFloatDevice(g.definition.ground))
    .map(p -> (PartGroundDevice) p).toList();
 }

 /** Excludes any spare propeller (same reasoning as {@link #floats}) and any installed rotor
  * propeller via {@link Classification#needsUnderwaterPropellerThrust} - see the class-level
  * note on why rotor thrust is already supplied by {@code PhysicsBridge.Body.stepRotor} and
  * must not be counted twice. */
 private static List<PartPropeller> nonRotorPropellers(EntityVehicleF_Physics v) {
  return v.allParts.stream().filter(p -> p instanceof PartPropeller pr && !pr.isSpare
      && Classification.needsUnderwaterPropellerThrust(pr.definition != null ? pr.definition.propeller : null))
    .map(p -> (PartPropeller) p).toList();
 }
}
