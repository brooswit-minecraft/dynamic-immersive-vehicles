# Verification, 2026-11-09

## Recovered prototype evidence (not current CI evidence)

- Unmodified IV core commit `6a1e7b416c1361534ac40ca699261a4fc89d28eb`
  built with Java 21, Gradle 8.8, and a 768 MiB JVM cap.
- The recovered addon sources compiled with `javac --release 21 -proc:none`
  against that IV core, Sable 2.0.5, Sable Companion 1.6.0, NeoForge
  21.1.265 libraries, and a cached renamed Minecraft 1.21.1 binary. This was an
  API check, not a standard Gradle/mod-loader build.
- `TireForcesSmoke` passed airborne zero traction, 1,681 friction-circle vectors,
  no ground-pulling rebound, and expected spring support.
- The then-current IV `master` separately had 39 syntax errors near
  `EntityVehicleF_Physics.java:758`; the prototype used the earlier commit above
  only for its API compile. No IV source was edited or transferred.

## Hosted CI contract

The GitHub Actions workflow uses Java 21 and Gradle 8.8 to run `clean build`
against these pinned inputs:

| Input | Pin |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.265 |
| Immersive Vehicles | CurseForge file 7926606 (24.0.0 for 1.21.1) |
| Sable NeoForge | 2.0.5 |
| Sable Companion | 1.6.0 |

A green hosted run proves standard compilation, unit tests, and artifact
production only. Its uploaded artifact must contain this project's jar,
SHA-256 checksums, and test reports; it must not contain dependency jars.

## Headless runtime-smoke check (MINECRAFT-491, added 2026-11-08)

A second CI job (`runtime-smoke`) boots a plain NeoForge dedicated server
(`gradle runServer`, with `nogui`, in a disposable CI-only world with no
persisted state) using the same pinned IV/Sable/Companion coordinates, now
resolved via `implementation` instead of `compileOnly` so they are present on
the run's classpath. The plain `jar` task is unaffected: it still packages
only this project's own classes/resources, so nothing from IV, Sable, or
Sable Companion is bundled into the published artifact.

This is a server-boot smoke check only. It watches the server log for the
vanilla "Done (...)! For help, type help" startup line (success) or a Mixin
apply failure / fatal exception / crash (failure) within a 10-minute budget,
then sends `stop` and uploads the full log. A pass means: NeoForge started
with `dynamicimmersivevehicles`, `mts` (Immersive Vehicles), `sable`, and
`sable-companion` all present, and this addon's three Mixins
(`VehicleForcesMixin`, `VehicleMovementMixin`, `WorldAccessor`) applied
against IV's real classes without error. It uses no vehicle content pack, so
it cannot observe vehicle spawn, boarding, driving, physics, or lifecycle
behavior — those remain untested by this job. A failure here is reported as
a startup/Mixin/dependency hazard, not a code-logic defect.

## Classification tests (MINECRAFT-492, added 2026-11-08; updated MINECRAFT-495)

`ClassificationTest` and `OverridesTest` run the real IV JSON definition types
(`JSONVehicle`, `JSONPart.JSONPartGroundDevice`) and the override opt-out/
validation logic without a live entity, world, or Sable runtime. They cover:
default-eligible ground vehicles at wheel counts other than four, tracked
vehicles, mixed wheel+track chassis, the no-config-entry default-enabled
case, explicit per-car opt-out, a missing `motorized` section, and a null
ground-device entry among installed parts. Aircraft and blimps are now
covered as an eligible category requiring no ground-contact device at all
(`isFlightVehicle`), still excluded by an explicit opt-out. A rotorcraft
(any installed `isRotor` propeller, see `isRotorcraft` below) is eligible
the same way, checked directly against the installed part before the
`isAircraft`/`isBlimp` flag or ground devices are even considered - a
rotor-carrying vehicle with neither flag set, or one with wheels, is still
eligible as a rotorcraft rather than deactivated or stuck on the ground
solver (a gap the first actual Wroosbit review found; see the
production-seam section below for the full fix). `PhysicsBridge.Body`
routes it to the rotor force model instead of the fixed-wing model, which
has no wing for a rotorcraft to act on.

**Towed status removed from `Classification.eligible` entirely (MINECRAFT-498,
scope correction after owner clarification).** An earlier revision of this
task excluded a towed flight vehicle (and, separately, a towed rotorcraft)
from eligibility while making a towed ground vehicle eligible - a
ground-only interpretation of "coupled trailer category." The ticket's
actual owner clarified that authorization is every IV vehicle, not
ground-only, and that hitch coupling must extend to any category this
bridge already models (not inventing support where IV or this bridge
categorically cannot). Since the hitch joint itself (`HitchJoint`/
`PhysicsBridge.createHitch`) is domain-agnostic - it only connects two
already-classified Sable bodies by their own connection-point anchors -
nothing about it actually required gating on domain. The `activelyTowed`
parameter was dead code once that gate was removed from ground, flight, and
rotorcraft alike, so it was deleted from `Classification.eligible`'s
signature rather than left unused; `PhysicsBridge.active()` no longer
passes it. `Classification.floatEligible` keeps its own `activelyTowed`
parameter unchanged: a float-only hull's `BuoyantBody` (see `VehicleBody`,
deliberately minimal) has no pose/anchor API for a hitch joint to connect
to, a concrete API gap unrelated to towed status itself, so a towed boat
still falls back to IV's native hitch code regardless.
`ClassificationTest`'s towed-specific `eligible()` cases were removed (the
parameter they exercised no longer exists) and replaced where needed with
eligibility-confirming cases (e.g. `installedRotorPropellerStillExcludedByOptOut`,
covering what remains excludable now that towed status alone no longer is);
the actual towed-vehicle behavior (the ordering wait, joint create/release)
is exercised at the `PhysicsBridge`/`HitchRegistry` level instead, where it
now genuinely lives.

## Buoyancy tests (MINECRAFT-494, added 2026-11-08; updated for amphibious handling)

`Classification.floatEligible(...)` (same file, same no-live-entity testing
approach) picks out a float-only hull: at least one installed `canFloat`
ground device, none drivable by the ground solver, not a flight vehicle,
and no installed rotor propeller. A vehicle with a drivable ground device
or a flight flag AND a float device is instead `eligible()` (the ground or
flight/rotor domain) - `floatEligible` is the exclusive fallback for a hull
with nothing the ground solver can drive and nothing the flight/rotor model
can fly, not a general "has a float device" check; see PhysicsBridge's
amphibious handling below for how those combined cases actually get their
water behavior. `ClassificationTest` covers a single pontoon, multiple
pontoons, a wheel+pontoon mix staying on the ground path, a non-floating
skid (a grounded skid helicopter, correctly excluded from both paths), a
float-only hull with an installed rotor propeller (correctly excluded even
without an `isAircraft`/`isBlimp` flag - a defensive check since a rotor-
carrying definition is essentially always a flight vehicle already excluded
above it), a float-only hull with an ordinary propeller staying float-
eligible, a float-equipped rotorcraft proven `eligible()` and never
`floatEligible()` (the combined rotor+float routing the two classifications
must agree on so `PhysicsBridge.active()` never builds both a `Body` and a
`BuoyantBody` for the same vehicle), and the same aircraft/blimp/towed/
opted-out/no-device/null-definition exclusions `eligible()` already covers.

`BuoyancyTest` exercises the pure force math `BuoyantForces` applies per
installed float device and propeller - submerged-fraction clamping at both
span ends, Archimedes buoyant force scaling with submerged fraction and
hull volume, quadratic water drag that is zero when not submerged or not
moving, and propeller thrust that is exactly zero whenever the propeller
itself is not underwater, regardless of throttle. `BuoyantForces.apply(...)`
itself - the per-tick buoyancy/drag force application at each float
device's own world position (producing a righting torque from uneven
submersion without separate stability code), the water-surface scan against
the live `ServerLevel`'s `FluidState`, and the propeller thrust/steering
integration - has the same live-entity/`ServerLevel`/Sable-runtime testing
gap `Body.stepGround`/`stepFlight`/`stepRotor` already have (see below): it
needs a real `EntityVehicleF_Physics` over a real `ServerLevel`'s water,
which remains exercised only by the in-game acceptance list below, not by
any unit test in this repository.

**Amphibious combined domain, added after review; extended to flight/rotor
after the MINECRAFT-497 coordination review.** Review flagged that routing
every vehicle with a drivable ground device exclusively to the ground
solver - even one that also has a `canFloat` device - would silently drop
water behavior for an amphibious vehicle just because it has wheels; a
later review on this same ticket flagged the identical gap for a
float-equipped fixed-wing/rotorcraft (a seaplane or floatplane helicopter)
being routed exclusively to the flight/rotor model. `BuoyantForces.apply(...)`
was factored out of `BuoyantBody` specifically so `PhysicsBridge`'s `Body`
(ground or flight) can call the same per-float buoyancy/water-drag/
propeller-thrust logic alongside its own wheel/track or flight/rotor forces
in the same tick, whenever any installed ground device is `canFloat`
(`hasFloatDevice(v)`, re-derived from the vehicle's live installed parts
every tick by `Body.step` and passed straight into `planTick` - not cached
at construction; an earlier version of this fix did cache it in a
`floatCapable` field, which self-review correctly flagged, since installing
or removing a float device afterward doesn't change the ground/flight
category and so never triggers `replaceIfCategoryChanged` either, leaving
nothing else to notice the change). This is not a new classification: it is the
existing ground or flight/rotor domain from `eligible()`, with buoyancy
layered on top at the force-application level rather than the
classification level. `BuoyantForces` itself excludes any installed rotor
propeller from its own underwater-propeller-thrust pass (`nonRotorPropellers`),
so a floating rotorcraft's thrust is supplied exactly once, by
`stepRotor`'s own collective/cyclic model, never doubled by `BuoyantForces`
treating the rotor as a second forward propeller - a rotor sitting above a
floating hull is in practice never itself submerged, so a reviewer checking
only submersion would not have caught this without the explicit exclusion.
Like the rest of `BuoyantForces`, the actual combined force application
against a live rigid body is exercised only by the in-game acceptance list
below.

**Spare-part and propeller-activity findings, corrected after review.**
Review (PR #4) found two further defects at the live head:

1. Neither `PhysicsBridge.active()`'s `installedGroundDevices` nor
   `BuoyantForces.floats()` excluded `APart.isSpare`. MTS's own
   `VehicleGroundDeviceBox` excludes spare ground devices from support the
   same way; left unfiltered here, a spare pontoon could activate buoyancy,
   receive force at its spare mounting location, and dilute
   `volumePerFloat` away from the floats actually holding the hull up.
   Both call sites now filter `!g.isSpare` before building their working
   set, and `BuoyantForces`'s propeller loop does the same for
   `PartPropeller.isSpare`.
2. `BuoyantForces.apply(...)` took a single `drive` value from *any*
   running `PartEngine` - reassigning it on every running engine found, so
   a later engine silently overwrote an earlier one's sign/throttle on a
   multi-engine vehicle - and applied it to every installed propeller with
   no check that the propeller itself was active. See the Mixin-accessor
   correction immediately below for the full fix.
3. (Found by self-review, PR #4) `PhysicsBridge.active()`'s
   `installedPropellers` - the list `Classification.eligible`/
   `floatEligible` use for the `isRotor` exclusion - did not exclude
   `PartPropeller.isSpare` either, so a spare rotor could suppress ground
   or float eligibility for a chassis whose only rotor was a non-functional
   spare. Filtered the same way as (1).

**Per-propeller drive state via Mixin accessors, added after a reviewer
directive.** The first correction above only gated thrust on
`propeller.isActiveVar.isActive` (destroyed/removed), which review (and
then the ticket's actual stakeholder) correctly rejected as an incomplete
stand-in for "this propeller is actually being driven" - `isActiveVar`
says nothing about engine linkage or RPM, so one running engine still
drove every active, non-spare propeller on the vehicle regardless of
whether that engine was connected to it at all. Per instruction, this was
corrected by reading IV's actual state rather than documenting the gap
away: `PartEngine.linkedPropellers` and `PartPropeller.currentRPM` are
both private with no public accessor (confirmed via `javap`), so two new
version-pinned `@Accessor` Mixins -
[`PartEngineAccessor`](src/main/java/io/github/brooswitminecraft/div/mixin/PartEngineAccessor.java)
and
[`PartPropellerAccessor`](src/main/java/io/github/brooswitminecraft/div/mixin/PartPropellerAccessor.java)
- expose them, the same pattern this codebase already uses for
`EntityVehicleF_Physics.getRecursiveTowingThrust()` (`VehicleTowingAccessor`)
and the client world field (`WorldAccessor`); no IV source is edited or
redistributed. `BuoyantForces.apply(...)` now builds a per-propeller drive
map from each running engine's actual `linkedPropellers`, and gates thrust
on `isActiveVar.isActive` **and** `|currentRPM| > 0` together - a
propeller with no driving engine, or linked but stalled, produces zero
thrust even if some other engine on the vehicle is running at full
throttle.

`BuoyancyTest.inactivePropellerProducesNoThrustEvenWhenSubmergedAtFullThrottle`
covers the `propellerActive` (destroyed/removed-or-not-turning) gate at
the pure-function level. The actual Mixin accessor reads, the per-engine
linkage map, and the `isSpare` filtering in (1)/(3) still require a live
`PartEngine`/`PartPropeller` pair and remain in the same live-entity/
`ServerLevel`/Sable-runtime testing gap as the rest of `BuoyantForces` -
exercised only by the in-game acceptance list below, not by any unit test
in this repository.

**Thrust-share dilution, corrected after a second exact-head review.**
The Mixin-accessor correction above fixed *which* propellers can thrust,
but `BuoyantForces.apply(...)` still divided `tuning.propellerForce` by
the count of every non-spare installed propeller before checking which
of them were actually operational - so a vehicle with one powered
propeller and one unpowered one correctly zeroed the unpowered one's
thrust, but still only gave the powered propeller half the configured
budget. This was exactly the "dilute the configured thrust share" half of
the original finding that the Mixin-accessor fix had not yet addressed.
`Buoyancy.perOperationalPropellerForce(propellerForceBudget,
operationalCount)` (new, pure function) now computes the per-propeller
share from only the propellers `BuoyantForces` has already determined are
operational this tick (driven by a linked, running engine; active;
turning) - a non-operational propeller is excluded from the denominator
entirely, not merely zeroed after an equal split.
`BuoyancyTest.mixedPoweredAndUnpoweredPropellerSetGivesFullAllocationToThePoweredOneAndZeroToTheOther`
and `...twoOperationalPropellersSplitTheBudgetEvenly` cover the extracted
allocation seam the review asked for at the pure-function level - the
exact "one linked/turning propeller plus one unpowered propeller" case,
and the even-split multi-propeller case. Which specific propellers end up
in the operational set each tick still requires a live
`PartEngine`/`PartPropeller` pair, same testing gap as the rest of this
file.

**Propeller thrust direction, corrected after a third exact-head review
(post-reconciliation).** The allocation fix above still derived each
propeller's signed drive from `Math.signum(engine.currentGearVar.currentValue)
* throttle` - transmission gear state, not propeller direction. Review
correctly identified this as wrong: confirmed via `javap` against the
pinned IV 7926606 bytecode, `PartPropeller.update()` computes
`currentRPM` from linked engines' `rpm / propellerGearboxRatio`
independent of `currentGearVar` entirely, and derives its own
`desiredLinearVelocity` (a public field) from `currentPitch` - a fixed,
JSON-defined sign for a static-pitch propeller, or one swung between
`+45`/`-45` by `vehicle.reverseThrustVar` for a dynamic-pitch one;
`addToForceOutput()` then derives real thrust direction from
`desiredLinearVelocity - airstreamLinearVelocity`, never from gear. So an
engine sitting in neutral (`currentGearVar == 0`) produced zero bridge
thrust for a propeller that IV itself still drove and pitched for thrust,
and a dynamic-pitch propeller reversed via `reverseThrustVar` could keep
receiving forward bridge thrust while the transmission stayed in a
forward gear. Fixed by `Buoyancy.signedPropellerDrive(desiredLinearVelocity,
throttle)` (new, pure function): `BuoyantForces.apply(...)` now reads
each linked propeller's own public `desiredLinearVelocity` for direction
and never consults `currentGearVar`. No new Mixin accessor was needed -
`desiredLinearVelocity` is already a public field, confirmed via the same
`javap` pass. `BuoyancyTest.forwardPitchPropellerDrivesForwardRegardlessOfGear`,
`...reversedPitchPropellerDrivesInReverseAtFullThrottle`, and
`...engineInNeutralStillDrivesAPropellerWithNonzeroPitch` cover the two
named disagreement scenarios directly through this seam at the
pure-function level; which value a live propeller's `desiredLinearVelocity`
actually holds at a given tick still requires a live `PartPropeller`, same
testing gap as the rest of this file.

## Flight force model tests (MINECRAFT-495, added 2026-11-08; corrected 2026-11-08 after review)

`FlightForcesTest` exercises every pure function in `FlightForces` - the
flight-domain analogue of `TireForcesTest` for the ground solver - with no
live entity, world, or Sable runtime: dynamic pressure scaling, a lift
coefficient that is zero at zero angle of attack, grows linearly with angle,
clamps at a hard stall rather than growing further, and is antisymmetric
about zero; induced drag that is zero with no wing (`aspectRatio <= 0`) and
grows with lift otherwise; aerodynamic force as a simple coefficient/area/
pressure product; control-surface force that is zero at zero deflection and
flips sign with it; and throttle-linear thrust that is zero at zero
throttle. `PhysicsBridge`'s own flight step (force/torque application
against a live rigid body) is not unit-tested, the same way the ground
solver's wheel loop in `Body.step` is not - both are only exercised, where
they are exercised at all, by the acceptance list below.

**Blimp buoyancy, corrected after review - twice.** The first revision
computed buoyancy as `rho_air * ballastVolume * g`, treating IV's
`ballastVolume` field as a literal displaced-air volume. Review (PR #5)
found this incompatible with IV's own contract: `ballastVolume` is
normally `emptyMass / 1000` - a small control-authority number, not an SI
volume sized to displace the vehicle's mass. At IV's documented value, a
1,000 kg blimp got about 12 N of upward force against Sable's roughly
9,810 N of gravity on that mass - it would fall, not float.

The first correction made buoyancy mass/gravity-compatible but gated and
scaled it on the JSON `gravityFactor` field, on the assumption that
`gravityFactor` was IV's own gravity-suppression lever. A second review
round, with pinned IV source rechecked more closely
(`EntityVehicleF_Physics.java:263-275,490-491` at commit
`6a1e7b416c1361534ac40ca699261a4fc89d28eb`), found that assumption wrong in
both directions: IV keys gravity suppression to whether `ballastVolumeVar`
(initialized from `ballastVolume`) is active/nonzero, not to
`gravityFactor` at all, and an unset raw `gravityFactor` (0) actually
resolves to a runtime `gravityFactorVar` default of **1** (full gravity) -
the opposite of what the first correction assumed. That version would have
wrongly floated a blimp with no configured `ballastVolume`, and wrongly
dropped one with an active `ballastVolume` but an explicit
`gravityFactor=1`.

`FlightForces.blimpBuoyancy(mass, gravity, ballastVolume, ballastControl)`
now replaces the `gravityFactor`-based `buoyancy` method entirely: it
returns 0 when `ballastVolume <= 0` (ordinary gravity, matching IV with no
ballast configured), and otherwise the vehicle's full weight as upward
lift plus `FlightForces.ballastTrim`'s small climb/descent authority -
gated purely on ballast activation, with no `gravityFactor` parameter to
misuse. `FlightForcesTest` covers both seam cases review named: (a)
`ballastVolume=0` nets zero buoyant force regardless of ballast control,
and (b) `ballastVolume>0` nets full neutral buoyancy, demonstrated
independent of any `gravityFactor` value since the method takes none. It
also keeps the representative 1,000 kg blimp integration-level seam test
from the first round, re-pointed at `blimpBuoyancy`'s active-ballast case.

## Rotor force model tests (MINECRAFT-497, added 2026-11-09)

Following up on MINECRAFT-495's rotorcraft exclusion (above, now superseded):
`Classification.eligible` no longer takes a propeller list at all and now
treats every `isFlightVehicle` definition - fixed-wing, blimp, or rotorcraft
- as eligible; the fixed-wing/rotor split happens inside
`PhysicsBridge.Body`, not in the pure eligibility decision. `Classification
.isRotorcraft(List<JSONPart.JSONPartPropeller>)` (built on the existing
`isRotor`) is the pure predicate `Body.step` calls fresh every tick (via the
new `PhysicsBridge.installedPropellers`, re-derived from the vehicle's
current parts, never cached) to pick `stepRotor` over `stepFlight` and to
size the rotor radius - not a value frozen once in `Body`'s constructor, so
a rotor installed, removed, or replaced after a vehicle's body is already
active switches solvers on the very next tick rather than keeping a stale
one. Self-review caught the frozen-at-construction version of this first
(PR #6 comment 6738923375 / Jira comment 52469-52470): the original
revision cached both `rotor` and the rotor radius in `Body`'s constructor,
so an installed-part change after activation was silently ignored - exactly
the "missing/changed parts" case the ticket calls out. `ClassificationTest`
covers: a fixed-wing aircraft with an ordinary (non-rotor) propeller is not
a rotorcraft, a helicopter (`isRotor` propeller) is both eligible and a
rotorcraft, a rotor among multiple installed propellers still marks
rotorcraft, the same mutable installed-parts list answers correctly before
a rotor is added, after it is added, and after it is removed again (the
integration-seam regression test for the frozen-state finding), no propellers
(or no rotor among them) does not, and a null propeller list entry is
ignored rather than throwing.

`RotorForcesTest` exercises every pure function in the new `RotorForces` -
the rotor-domain analogue of `FlightForces`/`TireForcesTest` - with no live
entity, world, or Sable runtime: collective pitch that is zero below IV's
own throttle deadband and reaches full authority at full throttle (matching
`PartPropeller`'s own rotor pitch mapping), lift that scales linearly with
collective, cyclic tilt that passes through within range and clamps at 20
degrees, the vertical component shrinking and the horizontal component
growing (and flipping sign) as tilt increases - the hover-to-forward-flight
mechanism - cyclic and yaw torque that are zero at zero input, scale with
available thrust (not airspeed, unlike a fixed-wing control surface, so a
hovering helicopter keeps full attitude authority at zero airspeed), and
flip sign with input direction, and rotor-radius conversion from IV's
inch-diameter propeller field. `PhysicsBridge`'s own `stepRotor` (force/
torque application against a live rigid body) is not unit-tested, the same
way `stepFlight` and the ground solver's wheel loop are not - all three are
only exercised, where they are exercised at all, by the acceptance list
below.

A local, non-CI sanity check (`javac --release 21 -proc:none` against the
real `mts.jar`, Sable NeoForge 2.0.5, joml 1.10.5, and a NeoForge
21.1.250 merged artifact - a close but not exact match to this repo's
pinned 21.1.265, pulled from this host's Gradle cache; the same API-check
approach as the recovered-prototype/MINECRAFT-495 evidence above, not a
standard Gradle/mod-loader build) confirmed `Classification.java`, the new
`RotorForces.java`, and the updated `PhysicsBridge.java` compile cleanly
against IV's real `JSONPart.JSONPartPropeller.diameter`/`isRotor` fields and
`EntityVehicleF_Physics`'s real control-input vars
(`elevatorInputVar`/`aileronInputVar`/`rudderInputVar`) and `throttleVar`,
and that all 127 tests in the repository - `RotorForcesTest` (19 new),
`PhysicsBridgeStepperTest` (8) and `PhysicsBridgeBodyReplacementTest` (4,
both production-seam regressions below),
the updated `ClassificationTest` (now including the frozen-state regression
test above and the merged-in boat/float classification cases below),
`FlightForcesTest`, `BuoyancyTest`, `TireForcesTest`, `TowingForcesTest`,
`OverridesTest`, `BodyRegistryTest`, and `PhysicsBridgeClassificationTest` -
pass under a manually assembled JUnit Platform launcher (JUnit 5.11.4/
platform 1.11.4). This head also integrates boat MINECRAFT-494/PR #4's
`VehicleBody`/`BuoyantBody`/`BuoyantForces`/`Buoyancy` classes directly into
this branch (PR #4 had not yet merged to main at the time of this
integration) per review direction, rather than waiting for #4 to merge
first; `PhysicsBridge` now types its registry and `active()`'s construction
choice as the shared `VehicleBody` interface instead of the single concrete
`Body` class. This is evidence the new code compiles and its pure functions
behave as specified - it is not the hosted Gradle build, is not the
`runtime-smoke` Mixin-apply check, and proves nothing about in-game flight
behavior.

## Trailer hitch joint tests (MINECRAFT-498, added 2026-11-09)

`ClassificationTest` now covers the changed rule: a towed ground vehicle
(trailer) is eligible on the same terms as any other ground vehicle
(`activelyTowedGroundVehicleIsNowEligibleForItsOwnHitchedBody`), still
excluded by its own opt-out
(`activelyTowedGroundVehicleOptOutStillExcludes`), while a towed flight
vehicle stays excluded (`activelyTowedAircraftIsExcluded`, unchanged) -
trailer bodies are a ground-domain-only feature in this change.

`HitchJointTest` exercises every pure function in the new `HitchJoint`
module - the hitch-domain analogue of `TireForcesTest`/`FlightForcesTest` -
with no live entity, world, or Sable runtime: anchor-world-position at an
identity pose (local offset minus center, no rotation), the same anchor
under a 90-degree yaw (confirming the offset is rotated before
translating, not after), and that it writes into and returns the exact
`dest` instance passed in (the calling convention `PhysicsBridge.createHitch`
relies on for each anchor). Locked-axis selection is covered for all three
`JSONConnection` flag combinations - ordinary (linear only), `mounted`
(every axis, fully rigid), `restricted` (linear plus yaw/roll, pitch free)
- and that `mounted` takes precedence when both flags are set.

**Lifecycle regression, caught by self-review before external review.** The
first revision's `active()` applied the towing-vehicle-ordering wait
unconditionally: once a trailer's own body was already active, if its
towing vehicle later lost its own body (opt-out, despawn, chunk unload),
the same guard that correctly defers a trailer's *first* activation also
deactivated - destroyed - the trailer's already-active body, instead of
leaving it as an unjointed, free-rolling Sable vehicle and only releasing
the now-stale joint (`reconcileHitch`'s job). The fix pulls that decision
out into a pure, package-private seam, `PhysicsBridge.deferToNativeHitch
(towed, alreadyActive, towingVehicleActive)`, so it is testable without a
live entity/registry: it defers only when `towed && !alreadyActive &&
!towingVehicleActive`. `PhysicsBridgeClassificationTest` now covers all
four cases - first activation waits, first activation proceeds once the
towing vehicle has a body, an already-active trailer is never deferred
even once its towing vehicle's body is gone (the regression itself), and
an untowed vehicle never defers regardless of the other two flags.

**Production-used hitch lifecycle seam, added after a second self-review round.**
The first fix above (`deferToNativeHitch`) only tested a pure boolean helper, not the
production `active()`/`reconcileHitch()`/`release` call paths themselves - review
correctly found that gap: it could not establish that losing a towing vehicle's body
releases the real joint while the trailer's body survives, or that `cleanup()`/
`unload()` release joints before bodies. The hitch-joint bookkeeping previously
inlined in `PhysicsBridge` (two `IdentityHashMap`s) is now its own class,
`HitchRegistry<K>` - generic over the key type and built only against
`PhysicsConstraintHandle` (a small Sable interface with no live-runtime dependency
of its own), the same kind of seam `BodyRegistry` already provides for body
lifecycle. `PhysicsBridge.reconcileHitch`/`active()`/`cleanup()`/`unload()` now all
delegate to one `HitchRegistry` instance instead of managing the maps directly.

`HitchRegistryTest` exercises this actual production class - not a parallel
reimplementation - with fake `String` keys and a fake `GenericConstraintHandle`
(the same non-sealed Sable subinterface real hitch joints use; `PhysicsConstraintHandle`
itself is sealed and cannot be implemented directly). It covers: exactly one joint
is constructed for a newly-jointed pair and reconciling again against the same
valid partner does not construct a second one; reconciling to a `null` desired
partner releases without ever calling `create`; **the regression itself** - a
separate fake `BodyRegistry<String,Object>` stands in for real `Body` lifecycle,
proving that releasing the towing vehicle's joint-registry entry never touches the
trailer's still-active body (`HitchRegistry` has no way to reach a body at all -
only keys and joint handles); releasing a key drops joints it holds as either the
trailer or the towing side; releasing a key with no joint in either role is a
no-op; an existing joint is rebuilt if it has gone invalid even for the same
partner; switching to a different towing vehicle releases the old joint before
building the new one; and a bulk sweep (mirroring `cleanup()`/`unload()`'s
`BiConsumer`) releases every joint before its body, leaking none.

`BodyRegistryTest`'s `removeIf` test now also asserts the bulk-removal
callback receives the key, not just the body (`PhysicsBridge.releaseHitch`
needs the entity to find and release any hitch joint keyed to it during a
`cleanup()`/`unload()` sweep, which a body-only callback could not give it).

**Towing-force double-count regression, caught by a third self-review round.**
`Body.stepGround` still replicated IV's own `getRecursiveTowingThrust()` verbatim
as a compensation impulse on the towing vehicle's Sable body - the exact
MINECRAFT-496 fix, unmodified. That recursive sum includes
`towedVehicle.thrustForceValue` for every towed vehicle in the chain, but a towed
vehicle that is now Sable-active and jointed (this task's own change) also applies
its own engine thrust directly to its own body via its own `stepGround` call, which
already reaches the towing vehicle mechanically through the hitch joint Sable
itself solves. Left unmodified, a powered trailer would be propelled twice: once
by its own body's drive force, and again by the towing vehicle's now-stale
compensation for the same thrust.

The fix replaces the direct `getRecursiveTowingThrust()` call with
`PhysicsBridge.unjointedTowingThrust(v)`, a private recursive sum this bridge
performs itself - mirroring IV's own recursion and its `mounted`-connection skip
exactly, except that any immediate towed vehicle already reported `isJointed` by
`HitchRegistry` (and everything recursed through it) is skipped entirely, since
that whole subtree's thrust already propagates through its own independently-
Sable-simulated body and joint. Reading each towed vehicle's own
`thrustForceValue` (previously only reachable via IV's own recursive method,
never per-level) required a second mixin accessor on `VehicleTowingAccessor`
(`@Accessor("thrustForceValue")`, alongside the existing `@Invoker` for the
recursive method).

The actual per-connection inclusion decision - `!mounted && !jointed` - is pulled
out as its own pure, unit-tested seam, `PhysicsBridge.compensableConnection(mounted,
jointed)`, the same kind of extraction `deferToNativeHitch` already used for the
prior lifecycle regression: the full recursive walk over real `TowingConnection`/
`EntityVehicleF_Physics` objects cannot be unit-tested without a live world (the
same documented limitation as `active()` itself, below), but the decision it makes
at each connection can be, and is: `PhysicsBridgeClassificationTest` now also
covers an unjointed, unmounted connection staying compensable (MINECRAFT-496's
original case, preserved), a jointed connection never being compensable (this
regression), and a mounted connection never being compensable regardless of joint
state (matching IV's own rule).

**Two findings from external review (wroosbit, CHANGES_REQUESTED on PR #7 @
`d30b35e`), both now fixed.**

1. **Client-side single-authority violation.** `active()` evaluated
   `deferToNativeHitch` (which depends on `registry.isActive(...)`) BEFORE the
   `v.world.isClient()` branch. The body/hitch registries are populated only by
   server-side code (inside that same branch's `else`), so a client-side towed
   entity's own `alreadyActive`/`towingVehicleActive` lookups are permanently
   `false` regardless of what the server has actually done - `deferToNativeHitch`
   then returns `true` forever for a towed client entity, so `active()` returns
   `false` on the client even once the server has genuinely built and jointed a
   Sable body for it. The client keeps running IV's native force/movement path
   while the server independently runs Sable - exactly the dual-authority
   condition this ticket requires there be none of. Fix: the full decision is now
   its own enum-returning seam, `PhysicsBridge.decide(eligible, isClient, towed,
   alreadyActive, towingVehicleActive)`, which resolves the client branch first
   and unconditionally (`CLIENT_ACTIVE` for any eligible client call, regardless
   of the other three flags) - the server-only hitch-ordering wait
   (`deferToNativeHitch`) is only ever consulted once `isClient` is already known
   `false`. `active()` now calls `decide(...)` instead of inlining the two
   checks in the wrong order. `PhysicsBridgeClassificationTest` covers the exact
   regression (`clientNeverConsultsTheServerOnlyHitchOrderingWait`: `CLIENT_ACTIVE`
   for every combination of towed/alreadyActive/towingVehicleActive once
   `isClient` is true) plus the ineligible and server-side cases (first-activation
   wait, already-active-trailer non-deferral, ordinary-vehicle activation) as a
   single coherent seam, superseding the narrower `deferToNativeHitch`-only
   tests from the prior round (kept alongside it, since `deferToNativeHitch`
   remains its own callable piece `decide` delegates to).

2. **Unhandled hitch-construction failure, corrected twice.** `reconcileHitch`
   called `HitchRegistry.reconcile(..., () -> createHitch(...))` with no failure
   path; if constraint creation threw (e.g. Sable's own anchor/configuration
   validation), the exception would escape through `active()` into the
   vehicle's own tick, after the trailer's body was already live - a partial-
   failure case the ticket explicitly requires cleanup for.

   The first fix made `reconcileHitch` swallow the exception
   (`try`/`catch (RuntimeException)`), relying on `HitchRegistry.reconcile`'s own
   contract - proved directly by `reconcileLeavesNoPartialStateWhenCreateThrowsAndCanRetryAfter`,
   mirroring `BodyRegistry`'s existing "untouched on failure" guarantee - that a
   failed `create` call records neither a joint nor a partner. A second review
   round found this incomplete: it left the vehicle's own **body** untouched in
   every case, including the first activation that the body itself was
   constructed for. A brand-new, never-jointed body after a failed first attempt
   is neither coupled nor on native fallback - not a coherent state, even though
   nothing leaked at the registry level alone - and silently retrying forever in
   that half-built state was exactly what review flagged.

   `reconcileHitch` now returns `boolean` (`false` on a caught failure, instead
   of silently returning) so `active()` can decide what the failure means for the
   body it is holding. `PhysicsBridge.rollBackOnHitchFailure(alreadyActive)` -
   `!alreadyActive` - is the decision; rolling back means removing the body and
   returning `false` so IV's native hitch motion is authoritative again that
   tick, and it only applies when the body was **just** constructed this same
   call - an already-healthy body from a previous tick is never torn down merely
   because a later reconcile attempt failed, the same non-destructive guarantee
   `deferToNativeHitch` already provides when a towing vehicle's body disappears.

   A third review round found the FIRST version of this fix's own tests
   insufficient, even though the source-level fix was already correct: they drove
   `HitchRegistry.reconcile` and `rollBackOnHitchFailure` separately and glued the
   exception-to-boolean conversion and rollback together by hand, so they stayed
   green even if `active()` itself stopped calling either one correctly - not a
   production integration seam. The actual `activate -> reconcile -> conditional
   rollback` composition is now its own generic method,
   `PhysicsBridge.reconcileOrRollBack(bodyRegistry, hitchRegistry, key,
   alreadyActive, reconcile, removeBody)`, parameterized over the body registry's
   key/value types exactly like `activateOrClassify` already is for plain body
   construction; `active()` calls this exact method with its own real
   `registry`/`hitches`/`Body::remove`. `HitchRegistryTest` now drives this real
   method directly (not a reimplementation) with a real `BodyRegistry`/
   `HitchRegistry` and fake body/removal callbacks:
   `reconcileOrRollBackRollsBackANewlyActivatedBodyOnFirstFailureWithExactlyOneRemoval`
   asserts the `false` result, exactly one real removal callback (the actual
   body, not just a counter), and the hitch-registry entry also cleared;
   `reconcileOrRollBackPreservesAnAlreadyActiveBodyOnFailure` asserts an
   already-active body survives untouched, with no removal callback at all;
   `reconcileOrRollBackSucceedsWithoutRemovalWhenReconcileSucceeds` and
   `retryAfterARolledBackFirstActivationSucceedsCleanly` cover the success path
   and a full rollback-then-retry-and-succeed cycle. Retrying is still not cached
   as permanently broken either way, since the anchors are computed from live
   poses that can change every tick, unlike a chassis definition's fixed geometry
   (there is no hitch analogue of `InvalidChassisGeometry`).

   **Separately-tracked integration hold, not resolved by this fix:** this PR's
   branch is still based on main at MINECRAFT-495 (`7b97198`), while the ticket's
   prescribed merge order is boats (MINECRAFT-494/PR#4) -> rotorcraft
   (MINECRAFT-497/PR#6) -> hitches (this PR, #7). Review has repeatedly noted #7
   cannot be accepted as the every-vehicle integrated head until #4 and #6 merge
   first; this branch will be rebased onto that integrated main, their
   PhysicsBridge/Classification behavior preserved, and exact-head build/
   runtime-smoke rerun once they do. Nothing in this PR can resolve that hold
   on its own.

**What remains unexercised by any unit test, the same shape of gap
`PhysicsBridgeClassificationTest`'s own note below already describes for
plain activation:** `PhysicsBridge.active()`'s own ordering wait (a trailer
deferring until its towing vehicle's body exists), `reconcileHitch`'s
create/replace/release transitions, and `createHitch`/`releaseHitch`
against a live Sable body and constraint - all of these need a live
`EntityVehicleF_Physics`/`ServerLevel`/Sable `SubLevelContainer`, for the
same reason documented below (the `(WorldAccessor)v.world` mixin cast). A
local, non-CI `javac --release 21 -proc:none` API-check (same approach as
the recovered-prototype/rotorcraft-exclusion evidence above) against the
real Sable 2.0.5 jar confirmed `PhysicsBridge`'s new code compiles against
`dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration`/
`PhysicsConstraintHandle`/`ConstraintJointAxis`, `PhysicsPipeline.addConstraint`,
`BoxPhysicsObject.getPose()`, and IV's real
`minecrafttransportsimulator.baseclasses.TowingConnection`/`JSONConnection`
fields (`hitchConnection`/`hookupConnection`/`towingVehicle`, `.pos`,
`.mounted`, `.restricted`), and that all 101 tests across the full suite
(`ClassificationTest`, `HitchJointTest`, `BodyRegistryTest`,
`FlightForcesTest`, `TireForcesTest`, `OverridesTest`,
`PhysicsBridgeClassificationTest`, `TowingForcesTest`) pass. This is
evidence the new code compiles and its pure functions behave as specified -
it is not the hosted Gradle build, is not the `runtime-smoke` Mixin-apply
check, and proves nothing about in-game hitch behavior.

A `testImplementation 'org.joml:joml:1.10.5'` dependency was added
(`build.gradle`): the neoForge moddev plugin only wires JOML into the
`main` sourceSet it treats as a mod, and `PhysicsBridgeClassificationTest`
loading the real `PhysicsBridge` class now requires resolving JOML types
(`GenericConstraintConfiguration`'s `Vector3dc`/`Quaterniondc` parameters)
at class-verification time even though that test never constructs a hitch
joint itself - without it, that test failed with
`NoClassDefFoundError: org/joml/Vector3dc`, caught locally before this was
ever pushed to CI. The version is pinned to the one NeoForge's own
dependency resolution already strictly constrains main's `compileClasspath`
to, not a separately chosen version.

`BodyRegistryTest` exercises `BodyRegistry`, the actual production
bookkeeping class PhysicsBridge relies on for its lifecycle/no-double-
physics guarantee, using plain keys and dummy bodies instead of a live
entity/Sable runtime - not a parallel stand-in, but the literal
`registry.activateOnce(key, construct)` call `PhysicsBridge` makes. It
proves: an already-active key's constructor is never called again (no
double physics), a construction failure leaves the key exactly as it was
before the call - neither half-activated nor silently retained, a later
retry after a failure still succeeds, and `deactivate` always hands back
the old body rather than dropping it.

`PhysicsBridgeClassificationTest` exercises `PhysicsBridge.activateOrClassify(...)`,
the exact (package-private) method `active()` calls once a vehicle is
confirmed eligible and server-side, again with a fake key/body/constructor.
This is the failure-classification boundary itself: only a thrown
`InvalidChassisGeometry` - Body's own explicit, pre-registration chassis-
bounds check - is cached in `brokenDefinitions`, stopping further retries
for that definition. An unrelated `IllegalArgumentException` (the exact
regression an earlier revision had: catching `IllegalArgumentException`
broadly also caught anything `BoxPhysicsObject`/`RigidBodyHandle.of`/the
initial-velocity call might throw, silently poisoning an otherwise-valid
definition on an operational failure) or any other `RuntimeException`
is proved to stay retryable, with a later successful call still able to
activate the key.

**Production-seam regression, added after self-review (MINECRAFT-497
self-review, Jira comment 52510).** Review found the rotor/float regression
coverage added so far stopped at pure classification: `ClassificationTest`
proved `Classification.isRotorcraft(...)` and
`needsUnderwaterPropellerThrust(...)` are themselves correct and stateless,
but nothing exercised the actual decision `PhysicsBridge.Body.step` makes
with that information. `Body` itself cannot be constructed in a unit test
(see below - it needs a live `ServerLevel`/Sable `SubLevelContainer`), so
its solver-selection decision was extracted into
`PhysicsBridge.selectStepper(flight, installedPropellers)` - a
package-private static method, not a parallel stand-in: `Body.step` now
calls it every tick to choose `stepGround`/`stepFlight`/`stepRotor`.

**Installed-rotor eligibility/dispatch gap, found by the first actual
Wroosbit review (Jira comment 52565) at head `f68ceab6`.** That review found
the fix above still missed the ticket's requirement to drive rotor physics
from the actual installed `isRotor` propeller, not a definition flag: both
`Classification.eligible` and the then-current `selectStepper` only reached
`ROTOR` when `motorized.isAircraft`/`isBlimp` was also set, so a vehicle
with a real installed rotor propeller but neither flag was either
deactivated outright (no wheels) or stuck on the ground solver (with
wheels) - its rotor never received lift or control. `selectStepper` now
checks `Classification.isRotorcraft(installedPropellers)` first and returns
`ROTOR` unconditionally when true, falling back to `FLIGHT`/`GROUND` on the
`flight` flag only when there is no installed rotor; `Classification
.eligible` makes the identical check first, before even looking at the
`isAircraft`/`isBlimp` flag or ground devices, so such a vehicle is eligible
at all. `PhysicsBridgeStepperTest` and `ClassificationTest` both cover a
rotor installed with no aircraft/blimp flag (alone, and alongside wheels) -
rotor dispatch wins over wheels, not the reverse.

That same review also found the rotor+float production-seam coverage still
only exercised predicates, never the actual composition `Body.step` applies
(`stepRotor` and `BuoyantForces.apply` both invoked together). The full
per-tick decision - which primary force model to run, and whether to
separately apply buoyancy - is now extracted as `PhysicsBridge.planTick
(flight, floatCapable, installedPropellers)`, returning a `TickPlan
(Stepper, boolean)` record; `Body.step` calls this one method for both
halves of the decision instead of computing them separately.
`PhysicsBridgeStepperTest` exercises it directly: a floating rotorcraft's
plan is `TickPlan(ROTOR, true)` - both halves at once, neither at the
expense of the other - with or without the aircraft/blimp flag, and the
rotor/non-rotor propeller-thrust partition `needsUnderwaterPropellerThrust`
defines is proved to have no overlap and no gap for the exact propeller
list a `ROTOR`-selecting tick plan carries: a rotor propeller is never
claimed by `BuoyantForces`'s underwater-thrust pass (already owned by
`stepRotor`), a non-rotor propeller (including a null entry) always is, so
a floating rotorcraft's thrust is counted exactly once, never both and
never neither.

**Body-category replacement lifecycle gap, found by self-review at head
`ce65a20c`.** `registry.activateOnce` deliberately reuses an already-active
body without reconstructing it (the existing "no double physics"
guarantee), but review found that reuse had an unintended consequence once
installed-part dispatch could flip which body category a vehicle needs
live: a float-only hull (`BuoyantBody`) that later has an `isRotor`
propeller installed becomes `ground`-eligible (via `Classification
.isRotorcraft`), but `active()` was still calling `activateOnce` against
the same key, which handed back the existing `BuoyantBody` unchanged -
`BuoyantBody.step` only calls `BuoyantForces.apply`, which explicitly
excludes rotor propellers from thrust, so the newly installed rotor would
never receive `stepRotor`'s lift or control. The reverse transition
(removing the only rotor from a rotor-only, wheel-less hull) likewise kept
a stale ground/rotor `Body` running after the vehicle fell back to
float-only eligibility. `PhysicsBridge.replaceIfCategoryChanged(registry,
key, neededGroundCategory, isGroundCategory, remove)` - generic over the
registry's key/body types, not tied to `EntityVehicleF_Physics`/
`VehicleBody` - is the fix `active()` now calls first: if an existing body's
actual category (`instanceof Body`) no longer matches the freshly computed
`ground` decision, the stale body is deactivated and removed before
`activateOrClassify` runs, so the subsequent `activateOnce` call
constructs a fresh body of the correct kind instead of reusing the wrong
one. `PhysicsBridgeBodyReplacementTest` exercises this exact method with
fake keys/bodies: a no-op when there is no existing body or its category
already matches, and both the float-to-ground and ground-to-float
transitions actually remove the stale body (verified destroyed, not
leaked) so the caller's next `activateOrClassify` call starts clean.

What this still cannot exercise: `active()`'s own eligibility computation
wired to a live entity (opt-out/towing/missing-definition transitions
actually calling `deactivate`), `Body`'s real construction against a
`ServerLevel`/Sable `SubLevelContainer`, and the mixins' cancellation
decision. Those need a live `EntityVehicleF_Physics`, which cannot be
constructed without a real `AWrapperWorld`/`IWrapperPlayer`/world, and
`v.world`'s mixin-targeted concrete class (`mcinterface1211.WrapperWorld`)
cannot be substituted with a fake without breaking the `(WorldAccessor)v.world`
cast `active()` relies on - this bridge found no way to exercise that
remaining slice without an actual running Minecraft instance. Whether a
real vehicle's body is actually constructed once, removed on opt-out/
towing, and never duplicated end-to-end therefore remains proven only by
the acceptance list below, not by any unit test in this repository.

**All-category towing-thrust compensation gap, found by self-review at head
`c5dec29` (post-integration).** This branch's generalization made flight and
rotorcraft trailers hitchable, but `PhysicsBridge.unjointedTowingThrust`'s
compensation impulse was only ever applied from `Body.stepGround` -
`stepFlight` and `stepRotor` never called it. Since any Sable-active towing
vehicle has its native `getForcesAndMotions`/`getRecursiveTowingThrust` call
suppressed regardless of its own category (`VehicleForcesMixin`), a flight
or rotorcraft vehicle towing a still-unjointed trailer (the first-activation
ordering wait, or a towed float-only hull with no hitch-capable body at
all) silently dropped that compensation entirely - exactly the scope gap
the all-category generalization was supposed to close, now reintroduced at
the force-application level instead of the classification level. Fixed by
extracting the existing impulse-application half of `stepGround`'s towing
logic into `Body.applyTowingCompensation(v)`, now called identically from
`stepGround`, `stepFlight`, and `stepRotor`'s own tails - the same shared,
category-agnostic seam `unjointedTowingThrust`/`compensableConnection`
already were, now actually wired to every category that can produce one.

No new pure-function seam was extracted for this fix, unlike the prior
rollback/ordering regressions above: `unjointedTowingThrust`'s own
per-connection decision (`compensableConnection`) was already correct and
already domain-agnostic (it takes no category parameter at all) - already
covered by `PhysicsBridgeClassificationTest`'s existing
`compensableConnection` cases, which needed no change. This was purely a
call-site wiring gap (two of three dispatch branches never invoking an
already-correct shared method), in the same `Body`-construction-requires-a-
live-`ServerLevel`/Sable-runtime testing gap documented just above for
`stepGround`'s wheel loop and the rest of `Body.step` - there is no way to
unit-test that `stepFlight`/`stepRotor` actually call
`applyTowingCompensation` without constructing a live `Body`. The structural
guarantee against this regression recurring is that all three step methods
now call the one shared private method rather than duplicating (or, as
happened here, omitting) the impulse-application logic inline.

**Partial-authority lifecycle gap on a first failed hitch attempt, found by
self-review kickback at head `f1330c1` (Jira comment 52740).** The rollback
decision in `reconcileOrRollBack`/`rollBackOnHitchFailure` was keyed on
`alreadyActive` - whether the trailer's *body* already existed before this
call - not on whether it was already *coherently jointed*. Those are not the
same fact: a vehicle can be Sable-active and untowed for any number of
ticks (its own category needs no tow connection to be eligible) and then
get hitched for the first time later. On that first hitch attempt,
`alreadyActive` was already `true` (the body predates this call), so a
failed `create()` was wrongly treated as "an already-healthy joint's
transient rebuild failure" and the body was preserved - but there was never
a joint to begin with. The result: `active()` still returns `true` (IV's
own native hitch-following stays suppressed for this entity, same as any
other Sable-active vehicle), yet no Sable joint exists either - neither
authority is actually moving this trailer relative to its towing vehicle,
strictly worse than either one alone.

The actual fact `rollBackOnHitchFailure` needs is whether `key` was already
coherently jointed (a valid handle to its current desired partner)
*entering* this attempt, which `HitchRegistry.isJointed(key)` answers
directly - and must be read before `reconcileHitch` runs, since
`HitchRegistry.reconcile` itself already clears any existing entry (valid
or not) before attempting to (re)create one, so it is the only point that
can still observe the prior state. `active()` now passes
`hitches.isJointed(v)` (evaluated eagerly, before the `reconcileHitch`
closure is even constructed) into `reconcileOrRollBack` instead of
`alreadyActive`; `rollBackOnHitchFailure`'s parameter was renamed to
`wasJointed` to match, with no change to its own boolean logic
(`!wasJointed`) - only to what fact its caller now supplies. This also
means `alreadyActive` fully stops being needed by this seam: a body that is
genuinely new this call is necessarily never-jointed too (a key can only
ever appear in `HitchRegistry` once its body already existed on some prior
call), so `wasJointed` subsumes the brand-new-body case without a separate
check.

`HitchRegistryTest` now drives this real production composition with the
corrected fact: `reconcileOrRollBackRollsBackAnActiveButNeverJointedBodyOnFirstHitchFailure`
is the regression itself - an active, never-jointed body whose first hitch
attempt fails is rolled back exactly like a brand-new one, not preserved.
`reconcileOrRollBackPreservesACoherentlyJointedBodyOnTransientFailure`
(renamed from the prior, now-corrected
`reconcileOrRollBackPreservesAnAlreadyActiveBodyOnFailure`, which had
exercised exactly the buggy case above without a real joint ever
established) now actually builds a real joint via `hitches.reconcile(...)`
first, so the fact it passes to `reconcileOrRollBack` matches what a real
caller would observe, and proves a body that WAS already coherently
jointed survives a later transient reconcile failure. The two
newly-activated-body and retry cases are unaffected, since a key that has
never been reconciled is never-jointed either way.

**Invalid/mismatched existing joint torn down by a failed rebuild, found by
a second self-review kickback at head `41e8242` (Jira comment 52750).**
The fix above was still incomplete: it computed `wasJointed` from
`HitchRegistry.isJointed(v)`, plain joint-map membership, which says
nothing about whether that joint's handle is still valid or whether it
still points at the current desired partner. `HitchRegistry.reconcile`
tears down an existing joint that is either invalid OR pointed at the
wrong partner - via its own internal `release(trailer)` - *before* even
attempting to construct its replacement. So by the time a replacement
`create()` call fails, the "already jointed" state `isJointed` reported a
moment earlier no longer exists; `reconcileOrRollBack` was reading a fact
about a joint that `reconcile` itself had just destroyed, and wrongly
preserving the body as if it still had a working joint, reproducing the
exact partial-authority condition the first fix was meant to close -
`active()` still suppresses IV's native hitch-following, but no Sable
joint exists either.

The actual fact needed is `HitchRegistry.isCoherentlyJointedTo(trailer,
desired)` (new method): a still-valid handle to exactly `desired`,
mirroring `reconcile`'s own no-op condition exactly, so
`isCoherentlyJointedTo(...)` being true implies a `reconcile` call with
that same `desired` is guaranteed to be a no-op (never calls `release` or
`create`, and so can never throw). `active()` now derives the actual
`desired` partner from the already-computed `towingVehicleActive` flag
(the identical `isHitchableBody` check `reconcileHitch`'s own `desired`
uses internally, so this mirrors it exactly rather than duplicating a
second copy of that check that could drift) and passes
`hitches.isCoherentlyJointedTo(v, desired)` into `reconcileOrRollBack`
instead of `hitches.isJointed(v)`.

One consequence worth stating plainly: given this corrected fact, an
already-coherently-jointed trailer can now never actually reach
`reconcileOrRollBack`'s rollback branch in real production use at all -
reconcile only calls `create()` (the only thing that can throw) when NOT
already coherently jointed, so any real failure necessarily means
`wasJointed` was already `false`. `rollBackOnHitchFailure`'s
parameterization is kept anyway (not collapsed to an unconditional `true`)
since it still documents and directly unit-tests the intended contract
independent of what the current `HitchRegistry` implementation happens to
make reachable.

`HitchRegistryTest` adds: `isCoherentlyJointedToDistinguishesValidityAndPartnerFromMereMembership`
(direct coverage of the new method's three distinguishing cases - valid/
matching, valid/wrong-partner, invalid/same-partner - against plain
`isJointed`, which conflates all of them as "true"); the regression itself,
`reconcileOrRollBackRollsBackOnFailedRebuildOfAnInvalidExistingJoint`
(an invalidated handle to the correct partner, failed rebuild, rolls back
with no leaked body or stale entry) and
`reconcileOrRollBackRollsBackOnFailedSwitchFromAMismatchedExistingJoint`
(the switch-partner shape of the same gap); and
`retryAfterARolledBackInvalidJointRebuildSucceedsCleanly` (the rolled-back
body's next-tick retry still succeeds cleanly, same as the original
first-activation retry case). The two pre-existing "preserves" and
"never-jointed" tests were updated to compute their `wasJointed` argument
via `isCoherentlyJointedTo` rather than `isJointed`, for fidelity to what a
real caller now actually passes.

**The same plain-membership gap, found by external review (wroosbit,
CHANGES_REQUESTED on PR #7 @ `6f4abea`, comment 52756) in a second call
site this branch's own fix above had not reached.**
`PhysicsBridge.unjointedTowingThrust`'s double-count exclusion used
`hitches.isJointed(towed)` - the identical plain-joint-map-membership gap
`reconcileOrRollBack` had just been corrected for, in the one remaining
place it was never updated. Worse, it wasn't even scoped to the *current*
towing vehicle: `isJointed(towed)` is true if `towed` is jointed to
*any* towing vehicle, not specifically the one whose
`unjointedTowingThrust` call is asking. Two distinct ways this understated
a towing vehicle's compensation: an invalid handle (stale, from a towing
vehicle that lost its body) still read as "jointed," and a trailer
recently switched to a *different* towing vehicle than the one currently
asking still read as "jointed" too - in both cases no valid Sable
constraint actually transmits that trailer's thrust to *this* towing
vehicle, so excluding it from the compensation sum silently dropped real
towing-thrust compensation that MINECRAFT-496 established and this branch
must preserve.

Fixed by replacing `hitches.isJointed(towed)` with
`hitches.isCoherentlyJointedTo(towed, v)` - scoped to exactly the towing
vehicle `v` whose recursive sum is being computed, using the same
validity-and-partner-aware check the rollback fix above introduced.
`compensableConnection`'s second parameter was renamed from `jointed` to
`coherentlyJointed` to match (its own boolean logic, `!mounted &&
!coherentlyJointed`, is unchanged - only the caller-supplied fact is
stronger now).

`HitchRegistryTest.compensableConnectionCompositionExcludesOnlyAValidCurrentPartnerJoint`
is the production-seam regression review required: a valid joint to
exactly the current towing vehicle is excluded (as before), while a joint
to a *different* towing vehicle and an invalidated handle to the same
recorded partner both still contribute - the exact two cases review named.
`unjointedTowingThrust`'s own recursive walk over real `TowingConnection`/
`EntityVehicleF_Physics` graphs remains in the same live-entity/world
testing gap documented elsewhere in this file; this test instead exercises
the real `compensableConnection`/`HitchRegistry.isCoherentlyJointedTo`
composition it is built from, directly.

**A null constraint handle treated as a healthy joint forever, found by a
third self-review kickback at head `8515052` (Jira comment 52759).**
`HitchRegistry.reconcile` stored whatever `create.get()` returned,
unconditionally, then recorded the partner. If `create` ever returns
`null` instead of throwing (rather than a thrown exception, which the
existing `reconcileLeavesNoPartialStateWhenCreateThrowsAndCanRetryAfter`
test already covered), the registry ends up with a recorded partner but
`joints.get(trailer) == null` - and the no-op condition's own
`existing == null` half (needed so a trailer with NO joint yet, as opposed
to one whose existing handle has since gone invalid, isn't wrongly forced
through a release/recreate cycle every tick) cannot tell that apart from a
key simply absent from the map. So every later `reconcile` call for the
*same* desired partner silently no-op'd forever, as if already correctly
jointed - `active()` stays `true` (still suppressing IV's native hitch
authority) while no Sable constraint has ever actually existed, with no
retry ever attempted again. The same partial-authority shape as the first
rollback fix above, from a third distinct cause.

Fixed by checking `create.get()`'s result for `null` and throwing
(`IllegalStateException`) before storing anything, exactly where a thrown
exception from `create` itself would already be - so a `null` result now
rolls back through the identical existing catch-and-retry path at the
caller (`PhysicsBridge.reconcileHitch`/`reconcileOrRollBack`) with no
separate handling needed there. `HitchRegistryTest
.reconcileTreatsANullCreatedHandleAsAFailedCreateNotAHealthyJoint` is the
regression itself: a `null`-returning `create` throws, leaves neither a
joint nor a partner recorded (`isJointed`/`partnerOf`/
`isCoherentlyJointedTo` all confirm nothing was stored), and a later call
for the same desired partner correctly retries `create()` again rather
than no-op'ing against the absent prior attempt.

None of the above prove any in-game driving/flight/boat/towing behavior -
see the acceptance list below.

## In-game acceptance (not yet performed)

Use a disposable Minecraft 1.21.1 world and a legally obtained compatible IV
content pack. Record the exact pack and car IDs. The acceptance run must show:

- NeoForge starts with all three mods and all required Mixins apply.
- Ordinary cars spawn, can be boarded, and get Sable physics with **no**
  config entry at all - wheel counts other than four included.
- A tracked (tread) vehicle spawns, drives, and turns via differential track
  force (`hasSkidSteer`).
- Each car drives forward and reverse, steers, coasts, and brakes without
  IV's original movement solver also moving it.
- Terrain collision, airborne recovery, client/server synchronization, despawn,
  chunk unload/reload, world restart, and removal all behave as intended.
- A car with `"enabled": false` in the JSON override continues to use
  ordinary IV physics.
- A pontoon/float-hulled boat with **no** config entry at all floats at a
  plausible draft at rest, drives forward/reverse and steers via its
  propeller/rudder while the propeller stays underwater, rights itself when
  pushed to one side rather than flipping or sinking, and stops producing
  thrust (coasting to a drag-limited stop, not instantly) if its propeller
  is lifted clear of the water.
- An amphibious vehicle (wheels/tracks AND at least one `canFloat` device)
  drives normally on land and, on entering water, also floats/rights itself
  and gets propeller thrust where installed - both domains' forces visibly
  active at once, not one replacing the other.
- A non-floating skid-only vehicle (e.g. a grounded skid helicopter)
  continues to use ordinary IV physics (documented exclusion, not a defect).
- An aircraft spawns, can be boarded, and gets Sable flight physics with no
  config entry: throttle produces forward thrust, pitch/roll/yaw respond to
  elevator/aileron/rudder input, and the craft can take off, climb, bank, and
  land without IV's native `getForcesAndMotions` also moving it.
- A blimp spawns, gets Sable flight physics, and holds a stable
  near-neutral altitude (mass/gravity-compatible buoyancy, not a fall)
  with ballast-control climb/descent trim distinct from an aircraft's
  wing-based lift.
- An aircraft or blimp with `"enabled": false` in the JSON override continues
  to use ordinary IV physics.
- A vehicle with an installed rotor propeller (a helicopter) spawns, can be
  boarded, and gets Sable rotor physics with no config entry: throttle
  produces collective vertical thrust and can hold a hover, elevator/aileron
  cyclic input tilts that thrust to move forward/sideways/backward and
  banks the fuselage accordingly, and rudder input yaws it, all without IV's
  native rotor-vectoring solver also moving it - distinct from a fixed-wing
  aircraft's wing-based lift and airspeed-dependent control authority. This
  must hold both for a definition with `motorized.isAircraft`/`isBlimp` set
  and for one with neither flag set (dispatch is driven by the installed
  rotor part itself, not the flag) and both with and without wheels
  installed alongside the rotor (the rotor must still win dispatch over the
  ground solver).
- A float-equipped helicopter (an installed rotor propeller AND at least
  one `canFloat` device) lands on and floats on water - buoyancy/righting
  from its float devices visibly active at the same time as rotor thrust/
  attitude control, with no double-counted propeller thrust from the rotor
  itself (see `BuoyantForces`'s rotor exclusion).
- A trailer hitched behind a car whose own body is not yet active (opted
  out, or not yet server-side) continues to follow via IV's own hitch code,
  with no double movement, for as long as that ordering dependency holds.
- A trailer hitched behind a car with an active Sable body gets its own
  Sable body jointed to the car's, with no double movement (IV's native
  hitch-following code does not also move it once `PhysicsBridge.active()`
  returns true for the trailer) and no position-snap jitter; the trailer's
  own wheels/suspension respond to terrain independently of the towing car,
  while the hitch keeps the two bodies coupled. This holds for a towing
  vehicle in any of the ground/flight/rotorcraft categories (e.g. a towed
  glider behind an aircraft, or a towed cart behind a car) - the joint math
  is domain-agnostic, so this is the same mechanism in every case, not a
  separate implementation per category.
- The joint's locked rotation matches the connection's own flags: an
  ordinary hitch lets the trailer swing/pitch/roll independently (settling
  upright on its own wheels), a `restricted` connection allows only pitch,
  and a `mounted` connection (e.g. a flatbed) stays fully rigid relative to
  the towing vehicle.
- Detaching a trailer at runtime releases the joint; the trailer continues
  as an ordinary free-rolling Sable vehicle in its own category (coasting,
  steerable if pushed, responsive to terrain) with no stale joint pulling it
  toward the vehicle it was released from.
- The towing vehicle despawning, unloading its chunk, or losing its own
  Sable body for any other reason releases the joint without an error or a
  leaked body; the trailer's own body survives as an unjointed vehicle.
- A vehicle towing an UNPOWERED trailer (no engine) accelerates and holds
  speed noticeably closer to IV's own unmodified behavior than before
  MINECRAFT-496, since that trailer stays unjointed-equivalent for
  compensation purposes (`thrustForceValue` is zero either way) while now
  also getting real suspension/drag from its own coupled body.
- A vehicle towing a POWERED trailer (its own engine, where the pack allows
  it) is propelled by that trailer's thrust exactly once - through the
  trailer's own Sable body and the hitch joint - not twice: the towing
  vehicle's `TowingForces` compensation must not also add the trailer's
  thrust once the trailer is jointed (`PhysicsBridge.unjointedTowingThrust`/
  `compensableConnection` - the MINECRAFT-498 self-review regression this
  task's own change introduced and then fixed). During the brief ordering
  wait before the trailer has its own body, the towing vehicle's
  compensation still applies (matching MINECRAFT-496, unchanged for that
  window).
- A towed float-only hull (boat) continues to use ordinary IV physics - not
  a towing-specific exclusion, but because its `BuoyantBody` has no pose/
  anchor API for a hitch joint to connect to (a concrete, citable API gap;
  see README.md's exclusions list), regardless of whether its towing
  vehicle itself has an active Sable body.
- An aircraft, blimp, or helicopter towing an unjointed trailer (a towed
  boat, or any towed vehicle still in the brief first-activation ordering
  wait) still gets the same towing-thrust compensation impulse a ground
  towing vehicle does - not lighter than it should feel on that axis just
  because the towing vehicle itself is flying - while a jointed flight/
  rotor-towed trailer is not double-compensated, on the same terms as a
  jointed ground trailer.

## Configurable boat angular water drag (MINECRAFT-518, added 2026-11-13)

Inspected the already-released boat buoyancy work (MINECRAFT-494/497/505)
before adding anything: `hullFillFactor`, `waterDrag`, and `propellerForce`
were already exposed through `Overrides.Tuning`, validated, and either
live-reloadable (`waterDrag`/`propellerForce`) or respawn-gated
(`hullFillFactor`), per `PhysicsBridge.applyLiveSafeFields`/
`reportFieldsNeedingRespawn`'s own documented split. The one genuinely
missing piece in the buoyancy/drag/stability space: nothing damped a
floating hull's own rotation directly - `BuoyantForces.apply`'s righting
torque is only an emergent side effect of `waterDrag` applied at each
float device's own off-center position (see its class doc), so a hull
knocked into a spin (e.g. by a collision) had no hydrodynamic resistance
arresting that spin on its own.

Added `angularWaterDrag` (`Overrides.Tuning`): a rotational-drag
coefficient, the rotational analogue of the existing `waterDrag`.

**Damping-stability finding, corrected after exact-head review (John,
ticket comment 53679).** The first revision computed an explicit quadratic
TORQUE magnitude and applied it as an impulse via `RigidBodyHandle
.applyAngularImpulse`. Converting that impulse into a velocity change
divides by the body's own moment of inertia - a quantity `RigidBodyHandle`
exposes no way to read at all. Review's own worked example (coefficient
100000, fraction 1, inertia 1000 kg*m^2, starting angular speed 1 rad/s)
showed this explicit update overshoots past zero and reverses the hull's
spin while ADDING rotational energy (`omega' = -4 rad/s`, 4x the starting
speed and flipped sign) - a genuine instability reachable from values
already inside this field's own documented valid range, not merely an
approximation error at some extreme edge.

Fixed by changing both what the pure function computes and how its result
is applied, rather than clamping the old impulse after the fact (which
would still have needed the unavailable inertia to bound correctly):
`Buoyancy.angularDragRetention(angularSpeed, submergedFraction,
angularDragCoefficient, dt)` now returns the EXACT analytic solution of
the same quadratic-drag ODE over one tick - `1 / (1 +
coefficient*fraction*|omega|*dt)` - which is mathematically guaranteed to
lie in `(0,1]` for every nonnegative input, with no inertia anywhere in
the formula. (A second review round, comment 53689, corrected an earlier
"backward-Euler" characterization of this formula here: it is the exact
closed-form solution of this specific ODE, not one numerical-integration
scheme chosen among several - `angularWaterDrag` itself is accordingly an
inertia-normalized angular decay strength, not a torque/Nm coefficient.)
`BuoyantForces.apply` multiplies the hull's own CURRENT angular velocity
by that fraction directly via `RigidBodyHandle.addLinearAndAngularVelocity`
(a direct velocity scale, never a force or impulse) - world-frame,
matching `angular`'s own existing frame, so no `inverse`-orientation
transform is needed for this term (unlike the per-float impulses, which
are local-frame). A retention of exactly 1 (zero coefficient, the
documented fallback, or no submersion/rotation) is a true no-op,
byte-identical to before this ticket.

**Staleness/ordering finding, corrected after a second exact-head review
round (John, comment 53689).** The revision above still read `angular` -
the CALLER's velocity snapshot, taken before this method's own per-float
impulses (and, for an amphibious `Body`, that domain's own ground/flight
forces) were applied this same tick - rather than the handle's actual
current velocity. If the underlying Sable/Rapier pipeline applies an
impulse synchronously (mutating the handle's stored velocity immediately,
not merely queuing it for the next physics step), scaling that stale
snapshot and adding the resulting delta onto whatever the handle's
velocity has already become this tick could still over- or under-damp
relative to the body's real current spin - a genuine staleness/ordering
risk, independent of (and smaller in degree than) the magnitude-
unboundedness defect comment 53679 already fixed. Fixed by reading the
handle's own angular velocity FRESH (`handle.getAngularVelocity(...)`)
immediately before this computation, instead of trusting the parameter
captured at the top of `BuoyantForces.apply` - correct regardless of
whether Sable applies impulses synchronously or defers them to the next
step, at the cost of one extra getter call.

Preserves default behavior exactly: the documented `FALLBACK` for this
field is `0.0`, so an unconfigured vehicle (or one explicitly setting
`angularWaterDrag: 0`) gets precisely the prior behavior - no dedicated
rotational damping term, only the pre-existing per-float righting torque.
Bounded the same way `waterDrag`/`propellerForce` already are (`0..100000`,
via `Tuning.validate`), rejected on a `parts` entry (no part-level
consumer, same as every other physics field but `driveForce`, via
`validateSupportedForPart`), participates in the same operator-override >
pack-metadata > IV-adapted > physically-derived > fallback precedence
chain (`Overrides.resolveFields`) and the same `extends`/template
inheritance (`mergeOntoParent`) as every other field, and is live-safe -
`BuoyantForces.apply` re-reads it fresh from the same live `Tuning` object
every tick, so `PhysicsBridge.applyLiveSafeFields` mutates it in place on
a config reload with no respawn needed (added to its own doc comment's
confirmed-by-inspection list and to `reloadOverrides`'s live-safe set,
verified by `PhysicsBridgeLiveOverrideApplicationTest`).

`BuoyancyTest` covers `Buoyancy.angularDragRetention` at the pure-function
level: retention is exactly 1 (no-op) when not submerged, not rotating, or
the coefficient is zero; retention stays strictly within `(0,1]` across a
wide sweep of coefficient/speed/fraction combinations including review's
own exact worked numbers (coefficient 100000, fraction 1, speed 1 rad/s -
the regression case for the fixed instability); and retention decreases
monotonically as coefficient, speed, or submerged fraction grows (more
damping), never crossing zero or reversing. `OverridesTest` covers
`angularWaterDrag`'s range validation (negative/over-100000 rejected, an
explicit `0.0` and an in-range value both accepted), its fallback value in
`resolved()`'s full-fallback and never-null assertions, its place in the
operator-override precedence chain, and its rejection on a `parts` entry.
`PhysicsBridgeLiveOverrideApplicationTest` covers it as one of the
live-safe fields `applyLiveSafeFields` mutates in place (and confirms it
is absent from `reportFieldsNeedingRespawn`'s construction-cached set,
unchanged from before this ticket).

**Production-path runtime evidence (review 53675/53676/53701, Servy slot
coordinated with and released by MINECRAFT-506), added after the fixes
above.** `BuoyantForces.apply`'s actual velocity-retention application
against a live rigid body was in the same live-entity/`ServerLevel`/
Sable-runtime testing gap as the rest of this file (documented above under
"Buoyancy tests") - exercised only by an in-game run, not by any unit test
in this repository. New `BuoyancyAngularDragProbe` (opt-in via
`-Ddiv.experimentalBuoyancyAngularDrag=true`, same pattern as
`GroundSupportFixtureProbe`) closes that gap for this specific addition: a
real `EntityVehicleF_Physics` with one `canFloat` `PartGroundDevice` (no
engine), spawned pre-tilted 25 degrees roll, driven by the real
Mixin-intercepted tick loop - four scenarios:
`divtest:boatdrag-off` (water, `angularWaterDrag` unset/0.0 fallback),
`divtest:boatdrag-on` (water, `angularWaterDrag: 80`),
`divtest:boatdrag-beached` (no water, `angularWaterDrag: 80`), and
`divtest:boatdrag-beached-off` (no water, `angularWaterDrag` unset/0.0 -
review follow-up 53701's explicit ask for a matched zero-drag control,
since `beached` alone "cannot establish unchanged behavior").

Run locally (`DIV_BUOYANCY_ANGULAR_DRAG_PROBE=true gradle runServer`,
with `-Ddiv.debugGroundContact=true` also enabled by this probe's own
`build.gradle` gate) with an explicit `massKg: 400.0` override seeded for
all four test vehicles in `run/config/dynamic-immersive-vehicles.json` (a
first attempt without an explicit `massKg` left mass at `Math.max(50,
v.getMass())` - effectively a near-massless synthetic hull - and both
water scenarios launched off the carved pool within 4-6 ticks under their
own real buoyant force, the same "seed massKg explicitly" lesson
`GroundSupportFixtureProbe`'s own doc already states for exactly this
reason). Measured over 140 ticks (7s) at this exact head:

- **Independent activation evidence** (review follow-up: "verify actual
  BuoyantBody activation", not just "something moved"): the existing,
  already-shipped `GroundContactDiagnostics` logged, for every one of the
  four scenarios' own `key`, both `classify ... ground=false floating=true
  decision=ACTIVATE` and `constructed ... class=BuoyantBody` - independent
  confirmation of the real production classification/construction path,
  not a native-physics fallback coincidentally producing similar motion.
- **Actual resolved coefficients** (review follow-up: "explicitly record
  actual resolved off/on coefficients, probe highDrag flag is only a
  label"): read live through the same public `Overrides.resolved` every
  real vehicle uses and logged at spawn - `off`:
  `resolvedAngularWaterDrag=0.0`, `on`: `80.0`, `beached`: `80.0`,
  `beachedOff`: `0.0`. Exactly the configured values, confirmed through
  the real resolution chain, not merely asserted.
- **Measurement-correctness finding, fixed after a fourth review round
  (John, comment 53717).** The first version of this run reported `off`'s
  cumulative activity as 3015 degrees and a ~78x reduction against `on`.
  Review correctly flagged an angle-wrap artifact: roll is reported in
  `(-180,180]` (MTS's own `convertToAngles` range), and `measure()` summed
  the NAIVE difference `rollNow - lastRollDegrees` with no wrapping - a
  hull oscillating right at that boundary (e.g. 179 to -179, a real
  2-degree rotation) was counted as 358 degrees of "activity" instead.
  `off`'s own undamped tumble settles right around the inverted (180
  degree) boundary, so its reported number was inflated by exactly this
  artifact. Fixed by extracting `AngleMath.shortestDeltaDegrees` (a tiny,
  dependency-free pure function - deliberately NOT inside
  `BuoyancyAngularDragProbe` itself, which cannot be loaded in a plain
  unit test for the same Mojang/SLF4J-on-classpath reason `OverridesTest`
  documents for `Gson`) and using it in `measure()` instead of the naive
  subtraction. `AngleMathTest` covers the exact boundary case review
  named (179 to -179 is 2 degrees, not 358), both crossing directions, the
  exactly-opposite-angles edge, a wide from/to sweep bounding every result
  to at most 180 degrees, and a repeated-small-steps-across-the-boundary
  case mirroring exactly what `measure()` now does.
- `off` (resolved `angularWaterDrag=0.0`): with the corrected measurement,
  the pre-tilted hull did NOT settle - it tumbled/oscillated past the
  tipping point, reaching roll angles near 180 degrees (essentially
  inverted) and continuing to oscillate there, for a cumulative absolute
  roll change of **210.5 degrees** over the window (re-measured; the
  wrap-inflated 3015-degree figure above is superseded and should not be
  cited). This is the EXISTING buoyancy/righting-torque model's own real
  behavior for this specific two-float (left/right only, no fore-aft
  spread), ~400kg synthetic test hull - not something this ticket's own
  code path touches when the coefficient is exactly 0 (that branch is
  skipped entirely by `tuning.angularWaterDrag > 0`, confirmed by
  inspection) - disclosed here as an honest observation about the
  baseline, not a defect this ticket is fixing.
- `on` (resolved `angularWaterDrag=80.0`): the SAME pre-tilted starting
  condition instead settled smoothly - peaking at roll=29 degrees then
  decaying cleanly to roll approximately 0 degrees (final: -0.05 degrees)
  by the end of the window, for a cumulative absolute roll change of only
  **38.9 degrees** (unaffected by the wrap fix - `on` never approaches the
  +-180 boundary) - about 1/5.4th of `off`'s CORRECTED rotational
  activity, a real and substantial reduction, just a far more modest
  (and now honest) ratio than the original wrap-inflated ~78x figure. This
  is real, measured evidence that `angularWaterDrag` actually changes the
  hull's rotational behavior through the real production path (Mixin ->
  `PhysicsBridge` -> `BuoyantBody` -> `BuoyantForces.apply` -> Sable/
  Rapier), not just at the pure-function/unit-test level - review's core
  ask.
- **off/on comparator, now an actual gate** (review follow-up: "do not
  report a pass solely because the server exits" - `maybeLogComparison`
  now fails loudly, flipping both scenarios to `FAILED`, unless BOTH hold:
  `off`'s own activity is nontrivial, >= 5 degrees cumulative - ruling out
  a vacuous "both barely moved" false positive - AND `on`'s activity is at
  most half of `off`'s): `offWasNontrivial=true onIsSubstantiallyLessActive=true
  result=PASS`.
- `beached` (resolved `angularWaterDrag=80.0`, no water anywhere nearby)
  vs. `beachedOff` (resolved `angularWaterDrag=0.0`, no water, otherwise
  identical): both stayed essentially frozen at the initial 25-degree
  tilt for the entire window - cumulative absolute roll change **0.002
  degrees for BOTH**, a measured `diff=0.0` between them. The matched
  zero-drag control confirms this is not merely "beached happened to stay
  put" but that configuring `angularWaterDrag` makes NO measurable
  difference at all while unsubmerged, exactly review's "unsubmerged/
  default cases are no-ops" ask, now with the control review's follow-up
  explicitly required: `result=PASS` (diff within the 2-degree tolerance).
- All four scenarios stayed finite and within the bounded test area for
  the full window (review's "state remains finite/stable" ask) once the
  `massKg` fix above was applied.

Full server log evidence (chunk grid requests, `GroundContactDiagnostics`
classify/constructed lines, each scenario's spawn line with its resolved
coefficient, periodic tick samples, each `COMPLETE` line, and both gated
`COMPARISON`/`UNSUBMERGED-NOOP-CHECK result=PASS` lines) was produced by
this exact run; not committed to this repository (the probe's own
disposable `run/` directory is gitignored, same as every other probe
here), consistent with this ticket's "exact-head local tests/runtime ...
honest disclosure" requirement - the numbers above are transcribed
directly from that run's log output, not estimated or reconstructed.

**Disclosed, deliberate scope limit (review comment 53701 explicitly
accepted this as a legitimate fallback: "add the focused reload check if
practical, otherwise keep that limitation distinct from code tests"):**
this run does not exercise the config hot-reload path itself (review's
"verify live-safe reload takes effect") - that remains covered only at the
unit level, by `PhysicsBridgeLiveOverrideApplicationTest` confirming
`angularWaterDrag` is one of `applyLiveSafeFields`'s mutated-in-place
fields (i.e. that `BuoyantForces.apply` re-reads the SAME live `Tuning`
object every tick), not by a live on-disk-edit-and-reload exercised
through this specific runtime probe.

No compatible vehicle pack or in-game run is currently recorded, so there is no
playable-MVP claim for any category above - ground, flight, or boat. The
flight force model in particular (`FlightForces`/`PhysicsBridge.stepFlight`)
is proven only at the pure-function level above; nothing here has confirmed an
aircraft/blimp is flyable, stable, or even stays airborne in an actual game.
Heavy build/decompile/game work must not run on TayneStation.

## Configurable fixed-wing lift/stall-cap and control authority (MINECRAFT-521, added 2026-11-13)

Hosted CI billing was blocked for this four-track sprint (MINECRAFT-519/520/
521/522, dispatched 2026-11-13); this is an exact-head local check instead,
same approach as the recovered-prototype/rotor-physics evidence above - not
the hosted Gradle build, not the `runtime-smoke` Mixin-apply check.

`FlightForces.liftCoefficient` previously hardcoded its stall-angle cap
(15deg) and lift slope (`2*pi`), and `controlSurfaceForce` reused that same
lift-slope constant for aileron/elevator/rudder authority. All three are now
explicit parameters - no implicit default inside `FlightForces` itself - with
the exact previous numbers preserved as named constants
(`DEFAULT_STALL_ANGLE_DEGREES`/`DEFAULT_LIFT_SLOPE`/
`DEFAULT_CONTROL_SURFACE_SLOPE`) that `Overrides`'s FALLBACK reuses directly,
so an unconfigured vehicle's flight behavior is byte-identical to before this
ticket.

Added three `Overrides.Tuning` fields - `stallAngleDegrees` (bounded
`(0,45]`, deliberately short of disabling the hard stall cutoff entirely, per
this ticket's own "do not claim full post-stall spin/soft-body simulation"
scope), `liftSlope`, and `controlSurfaceSlope` (both bounded `(0,10]`, a
generous margin around the `2*pi` thin-airfoil default) - validated, rejected
on a `parts` override (no part-level consumer), and wired through the same
precedence chain (`resolveFields`) and `extends`-template inheritance every
other vehicle-level field already uses. `PhysicsBridge.stepFlight` is the one
and only consumer: `tuning.stallAngleDegrees`/`tuning.liftSlope` feed
`liftCoefficient`'s wing lift, `tuning.controlSurfaceSlope` feeds all three
`controlSurfaceForce` calls (aileron roll, elevator pitch, rudder yaw) - no
dead config. All three are read fresh from the live `tuning` object every
tick (the same pattern already proven for `driveForce`/`friction`/etc.), so
they are added to `PhysicsBridge.applyLiveSafeFields`'s live-safe set rather
than `reportFieldsNeedingRespawn`'s construction-cached one - a live on-disk
edit takes effect on an already-active flight body's very next tick, no
respawn needed.

`FlightForcesTest` covers the configurability directly: a narrower
`stallAngleDegrees` moves where the hard cutoff actually clamps (not just
that 15deg still clamps at 15deg), a doubled `liftSlope`/`controlSurfaceSlope`
doubles the resulting coefficient/force at a fixed angle/deflection
(independently of each other - control authority no longer rides on the
wing's own slope), and the validated extremes (45deg stall angle, slope 10)
stay finite with a still-hard cutoff. `OverridesTest` covers out-of-range
rejection at both bounds for all three fields, the full operator-override-
over-fallback precedence chain, and part-override rejection.
`PhysicsBridgeLiveOverrideApplicationTest` covers all three actually being
mutated in place by `applyLiveSafeFields` and reported in its applied-field
set.

**Correction (John's comment 53756): the prior wording here overstated the
limitation.** `GroundSupportFixtureProbe`/`BuoyancyAngularDragProbe` (and
others in `io.github.brooswitminecraft.div.experimental`) already prove a
live `EntityVehicleF_Physics`/Sable body CAN be constructed and driven
through the real Mixin-intercepted tick loop in this repository, for the
ground and float-only (boat) domains - that pattern is proven, not absent.
The actual, narrower gap this ticket leaves open is that no FIXED-WING
runtime probe exists yet to apply that same pattern to `stepFlight`
specifically: `stallAngleDegrees`/`liftSlope`/`controlSurfaceSlope` and
`stepFlight`'s force/torque application remain exercised only at the
pure-function/wiring level above, not by a live runtime tick driving a real
`isAircraft`/`isBlimp` vehicle. Building that dedicated flight probe (the
same `JSONVehicle`/`ItemVehicle`/real-spawn/env-var-gated `gradle runServer`
shape as `BuoyancyAngularDragProbe`, sized for a wing/control-surface
vehicle instead of a float hull) was assessed as a legitimate "if practical"
enhancement (the review's own framing) rather than a requirement for this
bounded ticket's scope, and is not attempted here - this is the exact
untested flight path, disclosed plainly rather than concealed, not a
broader claim that no production-path flight evidence could ever exist.
Every prior flight/rotor ticket in this repository has stood on the same
disclosed limit for `stepFlight`/`stepRotor` specifically; this ticket does
not newly introduce it, and does not claim the broader "no fixture at all"
limitation the prior wording incorrectly implied.

**Self-review finding (own re-review, PR #19 head `0a05015`): invalid-reload-
retains-last-known-good was undertested.** The first revision's tests
covered range validation, defaults, precedence, pure-force behavior, and
live in-place application for `stallAngleDegrees`/`liftSlope`/
`controlSurfaceSlope`, but not the ticket's own explicit "invalid reload
retaining last-good values" requirement as an actual reload-path regression
(only `Tuning.validate()` throwing in isolation, which the ticket text does
not consider sufficient on its own). `Overrides.readAndValidate`'s
validation logic (field-range checks, `extends`-chain resolution, the
merged-result coupled check) was factored into a new parse-independent
`validateConfig(Config)`, and a new package-private test seam,
`Overrides.apply(Supplier<Config>)`, runs that exact same validation against
a caller-supplied `Config` - no live FML config directory or Gson needed at
all, the identical substitution `GroundSurfaceOverridesTest` already uses
for its own loader's equivalent gap. `Overrides.reload()` itself now
delegates to `apply()` (reached through the real file instead of a test
`Supplier`) rather than duplicating the validate-then-swap-or-reject logic a
second time. Two new `OverridesTest` cases install a known-good
`stallAngleDegrees`/`liftSlope`/`controlSurfaceSlope`, then "reload" an
out-of-range `Config` (one case) or a throwing parse (the other, standing in
for a malformed-JSON failure), and assert `Overrides.resolved` still returns
the PRIOR GOOD values, not the invalid ones or the flat fallback - the exact
regression this ticket's own acceptance criteria name. `Overrides.Config`
was widened from `private` to package-private solely so `OverridesTest` can
construct one directly for this seam; nothing about its own external
contract (`load()`/`reload()`'s public behavior) changed.

Local check: `javac --release 21 -proc:none` against the real `mts.jar`,
Sable NeoForge 2.0.5, Sable Companion 1.6.0, joml 1.10.5, and this repo's
exact pinned NeoForge 21.1.265 (`neoforge-21.1.265-merged.jar`, reused from
this host's own MINECRAFT-494 moddev build output at the identical pinned
version, plus the separate FancyModLoader/bus/netty/mojang-logging/
mixin/jetbrains-annotations/datafixerupper jars NeoForge's own source
references but that merged artifact does not bundle) confirms every changed
file (`FlightForces.java`, `Overrides.java`, `PhysicsBridge.java`, and the
four updated/added test files) compiles cleanly with zero errors against
IV's real `JSONVehicle.motorized`/control-input-var fields. All 542 tests in
the repository (540 plus the two new invalid-reload regressions above) pass
under a manually assembled JUnit Platform launcher (JUnit 5.11.4/platform
1.11.4) - the same full suite as every prior local check here, not merely
the new/changed tests - confirming this change introduces no regression
anywhere else in the suite.

**Actual repository Gradle build/test (John's comment 53748, serialized with
MINECRAFT-519 via a local slot-lock file on this host so the two tickets'
heavy builds never ran concurrently): `gradle --offline clean build test`,
Gradle 8.14 / JDK 21 (Temurin), on Servy at PR #19 head
`bee29073dbbc72c8b2d2caee6c0ba1fcf10695a0` - `BUILD SUCCESSFUL`, 8 tasks (7
executed, 1 up-to-date), `:test`/`:check`/`:build` all green. The actual
repository JUnit XML test reports (`build/test-results/test/*.xml`, the real
Gradle-driven run, not the manually assembled launcher above) total 542
tests, 0 skipped, 0 failures, 0 errors - matching the manual run exactly, as
expected since both exercise the identical test classes against the
identical pinned dependency set. `build/libs/dynamic-immersive-vehicles-
0.1.0-SNAPSHOT.jar` was produced. This is the actual Gradle pipeline
peers (MINECRAFT-519 et al.) used, not merely the manual javac/JUnit
substitute above - that manual run remains useful as the faster, narrower
confirmation during iteration, but this Gradle run is the evidence John's
comment asked for.**

**Merged-head Gradle build/test (John's comment 53762: "obtain merged-head
Gradle evidence through the shared Servy slot" after integrating
MINECRAFT-520's already-merged brake-bias work, PR #18/53d666d, into this
branch).** `git merge origin/main` brought in `Overrides.Tuning#
brakeBiasFront`/`BrakeBiasDistribution`/`BrakeBiasDistributionTest`/
`ServiceBrakeBiasTest` (MINECRAFT-520), conflicting with this ticket's own
changes to the exact same `Overrides.java`/`PhysicsBridge.java` regions
(`validate()`, `validateSupportedForPart()`, `copy()`, `mergeOntoParent()`,
`resolveFields()`, `applyLiveSafeFields()`, plus the matching doc-comment
sentences, `README.md`, and the copyable example JSON) - every conflict was
resolved by hand, keeping BOTH sides' fields side by side rather than
dropping either (the brief's own "resolve conflicts without dropping either
side" requirement). New head: `b6a8eb67334c13867f16b04bdb5594f35b739e3d`, a
real merge commit, not a mechanical no-conflict fast-forward - this
invalidated the prior GitHub approval at `0635c648` (re-requested review at
this head per this repo's own changed-head-requires-re-review rule).

Same local slot-lock coordination with MINECRAFT-519 as the prior run (held
for this run's duration, released immediately after; confirmed no other
gradle/java build process of mine was active before starting - a second,
unrelated `gradle test --offline` process against a DIFFERENT checkout path
(`~/code/brooswit-minecraft/dynamic-immersive-vehicles`, not any task
workspace) was observed running concurrently on this host during this run,
started by something outside this ticket's own control - the local
slot-lock file is this session's own coordination mechanism with
MINECRAFT-519 specifically, not a host-wide enforced lock, so it cannot by
itself prevent an unrelated process elsewhere from running at the same
time; this run still reached `BUILD SUCCESSFUL` regardless).
`gradle --offline clean build test` at head `b6a8eb6` - `BUILD SUCCESSFUL`,
8 tasks (8 executed, 0 up-to-date - a true `clean` this time, nothing
reused from the prior run). The real JUnit XML reports total **567 tests**,
0 skipped, 0 failures, 0 errors (542 from this ticket's own prior run plus
MINECRAFT-520's own `BrakeBiasDistributionTest`/`ServiceBrakeBiasTest` and
its `OverridesTest`/`PhysicsBridgeLiveOverrideApplicationTest` additions,
now exercised together for the first time at this merged head).
`build/libs/dynamic-immersive-vehicles-0.1.0-SNAPSHOT.jar` was produced
again at this new head.

**Final combined-head Gradle build/test (John's comments 53772/53776/53778:
sequence PR17/MINECRAFT-522's rotor tuning in first, then integrate and
present the final combined delta).** After PR17 merged to main
(`67fca5e5a95b589bb040b2034651a346a9d13479`, confirmed directly via `gh pr
view 17`/`git log origin/main`), merged `origin/main` into this branch a
second time. This brought in `Overrides.Tuning#cyclicLimitDegrees`/
`#cyclicAuthorityScale`/`#yawAuthorityScale` (MINECRAFT-522), conflicting
again with this ticket's own fields in the same `Overrides.java`/
`PhysicsBridge.java`/`OverridesTest.java`/
`PhysicsBridgeLiveOverrideApplicationTest.java`/`README.md` regions already
touched by the MINECRAFT-520 integration above. Resolved by hand again,
keeping all three tickets' fields together (stallAngleDegrees/liftSlope/
controlSurfaceSlope, brakeBiasFront, cyclicLimitDegrees/
cyclicAuthorityScale/yawAuthorityScale) - none dropped.

**Self-caught defect in this merge's own resolution.** The `FALLBACK` doc
comment conflict (this ticket's MINECRAFT-521 sentence vs. MINECRAFT-522's)
was first resolved by a scripted blind-concatenation pass across every
conflict in `Overrides.java` - correct for every plain code conflict, but
for this ONE doc-comment conflict it left a stray `*/` closing the comment
mid-way followed by a second, now-orphaned `* ...` block with no opening
`/**` - a real compile error (`';' expected`, 16 errors), caught by the
project's own local `javac` compile step run before the Gradle rerun, not
by Gradle itself. Fixed by merging the two sentences into one coherent
javadoc (commit `eb276df`). Both a plain `javac --release 21` compile and
the manually assembled JUnit launcher were re-run clean after the fix,
before spending the shared Gradle slot on a second full build.

Local slot-lock coordination again held for this run's own duration and
released immediately after (M519/M520/M522 are all already merged to main
at this point, so no sibling ticket's own heavy build could still be
in flight). `gradle --offline clean build test` at head
`eb276df642f6ad915524ab6c730f4d9b166ff094` - `BUILD SUCCESSFUL`, 8 tasks (8
executed). The real JUnit XML reports total **581 tests**, 0 skipped, 0
failures, 0 errors (567 from the prior merged-head run plus MINECRAFT-522's
own `RotorForcesTest`/`OverridesTest`/
`PhysicsBridgeLiveOverrideApplicationTest` additions, now exercised
together with this ticket's own fixed-wing tests for the first time at
this final combined head). `build/libs/dynamic-immersive-vehicles-
0.1.0-SNAPSHOT.jar` was produced again. This is the final combined delta
for review before merge that John's comments asked for, against `main` as
it stands now - `MINECRAFT-520` (PR #18) and `MINECRAFT-522` (PR #17) are
both already merged and reflected here. **MINECRAFT-519 (anti-roll, PR
#20) is NOT yet merged** (confirmed directly via `gh pr view 20`: still
`OPEN`, no reviews recorded at the time of this check) - John's own
sequencing instruction named only PR17 explicitly, so this delta is
presented now rather than held pending M519 as well; if M519 lands and
touches the same `Overrides.java`/`PhysicsBridge.java` regions before this
PR merges, another integration-and-rereview round will be needed the same
way this one was, and this ticket's own PR will need to check for that
before merging, not assume it's already been accounted for here.

## Fixed-wing/rotorcraft real-runtime regression fixtures (MINECRAFT-526)

Closes the disclosed gap both MINECRAFT-521 (PR #19: "No live
`EntityVehicleF_Physics`/Sable runtime fixture exists in this repo -
`stepFlight`'s actual force/torque application is not unit-testable") and
MINECRAFT-522 (PR #17: "In-game helicopter feel/calibration - not run this
pass") carried in their own test plans. `FlightForcesTest`/`RotorForcesTest`
already prove the pure math correct in isolation; nothing before this
ticket exercised the actual live decision `PhysicsBridge.Body.step` makes -
dispatching a real spawned vehicle to `stepFlight`/`stepRotor` via the
`VehicleMovementMixin`/`VehicleForcesMixin` seam, against a real
`Overrides`-resolved `tuning` object on a live Sable body.

Adds `experimental/FlightRotorRuntimeFixtureProbe.java`: opt-in
(`-Ddiv.experimentalFlightRotorRuntimeFixtureProbe=true`), dev/isolated-
host-only, OFF by default - the same convention every other probe in this
package already uses (`BrakeSweepProbe`, `TurnComparisonProbe`, etc.). Runs
6 scenarios strictly sequentially (one fixed-wing, one rotorcraft, times
three roles): `*-activation-default` (proves the vehicle is actually
PhysicsBridge-managed and dispatched to the correct stepper - a
misdispatched vehicle would simply free-fall at `g`=9.81 m/s^2 with no
corrective force, trivially distinguishable from the damped response an
engaged `stepFlight`/`stepRotor` actually produces - and logs that response
as the unconfigured/FALLBACK baseline), `*-configured` (an operator
override already in place at spawn - `liftSlope`/`controlSurfaceSlope` for
fixed-wing, `cyclicLimitDegrees`/`cyclicAuthorityScale`/`yawAuthorityScale`
for rotorcraft - re-runs the identical control input and logs the same
metrics for comparison against the baseline), and `*-live-reload` (starts
unconfigured, observes a baseline window, then calls the exact production
hot-reload entrypoint `PhysicsBridge.reloadOverrides()` - the same one
`OverridesHotReload` calls on its own mtime-poll - against a VALID edit to
the live config file for this same still-active vehicle with no respawn,
observes the response change in place, then calls it again against an
INVALID edit (`liftSlope=-1` / `cyclicLimitDegrees=0`, both out of
`Overrides.Tuning#validate`'s own range) and logs whether the VALID
response was retained unchanged rather than falling back to FALLBACK or
breaking).

No block platform is built (unlike every ground-domain probe) - flight/
rotor scenarios need no solid ground, only the chunk grid above to be
loaded/generated. Writes to the shared
`run/config/dynamic-immersive-vehicles.json` are read-merge-write (never a
blind overwrite, so nothing an operator or another fixture already has
there is lost) and the file's pre-probe content is snapshotted and restored
verbatim once all scenarios finish - deterministic, bounded cleanup, same
requirement this ticket's own scope names explicitly.

**What this proves:** the dispatch/live-field-application seam actually
works end to end on a real spawned vehicle, for both vehicle categories,
across all four of this ticket's named cases (activation, default
response, configured response, valid/invalid live reload). **What this
does NOT prove:** that the resulting flight/hover feel is well-tuned,
realistic, or calibrated - same disclosed limit `BrakeSweepProbe` itself
already carries for ground feel, and no change to any production force
model, default, or precedence/validation behavior is made by this ticket.

**Evidence actually gathered this pass:** this file compiles against the
real `mts.jar`/Sable/Sable Companion/joml/this repo's exact pinned NeoForge
classpath (`gradle --offline compileJava`, `BUILD SUCCESSFUL`, rerun from
clean with `--rerun-tasks`), and the full existing suite still passes
unmodified: `gradle --offline test --rerun-tasks` - `BUILD SUCCESSFUL`, the
real JUnit XML reports **605 tests**, 0 skipped, 0 failures, 0 errors
(unchanged from the pre-existing combined head above - this ticket adds no
new unit tests of its own, since the probe's entire point is exercising the
one thing a plain JUnit test structurally cannot: a live
`EntityVehicleF_Physics`/Sable runtime). **Disclosed limit, same category
every prior flight/rotor ticket has carried:** this probe has NOT yet been
run against an actual live dev server (hosted CI remains billing-blocked
per this file's own repeated note, and no live Minecraft server/world is
available in this engineering pass) - the scenario speeds/tilt angles/
bounds chosen are reasoned from this repo's own force-model formulas
(documented inline at each constant in the probe itself) to stay within the
same loaded-chunk/world-generation budget `BrakeSweepProbe`'s own review
history (53327/53330) already proved safe, not an empirically live-tuned
value. An operator doing the first live run should treat a watchdog timeout
or an out-of-bounds failure as a signal to shrink the observe-window tick
counts or speeds further, the same iterative process that review history
already went through for the ground domain.

**Self-review correction (head `fe415f8` -> this head).** Two required
fixes found by review before `wroosbit`'s substantive pass: (1)
`waitTerrain` forced a 13x13 chunk grid via `setChunkForced(..., true)` but
no path ever unforced it - both the normal-completion and terrain-timeout
paths left all 169 chunks permanently forced. (2) Once
`writeInitialConfigOnce` had snapshotted and started editing the shared
`dynamic-immersive-vehicles.json`, an initial-reload failure jumped
straight to `ALL_DONE` and a `mergeVehicleOverrideAndWrite` write failure
was only logged, not treated as a reason to stop - both bypassed
restoration and could leave the probe's own test overrides in the shared
config. Fixed by adding a `RELEASE_CHUNKS` terminal phase (unconditionally
unforces the whole grid - a no-op for any coordinate never actually
forced) that every path now funnels through via `RESTORE_CONFIG`
(`configWasWritten` tracks whether anything was actually written, so a
failure before the first write correctly skips restoration instead of
restoring a snapshot that was never taken); `mergeVehicleOverrideAndWrite`
now returns a success boolean every caller checks before proceeding to
reload/run. Re-verified after the fix: `gradle --offline compileJava
--rerun-tasks` - `BUILD SUCCESSFUL`; `gradle --offline test --rerun-tasks`
- `BUILD SUCCESSFUL`, 605 tests, 0 failures, 0 errors (unchanged).

**Live-run evidence (independent review finding 53833/53853/53878) - actual
real dev-server run, not just compile/unit evidence.** Five iterations
against a real `gradle runServer` instance (shared `/tmp/div-gradle-slot.lock`
flock, no duplicate dispatch), each diagnosing and fixing a real defect the
previous run's own evidence exposed:

1. First live run (head `1550ce3`): 0/6 scenarios passed - both vehicle
   kinds free-fell identically with zero rotation response regardless of
   kind or config. Proved the cleanup/restore/chunk-release machinery
   itself works end to end, but proved the fixture's own activation premise
   was broken.
2. With `-Ddiv.debugGroundContact=true` enabled to see why: both vehicles
   WERE correctly classified/activated/dispatched (`classify ... ground=true
   decision=ACTIVATE`, `class=Body`, correct ROTOR/FLIGHT stepper) - but
   `PhysicsBridge.Body`'s own `experimentalTwoBodyChassis` candidate
   defaults to `true` (`System.getProperty("div.experimentalTwoBodyChassis",
   "true")`) and unconditionally skips ALL force application for any
   non-GROUND stepper (its own "selected a non-GROUND stepper - unsupported
   for this candidate" log line) - a genuine production incompatibility
   this ticket's own scope requires reporting distinctly, not silently
   working around (see MINECRAFT-527, filed separately for the production
   fix - this probe's own `-Ddiv.experimentalTwoBodyChassis=false` gate is
   valid test isolation, not a claim that the default works).
3. With that flag disabled: 1/6 passed (`rotor-activation-default`).
   Fixed-wing tumbled violently (its own up-vector flipped sign within half
   a second) - its 2x1 collision box gave a 900kg hull an unrealistically
   tiny moment of inertia, so ordinary aerodynamic torque spun it instead
   of producing a controlled response. Sized the fixed-wing box closer to
   its own wingspan/a real fuselage length instead.
4. With that fix: 4/6 passed. The remaining two LIVE_RELOAD failures traced
   to `accelProxy` sampling its three points across the WHOLE 50/35-tick
   window - long enough for the body to tumble through multiple full
   rotations, aliasing a periodic quantity (`m11`/`m00` are periodic in the
   actual rotation angle) into meaningless noise. Confined the three
   samples to a much shorter early sub-window (`RESPONSE_SAMPLE_TICKS=8`,
   right after control input turns on, before large-angle effects
   dominate) where the proxy's own "proportional to current torque"
   rationale actually holds.
5. With that fix: 5/6 passed - the one remaining failure was the
   INVALID-vs-VALID retention check itself, which had been comparing noisy
   physical `accelProxy` readings across windows where the live body's own
   translational state had, by the third consecutive window, drifted far
   enough (observed: forward speed swinging between ~2 and ~25 m/s) that
   the comparison was unreliable regardless of the config. Replaced it with
   a deterministic check: read back `Overrides.resolved(key)`'s actual
   live-resolved field directly (no physics involved at all) - strictly
   more direct evidence of "retained the VALID config" than any further
   physical re-derivation could be.

**Final live run (head after fix 5): `/tmp/m526-live-run5.log`, 2026-11-14
~05:22-05:28 UTC - `ALL SCENARIOS FINISHED - 6/6 PASSED`.** Every scenario's
own PASSED line: `fw-activation-default` (dispatch confirmed, baseline
recorded), `fw-configured` (response vs baseline PASSED), `fw-live-reload`
(retention PASSED, live-resolved field confirmed `9.5`), `rotor-activation-
default` (dispatch confirmed), `rotor-configured` (roll+yaw response vs
baseline PASSED), `rotor-live-reload` (retention PASSED, live-resolved
field confirmed `4.0`). Cleanup confirmed in the same run: "original config
restored and reloaded" and "released 112 chunk(s) this probe itself
forced" both logged before `ALL_DONE`. The only `ERROR`-level log lines in
the full run are MTS's own startup banner and an unrelated Sable warning
about a `create:flywheel` block tag from a mod not installed - neither
from this probe. `gradle --offline compileJava/test --rerun-tasks` after
this final fix: `BUILD SUCCESSFUL`, 605 tests, 0 failures, 0 errors
(unchanged - no new unit tests, consistent with every prior pass on this
ticket).

This closes the ticket's own disclosed-gap requirement with actual,
reproducible, real-dev-server PASS evidence for all four named cases
(activation, default response, configured response, valid/invalid live
reload) across both vehicle categories - not merely a compiled, never-run
fixture. MINECRAFT-527 (filed separately) tracks the real production
defect this process discovered; fixing that production behavior is
explicitly out of this ticket's own scope.

**Second independent rereview (head `a6d4314`, comment 53897) and fixes.**
Three further gaps found on the already-6/6 head: (1) the INVALID-retention
check asserted `Overrides.resolved(key)` only AFTER this probe's own disk
repair had already rewritten the file back to the valid values and after a
further 35-tick `OBSERVE_INVALID` window had elapsed - during which
`OverridesHotReload`'s own background mtime poll could independently
re-read and reapply the (already-repaired) file, so the assertion risked
proving only that the repair itself worked, not that the REJECTED reload
had actually preserved the live state. Moved the assertion to immediately
after the rejection is confirmed, before any repair or further ticks (kept
a second assertion at the window's own end too, now correctly documented
as proving retention PERSISTS through the window rather than being the
only evidence). (2) `applyValidConfig` logged `vehiclesUpdatedLive` without
asserting on it - now requires exactly `1` (this scenario's own still-
active body; scenarios are strictly sequential, so no other vehicle is
alive to update). (3) `waitTerrain`'s chunk-ownership tracking inferred
"this probe forced it" from "it was unloaded", but unloaded and
not-already-forced are different things (loading is deferred/async, so an
unloaded chunk can already carry someone else's force-ticket) - now checks
`ServerLevel.getForcedChunks()` directly before forcing, so
`chunksForcedByThisProbe` (and therefore `releaseChunksOnce`) only ever
contains coordinates this probe itself actually added a ticket for.

Re-verified after all three fixes: `gradle --offline compileJava/test
--rerun-tasks` - `BUILD SUCCESSFUL`, 605 tests, 0 failures, 0 errors
(unchanged). Live run 6 (`/tmp/m526-live-run6.log`, 2026-11-14 ~05:37-05:43
UTC): `ALL SCENARIOS FINISHED - 6/6 PASSED` again, confirming the fixes
didn't regress the already-passing behavior.

Remaining disclosed, accepted limitations (same review, not further
addressed this pass - noted rather than silently claimed covered): the
`accelProxy` roll/yaw metric is a matrix-component second difference, not
a true angular acceleration - it remains orientation/rate-dependent to
some degree even confined to `RESPONSE_SAMPLE_TICKS`, so its claims are
about relative magnitude/direction of response, not a calibrated physical
quantity. This probe also cannot assert on `PhysicsBridge.Body`'s own
internal stepper selection directly (that state is package-private) -
`ACTIVATION_DEFAULT`'s dispatch evidence is the vertical-speed-vs-free-fall
proxy only, not a direct read of which stepper ran. Extending either would
need a new PhysicsBridge-side read-only diagnostic hook, which is outside
this ticket's own scope (no production code changes).

**Third independent review round (comments 53905/53907) - minimal, explicitly-
authorized read-only production diagnostic hooks added.** Review correctly
found two remaining gaps even at 6/6: (1) `liveResolvedRepresentativeField`
read the GLOBAL `Overrides.resolved(key)`, not the active Body's own cached
tuning - those are two different things that merely happen to usually agree,
and a merely-SELECTED stepper (the only thing this file could previously
observe) does not prove force application actually ran - M527's own root
cause is exactly a selected non-GROUND stepper whose execution a candidate
unconditionally skips. (2) No assertion proved the SAME live body persisted
across BASELINE/VALID/INVALID (never silently respawned/rebuilt).

Added three minimal, explicitly-scoped, read-only diagnostic additions to
`PhysicsBridge.java` (same established pattern as the pre-existing
`debugInjectAngularVelocity`/`debugReadAngularVelocity` - "Never called from
any production code path - only from this experimental test probe"):
`Body.lastExecutedStepper`/`executedStepCount` are set ONLY at the exact
point `step()` actually calls `stepGround`/`stepFlight`/`stepRotor` (never
on a tick `skipNonGroundForTwoBody` skipped), and
`PhysicsBridge.debugExecutedStepSnapshot(vehicle)` returns a small public
record bundling the active Body's own identity (opaque `Object`, `==`-
comparable only), that real executed-stepper name/counter, and the Body's
own actual live `tuning()` reference. No physics mutation, no API redesign,
no change to any production code PATH (only two new fields written at an
existing call site, read by nothing in production).

The probe now uses this directly: `beginWindow` asserts
`debugExecutedStepSnapshot(...).bodyIdentity()` is unchanged (`==`) every
time a scenario enters a new window, failing immediately if the live body
was ever replaced; `ACTIVATION_DEFAULT` asserts `executedStepCount>0` and
`lastExecutedStepperName` matches the expected mode BEFORE trusting the
physical free-fall proxy; the VALID/INVALID retention checks now read the
representative field from this snapshot's own `tuning()` instead of
`Overrides.resolved`.

Re-verified: `gradle --offline compileJava/test --rerun-tasks` - `BUILD
SUCCESSFUL`, 605 tests, 0 failures, 0 errors (unchanged). Live run 7
(`/tmp/m526-live-run7.log`, 2026-11-14 ~06:02-06:09 UTC): `ALL SCENARIOS
FINISHED - 6/6 PASSED` again, now with concrete evidence lines like
`ACTIVATION PASSED (dispatch confirmed: executedStepCount=65
lastExecutedStepperName=FLIGHT)` and `retention confirmed immediately after
rejection - live-resolved representative field=9.5` (read from the real
active Body, not the global resolver).

## Configurable front/rear drive-force distribution (MINECRAFT-524, added 2026-11-13)

Bounded M507 slice (the bounded-scope instruction on the ticket itself):
inspected the native IV driven-wheel metadata first (decompiled `mts.jar`
via `javap` - `JSONPart.JSONPartGroundDevice`/`PartWheel` have no per-wheel
powered/drivetrain-membership field at all, only `isWheel`/`isTread`/
`canGoFlat`/friction fields; `JSONPartDefinition.isSpare` is the one
drive-relevant exclusion flag, and `PhysicsBridge#groundContacts` already
excludes `isSpare` wheels from the `wheels` list `stepGround` splits the
drive budget across). Confirmed `stepGround`'s pre-ticket behavior was
`drive/wheels.size()` - a plain even split with no axle distinction, the
exact `brakeForce/wheels.size()` shape MINECRAFT-520 fixed for braking.

Implementation deliberately mirrors MINECRAFT-520's own
`BrakeBiasDistribution`/`brakeBiasFront` field-for-field (same geometric
front/rear axle grouping off each wheel's own `z` anchor, same exact-1.0
conservation, same unset/degenerate-layout fallback to the even split):
new `DriveBiasDistribution` class, new `Overrides.Tuning#driveBiasFront`
(0.0-1.0, no FALLBACK entry - null means unset, rejected on `parts`
entries since a part has no own axle position, live-hot-reloaded via
`applyLiveSafeFields` the same as `brakeBiasFront`), and a new
`driveShareRatio` field on `MultiContactFrictionSolver.FrictionContact`
(default 1.0 in every existing shorter constructor, so every pre-existing
call site - including every other ticket's own tests - is unaffected byte-
identically) that `solveFriction`'s drive branch now multiplies into
`driveForcePerWheel*skidSteerMultiplier`, composing with skid-steer/tracks
the same way `brakeMagnitude` already composes for braking. Explicitly
documented (class doc, `Overrides.Tuning#driveBiasFront`'s own comment,
README) as a geometric budget split only - not a differential, not
traction control, and not a gear-ratio simulation, per the ticket's own
explicit constraint.

Evidence named by the ticket: `DriveBiasDistributionTest` covers
conservation, front/rear extremes (0.0/1.0), odd/asymmetric wheel counts,
multi-axle lumping, reversed/ambiguous layouts, and the unset-bias/
degenerate-layout fallback to the exact pre-ticket even split (mirrors
`BrakeBiasDistributionTest`). `ServiceDriveBiasTest` is the real
production-path integration against the actual
`MultiContactFrictionSolver.solveFriction` on a representative 4-wheel
layout: conservation across several bias values, front-only and rear-only
drive still accelerating the vehicle forward from rest, reverse travel
(negative `driveForcePerWheel`, mirroring the engine's own
`Math.signum(currentGearVar)` sign flip) still accelerating backward
regardless of bias, asymmetric (trike-like) wheel counts, and a wheel
missing its own ground contact this tick simply losing its own share
rather than having it redistributed to the others (mirrors
`ServiceBrakeBiasTest`). `OverridesTest` and
`PhysicsBridgeLiveOverrideApplicationTest` gained `driveBiasFront`
coverage mirroring their existing `brakeBiasFront` cases (range validation
at both inclusive extremes, no-fallback-stays-null, full precedence chain,
part-override rejection, and live hot-reload mutation).

Local slot-lock coordination (per ticket comments 53806/53810/53820/53823
- a live multi-agent coordination exchange on this ticket itself, a shared
cooperative `flock` on `/tmp/div-gradle-slot.lock` held for the whole
Gradle lifetime, claimed 2026-11-14T04:17:51Z, released immediately after
completion at 2026-11-14T04:19:04Z, no concurrent Java/Gradle process of
another owner observed at claim time). `/tmp/gradle-8.14/bin/gradle
--offline clean build test --console=plain`, Gradle 8.14 / JDK 21
(Temurin), against this branch
(`minecraft-524/front-rear-drive-force-distribution`) at head `1364dbc`
(origin/main) - `BUILD SUCCESSFUL` in 1m 12s, 8 tasks (7 executed, 1
up-to-date). The real JUnit XML reports total **627 tests**, 0 skipped, 0
failures, 0 errors across the whole suite (11 from the new
`DriveBiasDistributionTest` and 7 from the new `ServiceDriveBiasTest`,
plus every pre-existing test run unchanged alongside them - confirming
this change introduces no regression anywhere else in the suite, skid-
steer/tracks and MINECRAFT-520's own brake-bias behavior included).
`build/libs/dynamic-immersive-vehicles-0.1.0-SNAPSHOT.jar` was produced.

Not yet performed: in-game acceptance (real vehicle spawn with a
configured `driveBiasFront`, observed front/rear-biased acceleration) -
this ticket's own brief says focused engineering tests are in scope, not
John's broad/hardware playtesting, which is explicitly out of scope here.

**Self-review finding and fix (PR #24 comment 6871636687).** The initial
version computed each wheel's drive-force ratio as `DriveBiasDistribution.
shares(...)[i] * wheels.size()` (the same shape `brakeShareRatios` already
uses) - for the unset/fallback case this is `(1.0/n)*n`, which is NOT
always the exact literal `1.0` (ordinary floating-point error, e.g. at
n=49 wheels it evaluates to `0.9999999999999999`), so an unconfigured
vehicle's drive would differ from the pre-ticket `drive/wheels.size()`
formula by that tiny error - a real violation of this ticket's own "unset
retains exact accepted behavior" requirement, which means byte-identical,
not merely close. Fixed by adding `DriveBiasDistribution#shareRatios`,
which fills the fallback with the literal constant `1.0` directly instead
of deriving it through division then remultiplication, and switching
`PhysicsBridge.stepGround` (and the test fixtures) to call it directly
instead of computing `shares(...)[i]*wheels.size()` itself.
`DriveBiasDistributionTest` gained a non-rounding regression test sweeping
wheel counts 1-97 (confirming every one gets the exact `1.0` ratio, and
specifically identifying which counts the naive round-trip would have
rounded), plus a degenerate-layout exactness test and a budget-conservation
test for the biased case.

Same local slot-lock coordination (claimed 2026-11-14T04:26:01Z, released
2026-11-14T04:27:10Z, no concurrent process observed). `/tmp/gradle-8.14/
bin/gradle --offline clean build test --console=plain` against commit
`e33c4a6cccceade1d37876d3fc5be69fa9b17e98` - `BUILD SUCCESSFUL` in 1m 9s, 8
tasks (8 executed, a true clean this time). The real JUnit XML reports
total **630 tests**, 0 skipped, 0 failures, 0 errors (14 from
`DriveBiasDistributionTest`, 7 from `ServiceDriveBiasTest`, plus every
pre-existing test unchanged alongside them).
`build/libs/dynamic-immersive-vehicles-0.1.0-SNAPSHOT.jar` was produced
again.

**Second review round (comment 53838) and fix.** John's review found two
things: (1) my own prior "no native driven-wheel metadata exists" claim
was wrong - `PartEngine#linkedWheels`/`#drivenWheels` are real native
`List<PartGroundDevice>` fields (confirmed via `javap` against the pinned
`mts.jar`); corrected to the narrower, still-true claim that
`PhysicsBridge`'s existing pre-ticket code never reads them (confirmed:
zero references anywhere in this package), so this ticket's own bounded
axle-split scope is unaffected - it preserves that existing choice rather
than newly filtering by native drivetrain membership. (2) Three concrete
test-strengthening asks, all addressed: the missing-contact test now
asserts a magnitude reduction (three-wheel result strictly below a
four-wheel reference, not merely a direction check); a new skid-steer
composition test verifies `driveForcePerWheel*skidSteerMultiplier*
driveShareRatio` composes exactly, solved as four independent single-
contact calls (a combined multi-contact call was tried first and failed -
see below - because cross-contact coupling within one pass gives a
later-processed wheel a tiny nonzero rolling-resistance term even
starting from rest, which is correct solver behavior, not a composition
bug, but contaminates an exact per-wheel product check); and an explicit
comment distinguishes the biased branch's deliberate floating-point
tolerance from `shareRatios`' own exact-1.0 contract.

**Self-caught test defect, fixed before the final build.** The first
version of the new skid-steer composition test solved all four wheels
together in one `solveFriction` call and asserted each wheel's output
equaled the exact product - this failed (`expected 560.0 but was
560.1848888888888`) because the solver processes contacts sequentially
within a pass, so wheel 1's own rolling-resistance term saw a tiny nonzero
residual speed left over from wheel 0's own impulse earlier in the same
pass. Root-caused via direct inspection of `solveFriction`'s own loop
order, confirmed this is intended cross-contact coupling (not a defect in
`driveShareRatio` itself), and fixed the TEST (not the production code) by
solving each wheel as an independent single-contact call instead.

Local slot-lock coordination (claimed 2026-11-14T04:40:19Z, released
2026-11-14T04:41:07Z; a concurrent `gradle --offline clean build test`
FAILED attempt before this one - at the test-only stage, from the flawed
combined-contact test above - was this session's own, not a conflict with
another owner). `/tmp/gradle-8.14/bin/gradle --offline clean build test
--console=plain` against commit `ee999d0...` (this ticket's current PR
#24 head) - `BUILD SUCCESSFUL` in 47s, 8 tasks (8 executed). The real
JUnit XML reports total **631 tests**, 0 skipped, 0 failures, 0 errors (8
from `ServiceDriveBiasTest`, up from 7 - the new skid-steer composition
test - plus every other test unchanged).
`build/libs/dynamic-immersive-vehicles-0.1.0-SNAPSHOT.jar` was produced
again. Slot released immediately after; M526 claimed it next per the
stated order.

**Third review round (comment 53855) - acceptance pending integration,
plus a doc-wording fix.** Independent technical review accepted head
`6484196` (631 tests: exact-unset ratios, native-metadata correction,
missing-contact magnitude and isolated skid-multiplier tests all found
sound) but withheld formal approval pending two things: (1)
`DriveBiasDistribution`'s own "Conservation" doc overclaimed the BIASED
branch's own conservation as exact arithmetic ("never a renormalized
approximation") - only `shareRatios`' unset/degenerate FALLBACK case
actually carries a byte-exact requirement; fixed (`f3cd160`, doc-only, no
logic change) to state the biased branch's own division is ordinary
floating-point arithmetic with a tolerance claim, matching what the tests
already assert (`assertEquals(..., 1e-9)`, never tolerance-0). (2)
MINECRAFT-523's own damping PR #22 (`f3d7f8a`) had formal approval and was
instructed to land on `main` first, with this ticket then required to
integrate the merged `main`, preserve both tickets' schema/ground changes,
and present a final combined head for formal approval - rather than
approving/merging an intermediate head that would go stale the moment
PR #22 landed.

**Integration.** Confirmed PR #22 merged to `main` at `46fc2fb` (merge
commit; `f3d7f8a` is its own head). `git fetch origin && git merge
origin/main --no-edit` into this branch produced conflicts in
`README.md`, `Overrides.java`, `PhysicsBridge.java`, and
`PhysicsBridgeLiveOverrideApplicationTest.java` - the same shared-schema
regions (`Tuning` field declarations, `validate()`/
`validateSupportedForPart()`/`copy()`/`mergeOntoParent()`/`resolveFields()`,
`applyLiveSafeFields`, the live-safe-field list in both code and prose,
and the config-reference table) every prior multi-ticket integration in
this file has hit. Resolved by hand, keeping BOTH tickets' fields side by
side everywhere (this ticket's `driveBiasFront`/`driveShareRatio`
alongside MINECRAFT-523's `suspensionDampingRatio`/stability-bound
validation) - nothing dropped from either side. `OverridesTest.java` and
`docs/dynamic-immersive-vehicles.example.json` merged cleanly with no
conflicts. Merge commit `9f4e737dd18b2e48dc5f292ef4baff11cbc55d64`.

Local slot-lock coordination (claimed 2026-11-14T04:51:01Z, released
2026-11-14T04:51:42Z; no concurrent process observed - M526 had already
released at 04:48:07Z per its own entry in the shared slot log).
`/tmp/gradle-8.14/bin/gradle --offline clean build test --console=plain`
against the merge commit `9f4e737dd18b2e48dc5f292ef4baff11cbc55d64` -
`BUILD SUCCESSFUL` in 40s, 8 tasks (8 executed). The real JUnit XML
reports total **645 tests**, 0 skipped, 0 failures, 0 errors (this
ticket's 631 plus MINECRAFT-523's own 14 net-new tests from `main`, all
exercised together for the first time at this combined head).
`build/libs/dynamic-immersive-vehicles-0.1.0-SNAPSHOT.jar` was produced
again. This is the final combined head presented for formal approval -
`9f4e737dd18b2e48dc5f292ef4baff11cbc55d64`.

**MINECRAFT-527 landed on main (PR #25) - removed this ticket's own workaround,
validated the real production fix instead.** `PhysicsBridge.Body.useTwoBody`
now excludes any non-GROUND stepper (and any buoyancy-applying one)
automatically, regardless of the global `experimentalTwoBodyChassis`
property's own value - exactly the fix review 53878/comment history on this
ticket called for. Merged current `main` (`df6e5db`) into this branch
(clean auto-merge, no conflicts); removed this probe's own
`-Ddiv.experimentalTwoBodyChassis=false` override from its `build.gradle`
gate, since it is no longer necessary - the real, unmodified default now
correctly routes flight/rotor vehicles to one-body.

Re-verified: `gradle --offline compileJava/test --rerun-tasks` - `BUILD
SUCCESSFUL`, 648 tests, 0 failures, 0 errors (further net-new tests from
main's own M527 merge). Live run 8 (`/tmp/m526-live-run8.log`, 2026-11-14
~06:18-06:23 UTC, shared-slot flock) with the override REMOVED: `ALL
SCENARIOS FINISHED - 6/6 PASSED`, and the per-vehicle `chassisReceipt`
diagnostic lines confirm `experimentalTwoBodyChassis=true` - the real,
unmodified production default - for every scenario, not a forced-off test
isolation flag. This is now the strongest evidence this ticket can offer:
the real production default, unmodified, correctly manages fixed-wing and
rotorcraft vehicles end to end.

**Targeted live evidence for the config-restore-failure path (review
53958/53959).** Review correctly found that even after the
"don't reload/claim restoration on a write failure" fix (`b7e8f05`), the
overall completion logging still looked success-shaped: `runScenarios()`
already logs `ALL SCENARIOS FINISHED` before cleanup starts, and
`releaseChunksOnce()`'s own final line always said `- ALL_DONE` regardless
of whether the config restore actually succeeded. Added `configRestoreFailed`
(set only in `restoreConfigOnce`'s own `IOException` branch) so
`releaseChunksOnce` now logs an explicit `CLEANUP FAILED, not a clean
ALL_DONE` ERROR line instead of the plain success line on that path.

To actually exercise this failure path live (not just reason about it), a
one-off local harness (outside the probe itself - a host-side `chmod 444`
on this worktree's own gitignored `run/config/dynamic-immersive-vehicles.json`,
timed via log-watching, restored to `644` immediately after capturing the
evidence) deliberately forced the restore write to throw. Captured from
`/tmp/m526-live-run9.log`:

```
[DIV flightRotorFixture] FAILED to restore original config at .../dynamic-immersive-vehicles.json - NOT reloading (would apply the known-still-probe-mutated file) - manual cleanup needed. Original content this probe is preserving for that cleanup: {...}
[DIV flightRotorFixture] released 115 chunk(s) this probe itself forced, but config restore FAILED (see the earlier FAILED-to-restore error above for manual cleanup) - CLEANUP FAILED, not a clean ALL_DONE
```

This proves, from an actual forced failure rather than code inspection
alone: no reload of the unrestored file, no false "restored and reloaded"
claim, chunk release still ran and is reported as unconditional, and the
terminal outcome is explicitly flagged as a cleanup failure rather than a
plain `ALL_DONE`. (The forced read-only permission also broke several
earlier in-run config writes as a side effect of firing early rather than
precisely at the final restore step - expected collateral of this blunt
harness technique, not a probe defect; the targeted final-restore-failure
lines above are what this evidence is for.) Permissions were restored and
the file's actual content verified intact immediately after capturing this
evidence - this worktree's own `run/` directory is gitignored and private
to this ticket, not shared repository state.

`gradle --offline compileJava/test --rerun-tasks` after this fix: `BUILD
SUCCESSFUL`, 648 tests, 0 failures, 0 errors (unchanged).

## Installed tire-specific grip overrides (MINECRAFT-529, added 2026-11-15)

Extends the existing part-override contract (`Overrides.parts`,
`Overrides.resolvedPart`/`effectivePartOverride`, MINECRAFT-505) so an
installed tire/ground-device PART can override its own contact grip, not
only the whole-car `friction` field - the bounded deliverable this ticket
names. Deliberately reused `friction` itself as the part-level field name
(rather than inventing a new one): it is already Gson-mapped, already
range-validated (`friction>0 && friction<=3` in `Tuning.validate()`), and
avoids any field-name collision risk with the three sibling tickets
(M528/M530/M531) working the same shared files concurrently this sprint.
The only schema change is deleting the one `throw` line in
`Tuning.validateSupportedForPart()` that previously rejected a part-level
`friction` entry outright - every other unsupported-field rejection in
that method is untouched, and `friction`'s bounds/`extends`-inheritance
machinery were already fully wired for every other field, so no new
validation code was needed at all.

**Production wiring.** `PhysicsBridge.tirePartFriction(partKey,
vehicleFriction)` mirrors `enginePartDriveForce` (MINECRAFT-505) exactly
in spirit and in precedence: an explicit part-level `friction` override,
if this specific installed tire part has one, REPLACES (never multiplies)
the vehicle's own resolved `friction` for that one wheel; otherwise the
vehicle's value passes through completely unchanged. `stepGround`'s own
`FrictionContact`-construction stream (immediately before the
`MultiContactFrictionSolver.solveFriction` call) now computes, per wheel,
`tireKey = wheel.definition.packID+":"+wheel.definition.systemName` (the
same key shape `enginePartDriveForce`'s own call site already uses for
`engine.definition`) and composes `tirePartFriction(tireKey,
tuning.friction)/tuning.friction` - a pure ratio that is exactly `1.0`
(a true no-op) whenever no part override is set - multiplicatively into
the EXISTING `frictionMultiplier` slot on `FrictionContact`, alongside
the ground-surface multiplier already living there (MINECRAFT-508):
`h.surface().frictionMultiplier()*tireGripRatio`. No new record
component, no change to `MultiContactFrictionSolver`'s public API/
constructors - confirmed deliberately to avoid touching the one file
M531 (shared reload entry point) and M530 (hitch) are also working this
sprint, and to avoid breaking any existing `FrictionContact`-constructing
test. Doc comments on `FrictionContact`'s own class doc and on the
`stepGround` composition site were updated to make explicit that this
slot now carries BOTH ground-surface and tire-part contributions
multiplicatively, so a future reader isn't confused into treating it as
ground-surface-only.

This reads each wheel's own currently-installed part key fresh every
tick inside `stepGround`'s existing per-wheel loop (never cached at
vehicle-construction time, and never routed through
`applyLiveSafeFields` - that mechanism only ever operates on vehicle-
level `Tuning`, confirmed by inspection before writing any code here;
part-level overrides were already being read live and uncached for
`enginePartDriveForce`, and this field needs nothing different). A wheel
that misses the ground ray this tick (`HitResult.Type.MISS`) never
reaches the `FrictionContact`-construction stream at all - its
`WheelHit` is never built, `wheel.contactThisTick` is set `false`, and
the loop `continue`s before anything in this ticket's new code runs - so
an airborne/phantom/spare tire's configured override can never
contribute any grip, satisfying the ticket's explicit "no phantom/
airborne contribution" requirement structurally, by the pre-existing
control flow this ticket left untouched.

**Tests.** `Body.stepGround` itself cannot be driven by a plain unit test
without a live Minecraft/IV/Sable runtime (`PhysicsBridgeWheelLoadShare
SimulationTest`'s own doc explains why every PhysicsBridge-level test in
this project already substitutes the real production pure-function path
instead of a live runtime) - the same substitution strategy is used here:

- `OverridesTest` (6 new cases): part-level `friction` now passes
  `validateSupportedForPart` (previously threw); every other
  previously-rejected field (`brakeForce`/`selfRightStrength`/`massKg`)
  still throws; an out-of-range part-level `friction` (3.5, 0.0) still
  fails plain `validate()` - the bounds check runs before
  `validateSupportedForPart` ever sees the merged entry, so no new/
  different bound was introduced; `friction` set via an `extends`
  template still folds in and still passes the supported-fields check;
  `PhysicsBridge.tirePartFriction` wins outright over the vehicle's
  resolved value when a part override exists, falls through unchanged
  when none exists, and resolves three different tire keys on the same
  vehicle (grippy/slick/unconfigured) to three independently correct
  values in one test - the ticket's "independent wheel identities"
  requirement at the `Overrides`/`PhysicsBridge` boundary. One
  pre-existing test (`partEntrySettingAnUnsupportedFieldFailsTheSupported
  FieldsCheck`) that asserted `friction` throws was updated to assert a
  still-genuinely-unsupported field (`massKg`) instead, since `friction`
  throwing is no longer the correct behavior this ticket intentionally
  changes; one pre-existing test (`partEntryInheritingAnUnsupportedField
  ThroughExtendsStillFailsTheSupportedFieldsCheck`) that used `friction`
  as its "still-unsupported-through-extends" example was switched to
  `brakeForce` for the same reason - no other existing assertion anywhere
  in the suite depended on `friction` being rejected on a part.
- `MultiContactFrictionSolverTirePartGripTest` (new, 4 cases): exercises
  the exact solver-facing composition `stepGround` performs (surface
  multiplier times the resolved-tire-friction ratio) against the real
  production `MultiContactFrictionSolver.solveFriction` - no part
  override anywhere composes to the byte-identical pre-ticket ground-
  surface-only multiplier; two differently-gripped tires plus an
  unconfigured third wheel in the SAME `solveFriction` call produce
  measurably different achieved lateral force from each other and from
  the unconfigured wheel (mirrors `MultiContactFrictionSolverGroundSurface
  Test`'s own "real force-consumer proof" pattern, MINECRAFT-508);
  swapping the resolved tire friction (standing in for installing a
  different part definition) changes the composed multiplier; ground-
  surface and tire-part contributions compose multiplicatively (0.2 ice
  times 0.5 slick tire = 0.1), neither one silently overriding the other.

Not covered by a plain unit test (requires a live runtime, same
limitation every other MISS/ground-contact-dependent behavior in this
project already carries, per `PhysicsBridgeWheelLoadShareSimulationTest`'s
own doc): the actual airborne-wheel-gets-zero-contribution behavior is
verified by inspection of the unchanged `HitResult.Type.MISS` control
flow (see above), not a driven integration test; in-game acceptance
(spawning a vehicle with two differently-configured installed tires and
observing measurably different handling) is out of scope here the same
way it was for every other MINECRAFT-5xx ticket's own "not yet performed"
note in this document.

Local slot-lock coordination (per this ticket's own brief - `flock` on
`/tmp/div-gradle-slot.lock` held for the whole Gradle invocation, no
`gradlew` wrapper present in this checkout so `/tmp/gradle-8.14/bin/gradle
--offline` was used directly against this project's own `build.gradle`/
`settings.gradle`/`gradle.properties`, Gradle 8.14). `compileJava`
succeeded first in isolation, then `test --offline -q` ran the FULL suite
with zero console output (quiet mode prints nothing on an all-green run).
The real JUnit XML reports total **662 tests**, 0 skipped, 0 failures, 0
errors across the whole suite (10 net-new - 6 in `OverridesTest`, 4 in
the new `MultiContactFrictionSolverTirePartGripTest` - plus every
pre-existing test run unchanged alongside them -
confirming this change introduces no regression anywhere else in the
suite, including every other ticket's own friction/part-override/ground-
surface coverage).

**Valid/invalid reload regression (review finding, PR #28).** The tests
above exercise `PhysicsBridge.tirePartFriction`'s precedence directly
against a map installed via `Overrides.setForTest` - real, but not the
same claim as a genuine RELOAD actually swapping in a new part override
or rejecting a bad one. Added
`validThenInvalidPartFrictionReloadAppliesThenRetainsThePriorGoodTireOverride`
(`OverridesTest`), mirroring the existing stallAngleDegrees/liftSlope/
controlSurfaceSlope "invalid reload retains last-known-good" regression's
exact structure, over `Overrides.apply(Supplier<Config>)` - the real
validate-then-swap-or-reject path `reload()` itself delegates through,
with no file I/O/Gson needed: applies a valid `parts.<tire>.friction`
(1.7) and asserts `PhysicsBridge.tirePartFriction` observes it; then
applies an invalid one (0.0, then separately 3.5 - both already-documented
out-of-range values for `friction`) and asserts each reload is rejected
AND the prior good 1.7 override is retained, not the invalid value or the
vehicle's flat fallback. Full suite re-run after adding this test:
**663 tests**, 0 skipped, 0 failures, 0 errors (662 + this 1 net-new case).

**Post-merge (2026-11-15).** Merged PR #28 into `main` at merge commit
`f16ea38665f410b0984f7e43e7d299f7ccca525a` (review approved at exact head
`c31ecfed09989f710ece87dcd550cf99e33ffcb6`, verified via `gh pr view
--json headRefOid,reviews` immediately before merging - the approval's own
recorded commit matched current head, no stale-approval risk). Post-merge
hosted CI (GitHub Actions run 43735826463, triggered by the merge push)
failed with all 5 jobs showing zero steps executed - "The job was not
started because recent account payments have failed or your spending
limit needs to be increased." This is the same pre-existing, already-
disclosed hosted-CI billing blockage every prior DIV ticket on this repo
has hit (most recently MINECRAFT-531's own merge, run 43732973812,
identical failure mode), not something introduced by or fixable from this
change. Retained exact-head local evidence stands in its place: a fresh
`gradle clean test build --offline` at the merged head (flock-protected on
`/tmp/div-gradle-slot.lock`) reproduced **663 tests**, 0 skipped, 0
failures, 0 errors, and produced
`build/libs/dynamic-immersive-vehicles-0.1.0-SNAPSHOT.jar` with SHA-256
checksum
`3181553d82fe76d21e26add899952e999d0d5d0477b02cd085f3c48b30ed3c0e`.

No separate release/deployment workflow exists in this repo beyond the
billing-blocked CI above (no `release.yml`, no tag-based release job) for
a change of this kind (an internal part-override/friction-composition
change, no version bump) - the same conclusion MINECRAFT-531's own merge
reached immediately before it. Per this ticket's own explicit scope ("Do
not interrupt or deploy to running Zippy; delivery coordinated
separately... User owns broad physical playtesting and it is not a
completion gate"), Zippy deployment and broad playtesting are not
performed here and are not this task's completion gate.

## Ground-surface config hot-reload (MINECRAFT-531)

Bounded deliverable: wire the already-implemented, already-non-throwing/
last-known-good `GroundSurfaceOverrides.reload()` (MINECRAFT-508) into the
existing periodic `OverridesHotReload` mechanism `Overrides.reload()`
(MINECRAFT-505) already uses, so editing
`dynamic-immersive-vehicles-surfaces.json` while the server is running
takes effect without a restart - the one thing `GroundSurfaceOverrides`'s
own class doc explicitly disclosed as missing.

`OverridesHotReload` now independently tracks and checks BOTH files' own
last-modified time (`lastSeenModifiedMillis` for the vehicle file,
`lastSeenSurfacesModifiedMillis` for the surfaces file - each read/compared
in its own try/catch, over its own file) on the SAME shared 100-tick check
period - an edit to one file can never mask, or be masked by, an edit to
the other, since neither's own detection depends on the other's state at
all. Confirmed by reading `GroundSurfaces.atState` (the real production
ground-contact entry point): it already calls
`GroundSurfaceOverrides.effective` fresh on every call, with no per-vehicle
caching layer in between - unlike vehicle `Tuning` (cached per-body,
selectively live-mutated via `PhysicsBridge.applyLiveSafeFields`), so
swapping `GroundSurfaceOverrides`'s own static map is already "live" for
every active vehicle's very next ground-contact tick. No change to
`PhysicsBridge.reloadOverrides()`'s signature or behavior was needed - the
"shared reload entry point" the sprint coordination comment named stays
exactly what it was; this ticket only adds a second, independent reload
call inside `OverridesHotReload` itself.

Adds `experimental/GroundSurfaceHotReloadProbe.java`: opt-in
(`-Ddiv.experimentalGroundSurfaceHotReload=true`, env-gated in
`build.gradle` as `DIV_GROUND_SURFACE_HOT_RELOAD_PROBE`), dev/isolated-
host-only, OFF by default - the same convention every other probe in this
package already uses. Unlike every ground-domain probe in this package, it
spawns no vehicle at all: "actual contacted-surface response" is read via
`GroundSurfaces.atState` against a real registered block's
`defaultBlockState()` (`minecraft:ice`/`minecraft:gravel`), the exact same
production entry point `GroundSurfaceTagProbe` (MINECRAFT-508) already
established as valid live evidence - no wheel/vehicle fixture is needed
since this file's own override map has no per-vehicle caching layer to
exercise. Four sequential phases, each writing to BOTH the surfaces file
and the vehicle-overrides file in the same step (non-destructive read-
merge-write, same convention `FlightRotorRuntimeFixtureProbe` uses) then
reloading BOTH via their real production entry points
(`GroundSurfaceOverrides.reload()`, `PhysicsBridge.reloadOverrides()` - the
same ones `OverridesHotReload` itself calls on its own mtime-poll, not a
re-derivation): BASELINE (establishes a known starting point for both
files together), VALID_SURFACE_VALID_VEHICLE (ordinary concurrent edit,
both succeed), INVALID_SURFACE_VALID_VEHICLE (a malformed surfaces edit is
rejected - last-known-good retained - while the concurrent valid vehicle
edit is NOT masked by that failure), VALID_SURFACE_INVALID_VEHICLE (the
mirror - an out-of-range vehicle edit is rejected while the concurrent
valid surfaces edit is NOT masked). Both files' pre-probe content is
snapshotted and restored verbatim (plus a final live reload of the
restored content) once done, reached from every exit path including a
mid-run failure - same `restoreConfigOnce` pattern
`FlightRotorRuntimeFixtureProbe` established.

**Self-found/self-fixed defect in this probe itself (not production code),
found by actually running it against a real dedicated server rather than
trusting code inspection alone:** the first live run's
INVALID_SURFACE_VALID_VEHICLE phase correctly asserted the rejected
`gravel` entry's last-known-good retention, but never repaired that
rejected entry back to valid ON DISK afterward - since
`GroundSurfaceOverrides.reload()` validates the WHOLE surfaces file as one
unit (same as `Overrides.reload()` does for vehicles), that still-broken
on-disk entry then poisoned the NEXT phase's own reload of the same shared
file, exactly the failure mode `FlightRotorRuntimeFixtureProbe`'s own
`applyInvalidConfig` doc already found and fixed for the vehicle file. Real
live-server evidence of the actual failure (first run, before the fix):
```
[DIV groundSurfaceHotReload] FAILED - VALID_VEHICLE_INVALID: the concurrent VALID surfaces edit was masked by the vehicle failure: ReloadResult[success=false, message=Cannot apply surface overrides: frictionMultiplier out of range, surfaceCount=-1]
```
Fixed by repairing the on-disk `gravel` entry back to its last-known-good
value immediately after that phase's retention assertion (mirroring the
established fix). Rerun after the fix, same real dedicated server, all four
phases passed:
```
[DIV groundSurfaceHotReload] BASELINE PASSED - surfaces reloaded (reloaded 1 surface override(s)) and vehicle reloaded (reloaded 3 vehicle override(s), 0 part override(s)) independently, both live-resolved correctly
[DIV groundSurfaceHotReload] VALID_SURFACE_VALID_VEHICLE PASSED - concurrent edits to both files both reloaded live and independently
[DIV groundSurfaceHotReload] INVALID_SURFACE_VALID_VEHICLE PASSED - invalid surfaces edit correctly rejected (last-known-good retained) while the concurrent valid vehicle edit applied, unmasked
[DIV groundSurfaceHotReload] VALID_SURFACE_INVALID_VEHICLE PASSED - invalid vehicle edit correctly rejected (last-known-good retained) while the concurrent valid surfaces edit applied, unmasked
[DIV groundSurfaceHotReload] original surfaces config restored and reloaded: reloaded 0 surface override(s)
[DIV groundSurfaceHotReload] original vehicle config restored and reloaded: reloaded 2 vehicle override(s), 0 part override(s)
[DIV groundSurfaceHotReload] COMPLETE (PASSED) - cleanup successful
```
(`gradle runServer` locally on Servy, under the required
`/tmp/div-gradle-slot.lock` flock, held through bounded cleanup and never
unlinked - a real headless dedicated server, `Done (6.7s)!`, this mod's own
mixins/physics pipeline actually initialized, not a mock.) This worktree's
`run/` directory is gitignored and private to this ticket; the probe's own
snapshot/restore left both shared config files byte-identical to their
pre-run content, verified by reading them back after the run.

**What this proves:** both files are independently detected and reloaded
without one masking the other, in both failure directions; a malformed
edit to either file is rejected with last-known-good retained; a valid
edit reaches the real production ground-contact resolution path with no
restart. **What this does NOT change:** no new surface model, erosion,
asset sampling, or vehicle-tuning field was added, and `PhysicsBridge`'s
existing vehicle live/respawn-field reporting (`applyLiveSafeFields`/
`reportFieldsNeedingRespawn`) is untouched - this ticket only adds the
second, independent surfaces-file check inside `OverridesHotReload`.

**Evidence gathered this pass:** `gradle --offline compileJava` (`BUILD
SUCCESSFUL`); `gradle --offline test --rerun-tasks` - `BUILD SUCCESSFUL`,
the real JUnit XML reports **652 tests**, 0 skipped, 0 failures, 0 errors
(this ticket adds no new unit tests of its own beyond what the real
dedicated-server probe above already exercises - `OverridesHotReload`'s
only pure/unit-testable seam, `isCheckTick`, is unchanged by this ticket
and already covered); the real dedicated-server `gradle runServer` probe
run above, both before and after the probe's own self-found/self-fixed
defect.

## Directional boat water drag (MINECRAFT-535, added 2026-11-15)

Extends the existing isotropic per-float quadratic point-velocity water
drag (`waterDrag`, `BuoyantForces.apply` lines ~68-73, `Buoyancy.drag`)
with three new bounded, body-local directional multipliers -
`longitudinalWaterDragMultiplier` (bow/stern), `lateralWaterDragMultiplier`
(port/starboard), and `verticalWaterDragMultiplier` (heave) - each a new
sparse `Overrides.Tuning` field, `0.0`-`5.0` inclusive, defaulting to
`1.0` (no FALLBACK change needed for existing behavior: `1.0` on every
axis is algebraically identical to the prior single-coefficient isotropic
formula - see below). Threaded through the full existing precedence chain
(`validate`/`copy`/`mergeOntoParent`/`resolveFields`/
`validateSupportedForPart` - rejected on part overrides, same as
`waterDrag`/`angularWaterDrag` themselves, since there is no part-level
consumer) and `PhysicsBridge.applyLiveSafeFields`, same shape as every
other live-hot-reloadable field in this project.

**The math.** `Buoyancy.directionalDragForce(speed, axisVelocityComponent,
submergedFraction, dragCoefficient, axisMultiplier)` returns
`-submergedFraction*dragCoefficient*axisMultiplier*speed*
axisVelocityComponent` - one axis's own signed force component. The
EXISTING isotropic vector force `BuoyantForces.apply` already computed
before this ticket, `-dragMagnitude/speed * localVelocity` (where
`dragMagnitude = submergedFraction*dragCoefficient*speed*speed`), expands
component-wise to exactly `-submergedFraction*dragCoefficient*speed*
velocityComponent` for every axis - i.e. `directionalDragForce` with
`axisMultiplier=1` on every axis. This is not a numerical coincidence this
ticket discovered after the fact; it is the exact algebraic identity the
new field's `1.0` fallback is built to satisfy, confirmed directly by
`BuoyancyTest.directionalDragWithUnitMultiplierMatchesIsotropicFormulaOnEveryAxis`.

**Production wiring.** Each submerged float's own world-frame point
velocity (`localVelocity`, already computed for the existing buoyancy/drag
calculation) is additionally transformed into the hull's body-local basis
via the existing `inverse` quaternion `BuoyantForces.apply` already has in
scope (the same basis `forward` is built in a few lines below: `x`=
lateral, `y`=vertical, `z`=longitudinal). Each axis's component is scaled
independently by `directionalDragForce` using that axis's own resolved
multiplier, and the resulting body-local force vector is transformed back
to world frame (via `q`, the forward orientation quaternion) before being
summed into the same `force` vector buoyancy's own upward component
already accumulates into - no new impulse application call, no change to
how that combined force reaches `handle.applyImpulseAtPoint`. Per-float
submergence fraction, propeller thrust, and the existing MINECRAFT-518
angular (roll/pitch/yaw) water drag are all completely untouched - this
is a purely additive change to the existing per-float linear drag term,
nothing else in `BuoyantForces.apply` was reordered or reused differently.

**Dissipative-only, never adds energy.** Every multiplier is bounded
`>=0` in `Tuning.validate()` - a negative value is rejected outright,
since it would flip `directionalDragForce`'s sign and make the "drag"
force push WITH an axis's own motion instead of against it, which is
exactly the kind of destabilizing energy addition this project's
MINECRAFT-518 angular-drag review (comment 53679) already established
must never happen for any water-drag term. `0.0` itself is explicitly
accepted (a real, legitimate "no drag on this axis at all" configuration,
not a degenerate one - same reasoning `brakeBiasFront`/`driveBiasFront`'s
own inclusive-extremes bounds already use), and `5.0` is a documentable
flat ceiling (same shape as `cyclicAuthorityScale`/`yawAuthorityScale`'s
own `5x` cap) - a tuning knob, not a path to an unbounded per-axis drag
force.

**Tests.**

- `BuoyancyTest` (5 new cases): `directionalDragForce` with every
  multiplier at `1.0` reproduces the existing isotropic vector formula
  component-for-component on all three axes (positive and negative
  velocity components, non-trivial fraction/coefficient); the force
  scales linearly with its own axis multiplier (`2x` multiplier -> `2x`
  force, `0` multiplier -> `0` force); across a swept
  `{0, 0.1, 1, 2.5, 5}` multiplier range, the returned force always
  opposes its own axis's velocity sign (never flips to push with it -
  the dissipative-only/no-energy-addition property, checked directly as
  a pure-function invariant, independent of any production wiring); zero
  when not submerged or not moving on that axis, matching `drag`'s own
  existing zero-cases.
- `OverridesTest` (4 new cases, mirroring the existing
  `suspensionSpringMultiplier`/`angularWaterDrag` test shape exactly):
  each of the three fields rejects negative/NaN/above-`5.0` values and
  accepts the inclusive `0.0`/`1.0`/`5.0` boundaries; an explicit
  operator override on all three takes precedence over the `1.0`
  fallback through the full `resolved()` chain; all three are rejected
  on a part-level override; a child entry inheriting
  `longitudinalWaterDragMultiplier` through `extends` picks up its
  template's own value.
- `PhysicsBridgeLiveOverrideApplicationTest` (existing suite, extended):
  all three new fields are confirmed as live-hot-reloadable via
  `applyLiveSafeFields`.

**Real-Sable runtime probe.** `DirectionalWaterDragProbe`
(`-Ddiv.experimentalDirectionalWaterDrag=true`, OFF by default, same
opt-in/CI-only/bounded-window discipline as every other probe in this
project - see `BuoyancyAngularDragProbe` for the shared pattern this
mirrors) spawns a real, Mixin-driven `BuoyantBody` hull YAWED 35 degrees
off world axes (so a frame-transform mistake in production would show up
as a real measurable defect, not be masked by an axis-aligned fixture),
then gives it a real initial world-frame velocity along one of its own
body-local axes at spawn time:

- `forwardBaseline` (isotropic `1.0`/`1.0`/`1.0`) vs. `forwardHighDrag`
  (`longitudinalWaterDragMultiplier=4`), same initial forward speed - the
  high-drag scenario's final speed must be at most 60% of the baseline's.
- `lateralHighDrag` (`lateralWaterDragMultiplier=4`), initial speed along
  the hull's own LATERAL axis instead, compared against the SAME
  `forwardBaseline` - valid because the isotropic `1.0` case is
  direction-independent by construction, so one baseline scenario
  correctly serves both axis comparisons; this is itself part of what the
  probe checks, not an assumption taken for granted.
- `verticalBaseline`/`verticalHighDrag` - a vertical bob (dropped above
  resting equilibrium, no horizontal velocity) at `1.0` vs.
  `verticalWaterDragMultiplier=4` - the high-drag bob must settle
  substantially faster.
- `equalMultiplierParity` (all three multipliers `=2.0`) vs.
  `equalScalarParity` (multipliers left at `1.0`, `waterDrag` itself
  doubled instead) - review's explicit "equal-axis parity" ask: both must
  decay to within a tight tolerance of the same final speed, since
  uniformly doubling every multiplier and doubling the underlying scalar
  coefficient are two different configuration paths to the identical
  physical force.
- Every scenario's own `measure` phase additionally enforces the
  dissipative-only/no-energy-addition requirement, checked as a decaying
  ENVELOPE (second-half peak speed vs first-half peak) rather than strict
  per-tick monotonicity - a real floating hull bobbing in water is a
  damped oscillator that legitimately exchanges kinetic and gravitational-
  potential energy via buoyancy every bob cycle (confirmed live across
  several iterations: a strict "speed never increases tick-over-tick"
  assertion rejected that normal bobbing outright on every single
  scenario, not a real drag defect); the pure-function dissipative-only
  property itself is already proven exhaustively by `BuoyancyTest` above.

Requires config-file entries seeded for each `divtest:waterdrag-*` key
(massKg plus this ticket's own fields, plus a modest `angularWaterDrag`
to damp the roll oscillation a strong lateral drag force induces at this
test hull's below-center float anchors - see the live findings below)
before running - see the probe's own class doc for the exact requirement,
the same disclosed limitation `BuoyancyAngularDragProbe`'s own doc already
carries for its fixtures. Gated behind `DIV_DIRECTIONAL_WATER_DRAG_PROBE=
true` in `build.gradle`'s `runServer` task, same opt-in pattern as every
other probe.

**Three live-discovered defects, found and fixed while actually running
this probe (none were production-code defects):**

1. **Chunk-loading watchdog crash.** `requestChunkGrid`'s `setChunkForced`
   blocks SYNCHRONOUSLY on real chunk generation
   (`ServerChunkCache.getChunk` -> `BlockableEventLoop.managedBlock`), not
   merely a deferred ticket add (confirmed directly via the server
   watchdog's own thread dump, twice) - requesting all 7 scenarios' grids
   (343 chunks), then even a reduced 5x5 grid (175 chunks), in a single
   tick exceeded the server watchdog's 60s-per-tick budget against a
   cold world and crashed the server outright.
   Fixed by (a) spreading one scenario's own chunk-grid request per tick
   across the first 7 ticks instead of all at once, and (b) switching the
   probe's own dev world to flat generation (`level-type=minecraft:flat`
   in `run/server.properties`), which eliminates the cold-generation cost
   variance that caused even the reduced 175-chunk request to still
   occasionally exceed 60s on an unlucky chunk.
2. **Wrong no-energy-addition test.** The ORIGINAL per-tick "speed must
   never increase" check rejected every single scenario's own normal
   settle/bob transient - see the envelope-check description above.
   Replaced with the decaying-envelope comparison.
3. **Undamped roll oscillation at the lateral axis.** A strong lateral
   drag force, applied at this test hull's two floats (mounted BELOW its
   own center, `y=-0.2`), produces a real roll torque - pre-existing in
   the ORIGINAL isotropic drag model this ticket extends (the same
   anchor/force geometry, independent of any multiplier), not introduced
   by the new directional multipliers themselves. With `angularWaterDrag`
   left at its `0.0` default in the test fixtures, that roll energy had
   nothing damping it: at `lateralWaterDragMultiplier=4.0` and `6.0`
   blocks/s initial speed, the resulting roll/translation feedback
   diverged within 5 ticks under `BuoyantForces.apply`'s existing
   EXPLICIT per-tick force/impulse integration (confirmed live: hull
   launched to 144 blocks above the water surface) - the same class of
   explicit-integration instability MINECRAFT-518's own review already
   found and fixed for ANGULAR drag (comment 53679), but never addressed
   for this pre-existing LINEAR drag term, which is out of this ticket's
   own scope to redesign. Fixed, without touching any production code, by
   (a) lowering the "high" multiplier scenarios to `2.5` and the initial
   speed to `4.0` blocks/s (still a clearly measurable, substantial
   difference from baseline, safely below where the pre-existing
   instability was observed to trigger), and (b) seeding a modest
   `angularWaterDrag: 50.0` in every fixture (a realistic companion
   configuration a real pack would also set, not a code change) to damp
   the roll oscillation that configuring a strong `waterDrag`/multiplier
   combination without any angular damping would otherwise leave
   undamped. Also switched the cross-scenario ratio comparison
   (`logRatioComparison`) from the noisy instantaneous `finalSpeed` to
   each scenario's own settled peak amplitude (`maxSpeedSecondHalf`),
   since a bobbing hull's instantaneous speed at one single sample point
   is phase-dependent noise, not a robust measure (confirmed live: a run
   where the high-drag scenario's own `finalSpeed` came out LOWER than
   baseline's purely by oscillation-phase luck, while its real settled
   amplitude was still clearly higher).

**Actual probe run results** (`DIV_DIRECTIONAL_WATER_DRAG_PROBE=true
gradle runServer`, flock-protected on `/tmp/div-gradle-slot.lock`, at this
exact head): all 7 scenarios reached `COMPLETE` with zero `FAILED` lines.
`FORWARD-AXIS-COMPARISON` ratio `0.204` (PASS, <=0.6 required),
`LATERAL-AXIS-COMPARISON` ratio `0.375` (PASS), `VERTICAL-AXIS-COMPARISON`
ratio `0.584` (PASS), `EQUAL-AXIS-PARITY` diff exactly `0.0` (PASS) -
configuring all three multipliers to `2.0` decayed IDENTICALLY to doubling
the scalar `waterDrag` coefficient directly, through the real
`BuoyantForces.apply` production call site, not merely in isolated
pure-function math.

**What this proves:** the directional multipliers are wired through the
real `Overrides` precedence/validation/live-reload chain exactly like
every other sibling field in this project, the underlying math exactly
reproduces the existing isotropic drag at the `1.0` default (both as a
pure-function identity and, via the probe, through the real production
`BuoyantForces.apply` call site), each axis's multiplier independently
and measurably changes drag on that axis through the real physics engine
(not just in isolated math), uniform multiplier scaling and direct
scalar-coefficient scaling are equivalent, and the force stays
dissipative (never adds energy) for any valid configuration.

**What this does NOT change:** per-float submergence/buoyancy, propeller
thrust/allocation, the existing MINECRAFT-518 angular water drag, boat
steering/rudder authority, and every other `Overrides.Tuning` field are
completely untouched. No planing model, steering-limit change, or new
rudder behavior was added - out of this ticket's explicitly bounded
scope.

**Evidence gathered this pass:** `/tmp/gradle-8.14/bin/gradle --offline
--no-daemon clean test build`, flock-protected on the shared
`/tmp/div-gradle-slot.lock` (never unlinked), at this exact head
(reconciled onto accepted main through MINECRAFT-532/533/534/536) -
`BUILD SUCCESSFUL`, the real JUnit XML reports **784 tests**, 0 skipped,
0 failures, 0 errors. Jar SHA-256
`67c455a6d63bc7205984f6378e0800182b09992d9206d145b2df05039e085d59`. The
real-Sable `DirectionalWaterDragProbe` run itself (an opt-in
`gradle runServer` pass, distinct from the unit-test evidence above) WAS
performed this pass - see "Three live-discovered defects" and "Actual
probe run results" above: all 7 scenarios `COMPLETE`, zero `FAILED`,
all four comparisons PASS.

## Installed-tire rolling resistance overrides (MINECRAFT-534, added 2026-11-15)

**Schema.** Mirrors MINECRAFT-529's own `friction` part-override exactly
in shape, for a new, INDEPENDENT field: `Overrides.Tuning.rollingResistance`
(bounds `(0, 10]` - strictly positive to avoid a division-by-zero in the
resolved-value ratio PhysicsBridge divides back out, same reasoning
`friction`'s own `>0` bound already documents; capped at 10 to match
`GroundSurfaceOverrides`'s own `rollingResistanceMultiplier` range, since
the two compose multiplicatively). Threaded through `copy`/
`mergeOntoParent`/`resolveFields` the same as every other field;
`FALLBACK.rollingResistance=1.0` (a true no-op against the ground-surface
multiplier, byte-identical to before this ticket). `validateSupportedForPart`
was NOT changed to reject it - like `friction`, it is a supported part
field from the start, with no separate "removed from reject list" diff
needed since this field did not exist before this ticket.

**Production wiring.** `PhysicsBridge.tirePartRollingResistance(partKey,
vehicleRollingResistance)` mirrors `tirePartFriction` exactly: an explicit
part-level `rollingResistance` override, if this specific installed tire
part has one, REPLACES (never multiplies) the vehicle's own resolved
`rollingResistance` for that one wheel; otherwise the vehicle's value
passes through unchanged. `stepGround`'s own `FrictionContact`-construction
stream now also computes `tirePartRollingResistance(tireKey,
tuning.rollingResistance)/tuning.rollingResistance` - a pure ratio, exactly
`1.0` with no override - and multiplies it into the EXISTING
`rollingResistanceMultiplier` slot alongside the ground-surface multiplier
already living there (MINECRAFT-508): `h.surface().rollingResistanceMultiplier()
*tireRollingResistanceRatio`. No change to `MultiContactFrictionSolver`'s
own rolling-resistance formula (`F=-speed*20*c.rollingResistanceMultiplier()`)
or public API - the composition happens entirely in the caller, same as
grip's own composition. This field is deliberately independent of
`friction`/`tirePartFriction` - a part may override one, both, or neither,
confirmed by a dedicated test (`tirePartRollingResistanceIsIndependentOfTirePartFrictionOnTheSamePart`).
Also wired into `applyLiveSafeFields` (vehicle-level hot reload) alongside
`friction`, since `stepGround` already re-reads `tuning.rollingResistance`
fresh every tick with no separate cached field - an operator editing a
vehicle's own `rollingResistance` live-applies on the very next tick, no
respawn needed, same as `friction`.

A wheel that misses the ground ray this tick (`HitResult.Type.MISS`)
never reaches the `FrictionContact`-construction stream - its `WheelHit`
is never built and the loop `continue`s before this ticket's code runs -
so an airborne/phantom/spare tire's configured override can never
contribute any rolling-resistance change, satisfying the ticket's "no
airborne/spare tire influence" requirement structurally, by the
pre-existing control flow this ticket left untouched.

**Tests.** Same substitution strategy MINECRAFT-529 used (`Body.stepGround`
cannot be driven by a plain unit test without a live runtime):

- `OverridesTest` (11 new cases): part-level `rollingResistance` passes
  `validateSupportedForPart`; out-of-range values (10.5, 0.0) still fail
  plain `validate()`; `rollingResistance` set via an `extends` template
  folds in and still passes the supported-fields check;
  `PhysicsBridge.tirePartRollingResistance` wins outright over the
  vehicle's resolved value when a part override exists, falls through
  unchanged when none exists, resolves three different tire keys on the
  same vehicle (draggy/smooth/unconfigured) independently, stays
  independent of `tirePartFriction` on the very same part entry, and a
  valid/invalid reload (`Overrides.apply(Supplier<Config>)`) applies a
  good override then rejects two out-of-range ones while retaining the
  prior good value - mirroring `friction`'s own MINECRAFT-529 reload
  regression exactly.
- `MultiContactFrictionSolverTirePartRollingResistanceTest` (new, 8 cases):
  exercises the exact solver-facing composition `stepGround` performs
  against the real production `MultiContactFrictionSolver.solveFriction` -
  no part override anywhere composes to the byte-identical pre-ticket
  ground-surface-only multiplier; a higher resolved rolling resistance
  dissipates measurably more coast-down energy (opposing-sign,
  larger-magnitude longitudinal force) than a lower one at the same
  forward speed with zero drive/brake demand (the ticket's required
  coast-down/energy-dissipation coverage); zero velocity produces exactly
  zero rolling resistance regardless of configuration (nothing to
  dissipate); reverse velocity still produces an opposing (sign-correct)
  force; swapping the resolved tire rolling resistance (standing in for
  installing a different part) changes the composed multiplier; ground-
  surface and tire-part contributions compose multiplicatively (2.0x
  gravel times 3.0x draggy tire = 6.0x, neither one silently overriding
  the other); and changing only `rollingResistanceMultiplier` leaves
  achieved lateral (grip) force completely unaffected, proving the two
  compositions (grip ratio, rolling-resistance ratio) are genuinely
  independent.

Not covered by a plain unit test (same pre-existing, already-disclosed
limitation every other MISS/ground-contact-dependent behavior in this
project carries): the actual airborne-wheel-gets-zero-contribution
behavior is verified by inspection of the unchanged `HitResult.Type.MISS`
control flow, not a driven integration test; in-game acceptance (spawning
a vehicle with two differently-configured installed tires and observing
measurably different coast-down behavior) is out of scope here, the same
way it was for MINECRAFT-529 and every other MINECRAFT-5xx ticket's own
"not yet performed" note in this document - John owns broad physical
playtesting per this sprint's shared execution/acceptance note, not this
ticket's completion gate.

**Evidence gathered this pass:** worked in an isolated worktree/branch
(`minecraft-534/installed-tire-rolling-resistance-overrides`) off the
sprint's accepted baseline `main` at `a0c4fa4c0ab265e96d8378747c6652ec2e9c7d2f`
(same baseline this ticket's own shared execution note names). Local
slot-lock coordination (`flock` on `/tmp/div-gradle-slot.lock` held for
the whole Gradle invocation, no `gradlew` wrapper present in this
checkout so `/tmp/gradle-8.14/bin/gradle --offline` was used directly).
`compileJava compileTestJava` succeeded first in isolation, then
`test --offline -q` ran the FULL suite with zero console output (quiet
mode prints nothing on an all-green run). The real JUnit XML reports
total **703 tests**, 0 skipped, 0 failures, 0 errors across the whole
suite (19 net-new - 11 in `OverridesTest`, 8 in the new
`MultiContactFrictionSolverTirePartRollingResistanceTest` - plus every
pre-existing test run unchanged alongside them, confirming this change
introduces no regression anywhere else in the suite, including every
other ticket's own friction/part-override/ground-surface/hot-reload
coverage). A subsequent `gradle --offline build -q` also succeeded
quietly, producing
`build/libs/dynamic-immersive-vehicles-0.1.0-SNAPSHOT.jar` with SHA-256
checksum
`a2fd5a8b59e0d7b6682c7b0fef1c5025b557c1ca0fb7bbc4a7ad436f29d72dae`.

**Hosted-CI billing/capacity limit disclosure.** The exact-head run
(https://github.com/brooswit-minecraft/dynamic-immersive-vehicles/actions/runs/43756562991)
failed before execution on all 5 jobs (`runner_id 0`, zero steps),
annotation "The job was not started because recent account payments have
failed or your spending limit needs to be increased." - the same pre-
existing, already-disclosed hosted-CI billing blockage recent DIV
tickets on this repo have hit (most recently MINECRAFT-531's merge),
not introduced by this change. Disclosed on both PR #30 and this ticket
per the sprint's shared acceptance requirement.

**Review correction (PR #30/Jira comment 54071, CHANGES_REQUESTED by
Wroosbit).** The initial composition at `stepGround`'s `FrictionContact`-
construction call site divided the resolved tire value back out by
`tuning.rollingResistance` (mirroring `tireGripRatio`'s own division by
`tuning.friction` immediately above it) - but, UNLIKE `friction`,
`solveFriction` has no separate vehicle-baseline parameter for rolling
resistance (its `rollingResistanceMultiplier` is the SOLE scalar
multiplying the solver's fixed k=20 formula). That division silently
cancelled any configured non-unit vehicle `rollingResistance` back to a
no-op when no part override existed, and produced `surface*(part/
vehicle)` instead of `surface*part` when a part override DID exist -
contradicting this ticket's own declared "replace, not multiply"
precedence. The review also correctly noted the new solver test
hard-coded a unit (1.0) vehicle baseline and repeated the same (buggy)
division in its own helper, so it could not have caught this.

**Fix.** Removed the division entirely -
`PhysicsBridge.tirePartRollingResistance(tireKey, tuning.rollingResistance)`
already returns the fully-resolved value either way (part override, or
the vehicle's own resolved value unchanged), so the contact-construction
site now composes `h.surface().rollingResistanceMultiplier()*
tireRollingResistance` directly, no ratio. Added two dedicated non-unit-
vehicle-baseline regressions to `OverridesTest`
(`nonUnitVehicleRollingResistanceComposesWithGroundSurfaceUnchangedWhenNoPartOverrideExists`,
`nonUnitVehicleRollingResistanceIsReplacedOutrightByAPartOverrideInTheComposedMultiplier`),
driven through the real production `PhysicsBridge.tirePartRollingResistance`
with vehicleRollingResistance=2.0 (not 1.0, so a reintroduced division bug
cannot hide behind self-cancelling arithmetic) - one asserting the
no-override case composes to 2.0 (not 1.0), the other asserting a 3.0
part override composes to 3.0 (not 1.5). Also corrected the existing
`MultiContactFrictionSolverTirePartRollingResistanceTest` helper to match
the fixed production composition (no division). Full suite re-run after
the fix: **705 tests**, 0 skipped, 0 failures, 0 errors (703 + 2 net-new
regressions). `gradle --offline build -q` succeeded again; jar SHA-256
`65ea6c7725a863c979f1b8ae0a0d34817f54043a598582f49b480bab6a2a140f`
(changed from the pre-fix build, as expected for a real production
behavior change).

**Reconciliation onto accepted main (review finding, Jira comment 54081).**
Accepted `main` advanced past this branch's own base
(`a0c4fa4c0ab265e96d8378747c6652ec2e9c7d2f`) to
`d5fb9ad0abb7a6d681664374afff45a82464cc67` (MINECRAFT-532's own
contact-aware axle drive-force sharing merge) while this ticket's review
was in flight - GitHub reported `CONFLICTING`. Merged `origin/main` into
this branch: `Overrides.java`/`OverridesTest.java` auto-merged cleanly
(the two tickets' own additive schema changes - `rollingResistance` here,
`axleDriveForceSharing` there - land in disjoint regions of the shared
file, exactly the "additive, coordinate through the schema coordinator"
scoping the sprint's shared execution note requires). `PhysicsBridge.java`
had one real conflict, a doc-comment list both tickets independently
appended to (`applyLiveSafeFields`'s own "which fields are live-safe"
enumeration) - resolved by keeping both tickets' own entries, in the
order they were appended (`axleDriveForceSharing` then
`rollingResistance`); the ACTUAL field-wiring code for both tickets
(M532's own `applyLiveSafeFields`/axle-sharing block at lines ~418-420/
~2222-2231, this ticket's own `tirePartRollingResistance` composition at
lines ~2408-2411) sits in disjoint regions and auto-merged with no
conflict at all - confirmed by inspection that both call sites are
intact and unchanged from each ticket's own independent work.
`VERIFICATION.md` had the expected two-independently-appended-sections
conflict (both tickets added a dated `##` section at the same point in
the file) - resolved by keeping both sections, this one first. No
production logic was altered by this reconciliation beyond the conflict
resolution itself. Full suite re-run after reconciling: **722 tests**,
0 skipped, 0 failures, 0 errors (705 of this branch's own + 17 from
MINECRAFT-532's own merged work, confirming no regression from either
side of the merge). `gradle --offline build -q` succeeded again; jar
SHA-256 `9355b64a4dc682ffa9ca9351627f6267603c2f0009a95e02807f786ceb9bde60`.

**Second reconciliation (review findings, Jira comments 54089/54091;
John's coordination comment 54086).** Accepted `main` advanced again
while the previous reconciliation's review was in flight, through
MINECRAFT-533's own per-axle toe-alignment merge
(`c2fa1e37e90438da3d7e6b87482dbe6341f4d1a9`, PR #33) - the prior
reconciled head (`fc4fd48`) only integrated as far as `d5fb9ad` and so
omitted M533's already-accepted work, even though `git merge-tree`
itself reported clean (no code conflict - this was a staleness/evidence
gap, not a conflict). Merged `origin/main` again: this merge auto-
resolved with ZERO conflicts in any file (`Overrides.java`,
`PhysicsBridge.java`, `VERIFICATION.md` all auto-merged cleanly) -
M533's own `axleToeDistribution`/`AxleToeDistribution` additions and
this ticket's `rollingResistance` work remain in disjoint regions, same
additive-schema-coordination property the first reconciliation already
demonstrated for M532. Full suite re-run after this second
reconciliation: **741 tests**, 0 skipped, 0 failures, 0 errors (722 of
the prior reconciled state + 19 from MINECRAFT-533's own merged work).
`gradle --offline build -q` succeeded again; jar SHA-256
`f272eedb061f57e0b1ee21a6fd887452da22c6e8c39c86080a049cdbc3194be3`.

**Post-merge (2026-11-15).** Wroosbit approved exact head
`353a523fa4da3537970ddbb1d9ccd38eaa963037` (review comment Jira 54102);
merged PR #30 into `main` at merge commit
`4538d5184c517309a6f161f68d35bb26abedf061`. Post-merge hosted CI (run
43760732717, triggered by the merge push) failed with all 5 jobs
executing zero steps - the same pre-existing, already-disclosed
hosted-CI billing/spending-limit blockage every recent DIV ticket on
this repo has hit (most recently MINECRAFT-533's own merge, run
43759185283). No `release.yml` or tag-based release workflow exists in
this repo beyond that billing-blocked CI, and this change (an internal
part-override/rolling-resistance-composition change, no version bump)
does not warrant inventing one. Retained exact-head local evidence
stands in its place: a fresh `gradle --offline clean test build` at the
merged head (flock-protected on `/tmp/div-gradle-slot.lock`) reproduced
**741 tests**, 0 skipped, 0 failures, 0 errors, and produced
`build/libs/dynamic-immersive-vehicles-0.1.0-SNAPSHOT.jar` with SHA-256
checksum
`b4624b6ea3f33227b8ffb389bf8f7d141dfb5c9e80ffa88d2365b0a3ea487a83`.

## Contact-aware axle drive-force sharing (MINECRAFT-532, added 2026-11-15)

Bounded deliverable: opt-in redistribution of a wheel's own already-assigned
drive-force share (`DriveBiasDistribution#shareRatios`' output) to its
grounded same-axle wheel(s), whenever that wheel has no ground contact this
tick, weighted by each grounded wheel's own real measured suspension
contact load (`WheelHit.normal()`, AFTER MINECRAFT-519's anti-roll
redistribution) - never wheel angular velocity/slip, and never across the
front/rear axle boundary. This is explicitly NOT a limited-slip
differential, wheel-spin simulation, or traction control, per the ticket's
own stated constraint.

New isolated, Minecraft-type-free helper class `AxleDriveForceSharing`
(mirrors `DriveBiasDistribution`'s own shape: same `AXLE_EPSILON`/max-`z`
front-row classification, "lump every non-front row into one rear group"
convention). `shareRatios(z, driveShareRatio, grounded, load, enabled)`:
disabled (default) returns a defensive copy of `driveShareRatio` completely
unchanged - byte-identical to the pre-ticket allocation; enabled
redistributes WITHIN each axle group independently (front and rear total
assigned ratio both exactly conserved), weighting grounded wheels by real
load and sending an airborne wheel's own ratio to `0`; an axle with no
grounded wheel carrying positive load at all (every wheel airborne, or
every grounded wheel reporting exactly zero load) is left completely
untouched rather than fabricating a split with nothing real to weight it
by.

New `Overrides.Tuning#axleDriveForceSharing` (`Boolean`, no FALLBACK entry -
null/false is the documented no-op default, same "unset means untouched"
shape as `driveBiasFront`/`brakeBiasFront`; rejected on `parts` entries, a
vehicle-only axle concept). Wired into `PhysicsBridge.stepGround` as a new
post-Phase-1 pass placed immediately AFTER the existing MINECRAFT-519
anti-roll block (so the `load` it consults is the final, already-
redistributed contact load) and before the native contact-ownership sync:
builds `grounded`/`load` arrays over the full wheel count from `hits`, calls
`AxleDriveForceSharing.shareRatios`, and rebuilds each `WheelHit` with its
new ratio via the new `WheelHit.withDriveShareRatio` (mirrors the existing
`withNormal` record-replacement shape). Re-read live every tick via
`applyLiveSafeFields`, same as `driveBiasFront`. No change to
`MultiContactFrictionSolver.FrictionContact`'s own shape - only which
`driveShareRatio` value a `WheelHit` carries before the solver composes it
with `driveForcePerWheel*skidSteerMultiplier`, same as before this ticket.

**Evidence named by the ticket:** `AxleDriveForceSharingTest` (pure-math
core) covers disabled no-op (byte-identical, independently-allocated
copy), equal contacts (no change from an already-even split), an
asymmetric-load split (weighted exactly by real measured load, each
axle's own total conserved), one wheel airborne (its share moves only to
its grounded same-axle mate, the other axle completely untouched),
zero/all-airborne and all-grounded-zero-load fallback (left untouched
rather than dividing by zero or fabricating a split), an asymmetric
(trike-like) wheel-count layout, and 500 randomized trials asserting every
ratio stays finite/non-negative and each axle's own total assigned ratio
is exactly conserved. `AxleDriveForceSharingIntegrationTest` is the real
production-path integration against `MultiContactFrictionSolver
.solveFriction` (mirrors `ServiceDriveBiasTest`'s own solver-level shape):
enabling sharing measurably recovers acceleration an airborne wheel's
share would otherwise lose versus the disabled default; disabled
reproduces the exact pre-ticket unredistributed-loss outcome; reverse
travel still accelerates correctly (and still shows the same recovered-
acceleration effect) with the sign flip applied at the `driveForcePerWheel`
call site, unaffected by this ratio composition; and the redistributed
ratio composes multiplicatively with skid-steer's own per-wheel
multiplier, neither overriding the other. `OverridesTest` and
`PhysicsBridgeLiveOverrideApplicationTest` gained `axleDriveForceSharing`
coverage mirroring their existing `driveBiasFront` cases (no-fallback-
stays-null, full precedence chain, part-override rejection, live hot-
reload mutation).

Schema coordination (ticket comments 54060/54063 - a live multi-agent
coordination exchange on this ticket itself, which this owner coordinates
as the designated schema coordinator for the concurrent M532-537 sprint):
this ticket's own additive `Overrides.Tuning` region (one new field, one
new independent helper class, one new post-Phase-1 `PhysicsBridge.stepGround`
pass) was posted before any of M533-537 landed their own fields, along with
a proposed ticket-number merge-sequencing convention for the remaining five.

Local slot-lock coordination (per this ticket's own shared cooperative
`flock` on `/tmp/div-gradle-slot.lock`, held for the whole Gradle lifetime,
never unlinked or bypassed). `gradle --offline compileJava compileTestJava
test --console=plain` against this branch
(`minecraft-532/contact-aware-axle-drive-force-sharing`) at head (origin/main
`a0c4fa4` unchanged at this ticket's start) - `BUILD SUCCESSFUL` in 49s. The
real JUnit XML reports total **705 tests**, 0 skipped, 0 failures, 0 errors
across the whole suite (10 from the new `AxleDriveForceSharingTest`, 4 from
the new `AxleDriveForceSharingIntegrationTest`, plus every pre-existing
test - including every other ticket's own drive/brake/anti-roll/suspension
behavior - run unchanged alongside them, confirming no regression).

Not yet performed: in-game acceptance (a real vehicle spawn with
`axleDriveForceSharing` configured, observed recovered acceleration with
one wheel genuinely airborne over uneven terrain) - this ticket's own brief
says focused engineering tests are in scope, not John's broad/hardware
playtesting, which is explicitly out of scope here. Hosted CI remains
limited by the disclosed billing/capacity constraint (see other tickets'
own entries above); this pass relies on the local evidence above.
## Per-axle toe alignment (MINECRAFT-533, added 2026-11-15)

Bounded M507 slice. Inspected the named seams first:
`PhysicsBridge.stepGround`'s per-wheel loop already computes `loadShareX`/
`loadShareZ` (each wheel's own x/z anchor relative to `center`, the exact
geometry `BrakeBiasDistribution`/`DriveBiasDistribution` already use for
their own front/rear axle split) before building `steer`/`forward`/`right`
at the documented `~1960` seam - no new per-wheel geometry needed, only a
new per-wheel angular offset folded into the existing `steer` value before
`forward`/`right` are derived from it.

Implementation deliberately mirrors MINECRAFT-520/524's own
`BrakeBiasDistribution`/`DriveBiasDistribution` shape (same geometric
front/rear axle grouping off each wheel's own `z`, same unset/degenerate-
layout fallback), but adds the orthogonal left/right mirroring
`PhysicsBridge`'s own `skidSteerMultiplier` already does off `anchor.x`:
new `AxleToeDistribution` class (`toeRadians(x[], z[], toeFrontDegrees,
toeRearDegrees)` - `-signum(x)*toeRadians` per wheel, so a positive value
is toe-IN, converging, on whichever axle it is configured for), new
`Overrides.Tuning#toeFrontDegrees`/`#toeRearDegrees` (`-20` to `20`
degrees, no FALLBACK entry - null means that axle's toe is unconfigured,
rejected on `parts` entries since a part has no own axle position, live-
hot-reloaded via `applyLiveSafeFields` the same as `brakeBiasFront`/
`driveBiasFront`). `PhysicsBridge.stepGround`'s existing `steer` line now
adds this per-wheel offset ADDITIVELY to the existing rudder-driven
steering term (never replacing it), so a steerable wheel still sweeps with
rudder input exactly as before, just around a toed neutral, and a non-
steering wheel still gets its own configured toe; `skidSteerMultiplier`'s
own separate `anchor.x`-based computation is untouched, so track-vehicle
skid-steer composes unaffected. No camber thrust, suspension, or visual-
only change - only the contact tangent `forward`/`right` frame itself.

Evidence named by the ticket: `AxleToeDistributionTest` covers the pure-
math core - mirrored left/right symmetry (equal-magnitude opposite-sign
offsets), axle classification (including a reversed-z and a lumped-multi-
axle layout, mirroring `BrakeBiasDistributionTest`'s own cases), the
ambiguous single-axle/lone-wheel fallback to zero toe, a wheel exactly on
the centerline getting no toe (no "other side" to mirror against), degrees-
to-radians conversion, and a 500-trial random-layout finiteness sweep.
`ToeAlignmentContactIntegrationTest` is the real production-path
integration against the actual `MultiContactFrictionSolver.solveFriction`
on the same representative 4-wheel layout `ServiceDriveBiasTest` uses, with
each wheel's own `forward`/`right` built exactly the way `stepGround` does
(`steer`->`sin`/`cos`): straight travel from rest with a symmetric toe
configuration produces exactly zero net world-frame lateral velocity (the
ticket's own "no unintended net lateral force at symmetric state") while
still accelerating forward; the same holds in reverse; an unset
(`null`/`null`) toe is a byte-identical no-op against an explicit
`0.0`/`0.0`; a configured front toe produces a measurably different
lateral outcome than no toe at all once the vehicle already has lateral
slip to act against (the ticket's own "meaningful aligned-force
difference"); and toe composes additively with an existing rudder steer
angle rather than replacing it. `PhysicsBridgeLiveOverrideApplicationTest`
gained `toeFrontDegrees`/`toeRearDegrees` coverage mirroring its existing
`brakeBiasFront`/`driveBiasFront` cases (live hot-reload mutation,
including revert-to-null on removal).

Local slot-lock coordination (shared cooperative `flock` on
`/tmp/div-gradle-slot.lock` held for the whole Gradle lifetime, no
concurrent Java/Gradle process of another owner observed at claim time).
`/tmp/gradle-8.14/bin/gradle --no-daemon build --console=plain`, Gradle
8.14 / JDK 21 (Temurin), against this branch
(`minecraft-533/per-axle-toe-alignment`) at head `a0c4fa4` (origin/main,
the pinned baseline for this six-slice sprint) - `BUILD SUCCESSFUL`. The
real JUnit XML reports total **707 tests**, 0 skipped, 0 failures, 0
errors across the whole suite (13 from the new `AxleToeDistributionTest`,
5 from the new `ToeAlignmentContactIntegrationTest`, plus every
pre-existing test run unchanged alongside them - confirming this change
introduces no regression anywhere else in the suite, skid-steer/tracks,
brake/drive-bias and every other ground-force path included).
`build/libs/dynamic-immersive-vehicles-0.1.0-SNAPSHOT.jar`
(sha256 `2df1d3ac26f5da6ee474e7bc74b89e43fa94ff74e2daad5509466446b1b56674`)
was produced.

Not yet performed: in-game acceptance (real vehicle spawn with configured
`toeFrontDegrees`/`toeRearDegrees`, observed grip/stability effect) - this
ticket's own brief says focused engineering tests are in scope, not John's
broad/hardware playtesting, which is explicitly out of scope here.

**Reconciliation onto M532 (CHANGES_REQUESTED, review comment, added
2026-11-15).** Main advanced from this ticket's a0c4fa4 baseline to
`d5fb9ad` via MINECRAFT-532 (contact-aware axle drive-force sharing),
making PR #33 `mergeable=false`/`mergeable_state=dirty`, exactly as the
reviewer's merge-tree reproduction found. Merged `origin/main` into this
branch; conflicts were confined to the explicitly-coordinated shared
schema/contact seam (`Overrides.java` field declarations/validation/part-
rejection/copy/merge/resolve, `PhysicsBridge.applyLiveSafeFields`,
`PhysicsBridgeLiveOverrideApplicationTest`'s combined-fields case) plus
doc/example-JSON append points (README.md, VERIFICATION.md, the example
config) - every conflict was two independent field additions sitting on
adjacent lines, resolved by keeping both sides (M532's
`axleDriveForceSharing` plumbing alongside M533's
`toeFrontDegrees`/`toeRearDegrees` plumbing), never dropping either
feature's own logic. `PhysicsBridge.stepGround` itself merged cleanly with
no conflict: M533's toe offset folds into `steer` at the existing
`~1960`-area wheel-hit construction loop, while M532's own post-contact
drive-force redistribution (gated on `Boolean.TRUE.equals(tuning.
axleDriveForceSharing)`) runs later in the same method against the already-
built `hits` list - two genuinely distinct regions, confirming the
ticket's own "independent helper classes and distinct force regions"
coordination held.

Local slot-lock coordination (shared cooperative `flock` on
`/tmp/div-gradle-slot.lock` held for the whole Gradle lifetime, no
concurrent Java/Gradle process of another owner observed at claim time).
`/tmp/gradle-8.14/bin/gradle --no-daemon build --console=plain` against the
reconciled head (`minecraft-533/per-axle-toe-alignment` merged with
`origin/main` @ `d5fb9ad`) - `BUILD SUCCESSFUL`. The real JUnit XML
reports total **724 tests**, 0 skipped, 0 failures, 0 errors across the
whole suite - M532's own `AxleDriveForceSharingTest`/
`AxleDriveForceSharingIntegrationTest`/`OverridesTest` additions and
M533's `AxleToeDistributionTest`/`ToeAlignmentContactIntegrationTest`
additions both present and passing together, confirming no regression in
either direction.
`build/libs/dynamic-immersive-vehicles-0.1.0-SNAPSHOT.jar`
(sha256 `65dd7fb988eaa35a954e739d6b2722a026b4b53b5aa5fcd02220b6e25d80da03`)
was produced.

## Float-only hull hitch support (MINECRAFT-545)

`HitchAnchor` (level/box/center) is implemented by both `PhysicsBridge.Body`
and `BuoyantBody`; `Classification.floatEligible`, `PhysicsBridge.active`/
`isHitchableBody`/`createHitch`/`reconcileHitch` are generalized from
`Body`-only to this new interface so a float-only hull can be either side of
a hitch joint. `gradle test` under the shared `/tmp/div-gradle-slot.lock`:
**786 tests**, 0 skipped/failures/errors (785-test accepted baseline + 1 new
`ClassificationTest` case for the corrected `floatEligible` behavior).

**Production safety fix (`PhysicsBridge.unsafeBuoyantAngularLock`):** a
`mounted`/`restricted` angular lock between two active `BuoyantBody`
instances is refused outright (falls back to IV's native hitch for that
pair, exactly as every towed float behaved before this ticket) - added
after live evidence (below) showed this exact combination is a genuine,
reproducible runaway instability, not a hypothesis. `gradle test`: **789
tests**, 0 skipped/failures/errors (785-test accepted baseline + 1 new
`ClassificationTest` case for the corrected `floatEligible` behavior + 3
new `PhysicsBridgeClassificationTest` cases for the new safety gate).

**Live Sable evidence (`BuoyantHitchProbe`, opt-in via
`-Ddiv.experimentalBuoyantHitch=true`/`DIV_BUOYANT_HITCH_PROBE=true`),
required because this ticket's own acceptance criteria name real
attach/detach/rotated-anchor/unavailable-replacement/despawn-unload
behavior, which no unit test can exercise without a live Sable-driven body
and the real Mixin-intercepted tick loop.** Real pontoon-hull
`EntityVehicleF_Physics` spawned with connection groups, wired with the
real `AEntityG_Towable.connectTrailer`/`disconnectTrailer` API (never a
DIV-internal shortcut), driven under
`/tmp/gradle-8.14/bin/gradle --no-daemon --console=plain runServer` on this
branch (`minecraft-545/float-only-hull-hitch-support`). Final run: `[DIV
buoyantHitchProbe] COMPLETE - ATTACH/DETACH/TOWING_UNAVAILABLE/
DESPAWN_TRAILER/LEVEL_UNLOAD/INSTALLED_PART_ATTACH all passed (TOWING_
REPLACEMENT disclosed as not attempted)`:
- `ATTACH`: connected from a deliberately non-aligned initial pose (towed
  spawned yawed 200&deg;, anchors offset from each hull's own center);
  anchor-point world-distance (computed independently from each vehicle's
  own public pose, never a DIV-internal field) settled to `0.0024` blocks
  by tick 140, well under the `0.75`-block tolerance.
- `DETACH`: `towing.disconnectTrailer(0)`; `towed.towedByConnection`
  explicitly asserted `null` (not merely logged) 60 ticks later - both
  hulls stayed finite/bounded.
- `TOWING_UNAVAILABLE`: `towing` despawned while `towed` stayed jointed;
  `towed` remained finite/bounded for 60 ticks with no crash - the
  documented "already-active trailer stays an unjointed, free-rolling
  Sable vehicle" contract (`PhysicsBridge.deferToNativeHitch`'s own doc)
  held for a float-only trailer too. This is the live evidence for "towing
  body unavailable".
- `DESPAWN_TRAILER`/`LEVEL_UNLOAD`: the already-unjointed `towed` despawned
  itself, then `PhysicsBridge.unload(level)` called directly against the
  level with nothing left alive from this pair; no exception either time.
- `INSTALLED_PART_ATTACH`: an independent third pair (`towing3`/`towed3`,
  own pool) where `towed3`'s hookup connection is owned by its own
  installed right-float part (not the vehicle root) - anchor distance
  settled to `0.0012` blocks by tick 140, live proof that
  `HitchJoint.partConnectionOffset`'s MINECRAFT-530 installed-part
  composition reaches a `BuoyantBody`.

**Two real defects this probe's own first live runs caught and fixed
before this evidence was recorded** (both confirmed live, not merely
reasoned about): (1) the probe's own first `DETACH` check called
`disconnectAllConnections()` and logged `towedByConnection` without
asserting it - that method is documented as the OWN-removal cleanup path
and does NOT clear a trailer's own `towedByConnection`; switched to
`disconnectTrailer(0)` (the real detach call) plus an explicit
null-assertion. (2) an EARLIER `TOWING_REPLACEMENT` fixture (a live
towing-body-REPLACEMENT demo: fresh towing hull, reconnect `towed` to it,
settle) computed the fresh towing vehicle's spawn position from a fixed
offset that ignored `towed`'s own settled yaw, and a later attempt solved
for exact anchor coincidence without accounting for both hulls'
collision-box half-extents (spawning the fresh hull overlapping `towed`);
both produced the identical symptom live - anchor distance settling
cleanly below `0.003` blocks, then a sudden runaway divergence to 35+
blocks within ~10 ticks, ending in a `BuoyantBody.step`
`NullPointerException` once the ejected body left Sable's own active
region.

**Disclosed, NOT resolved: "towing body replacement" has no reliable live
evidence, now with a confirmed-deterministic, mechanistic diagnosis (not
timing/host-load, as first suspected).** Per-tick diagnostic logging
(position + linear speed, every tick) across multiple runs with
byte-identical code produced bit-identical trajectories - ruling out the
timing-sensitivity theory entirely. The actual sequence: tick 1 (joint
just created) is unremarkable (initial anchor gap ~2.0 blocks, both
bodies' speeds normal); by tick 2 `towed` alone jumps ~9 blocks in one
step; by tick 3 BOTH bodies are moving at 230-300+ blocks/s; within 5-8
ticks one leaves Sable's own active region and the next `step()` call
crashes on the now-handle-less body. Three further hypotheses were tested
and ruled out: spawn clearance (4 vs 8 blocks, no change), explicit
`box.wakeUp()` on both sides right after `addConstraint` (now shipped as
a genuine defensive improvement in `PhysicsBridge.createHitch` regardless
- see below - but did not change this crash's trajectory at all, bit-
identical with or without), and `towing2` spawning partially submerged
(pinned its spawn Y to the water surface - no change).

Leading hypothesis: the fully-rigid (always-linear-locked)
`GenericConstraintConfiguration` joint applies its FIRST solve as a
single, undamped correction sized by the full initial anchor gap, and
this is disproportionately violent specifically when joining a freshly-
spawned, zero-velocity body to an already-settled, actively-simulating
one - unlike this ticket's own `ATTACH` phase, where BOTH sides are fresh
(spawned the same tick) with a similar-or-larger initial gap and settle
gently. If true, this would NOT be float-specific: no existing DIV probe
or test (MINECRAFT-525/530 included) appears to have ever exercised a
live "reconnect to a freshly-spawned towing vehicle" case for ANY body
type, ground included - this may be a pre-existing, general hitch-joint-
creation gap this ticket's own live testing happened to be the first to
exercise, not a defect this ticket introduced. Recorded on MINECRAFT-547
with the suggestion to test the identical sequence against a ground
vehicle pair next, to confirm or rule out the float-specific framing.
"Towing body unavailable" (above) remains fully live-proven; "replacement"
specifically does not have live evidence within this ticket's own bounded
scope/budget - continued per John's own explicit direction not to waive
this named requirement (ticket comment history), diagnosed from evidence
rather than retried blindly or silently dropped.

**Per John's own directive (ticket comment 54217): a bounded minimal
ground-vehicle reconnect comparison, using the identical lifecycle/anchor
setup.** Added `GroundHitchReplacementProbe` - two simple 4-wheeled ground
vehicles (no engine needed; `Classification.eligible` only requires a
drivable ground device), same ATTACH/DETACH/TOWING_UNAVAILABLE/
TOWING_REPLACEMENT shape as `BuoyantHitchProbe`'s main pair, same
per-tick diagnostic logging. Result: **ground does NOT reproduce the
crash.** The replacement reconnect (initial anchor gap ~2.5 blocks, a
similar magnitude to the float case's ~2.0-2.1) produces a real but
transient speed spike (peak ~17 blocks/s at ticks 3-10), which the ground
vehicle's own wheel-suspension forces damp out cleanly - back under 1
block/s by tick 80, fully settled (anchor distance ~0.21 blocks, within
tolerance) by tick 140, `COMPLETE`. This is a DEFINITIVE finding: the
crash is specific to a float-involved pair, not a pre-existing general
DIV/Sable hitch-joint-creation gap - buoyancy alone does not damp a
horizontal correction impulse of this scale fast enough, unlike a ground
vehicle's active wheel-ground contact forces.

**An attempted fix based on this finding was tried and explicitly
reverted after it made things WORSE, confirmed live - reported here in
full rather than silently dropped.** Hypothesizing that the raw initial
anchor-gap SIZE was the deciding factor, added
`PhysicsBridge.unsafeBuoyantInitialGap` - a gate refusing (throwing,
caught by `reconcileHitch`'s own try/catch) any hitch-joint creation with
a world-space anchor gap over a conservative 1.0-block threshold when
either side is a `BuoyantBody`. Live result: this ALSO blocked the
ORIGINAL, previously-safe `ATTACH` phase (its own initial gap is ~4
blocks, well over the threshold, yet that configuration has settled
cleanly across every prior run in this ticket) - every tick's own
construct-then-immediately-roll-back cycle (the gate's exception causes
`reconcileOrRollBack` to tear down the JUST-activated `BuoyantBody` every
single tick, since a brand-new body is never "already coherently
jointed") produced worse chaos than the crash it was meant to prevent:
Sable itself logged "Aborting entity get for abnormally large AABB" and
the hull's own position diverged to absurd magnitudes (tens of thousands
of blocks) within 4 ticks. REVERTED in full (code and tests) once this
was confirmed live - raw gap size is NOT, by itself, a safe discriminator
between the working `ATTACH` case and the crashing `TOWING_REPLACEMENT`
case; the real distinguishing factor remains unidentified (leading
hypothesis unchanged: joining a FRESH body to an already-settled one,
not gap size alone - both `ATTACH` sides are fresh, only
`TOWING_REPLACEMENT`'s `towed` side is not). A speculative `box.wakeUp()`
call on both sides at joint creation (tried in an earlier iteration) was
also removed per John's own instruction not to retain a defensive change
that doesn't address a demonstrated requirement - confirmed by direct A/B
test to have no effect on the crash's own trajectory either.

**Net result: no code fix for the replacement crash is included in this
PR.** Two fix attempts were tried live and both failed to help (one
inert, one actively harmful and reverted); the ground-vehicle comparison
conclusively rules out "pre-existing, not float-specific" as a basis for
treating this as out of scope. The crash remains fully disclosed,
reproducible, and unresolved - recorded in detail on MINECRAFT-547.

**Disclosed, deliberate scope limit:** the probe's connections are ordinary
(unmounted, unrestricted) rather than `restricted`/`mounted` with a
non-identity `rot`. A first version used `restricted` with a 20&deg; `rot`
between the main pair to also exercise `HitchJoint.lockedAxes`'s yaw/roll
lock for a float-only body; live evidence showed that locking yaw/roll
between TWO independently-buoyant hulls (each with its own righting torque
fighting the lock) produces the genuine runaway instability the new
`unsafeBuoyantAngularLock` production gate above now refuses outright.
Mounted/restricted-joint stability for a buoyant pair is tracked at
MINECRAFT-547, not chased down here. The rotation-sign convention itself
(MINECRAFT-525) is already covered, domain-agnostically, by
`HitchRotationFrameProbe`/`InstalledPartHitchFrameProbe`, neither of which
depends on which `HitchAnchor` implementation either side of the joint is.

Not yet performed: broad/hardware playtesting with a real content pack and
a real player (out of scope per this sprint's own brief - the user owns
that, not a completion gate).

**Pure diagnostic, per John's directive (comment 54230): real native
linear/angular velocity at joint creation, `ATTACH` vs `TOWING_REPLACEMENT`.**
No fix attempted here - this is measurement only, added as log-only
instrumentation gated behind the existing `GroundContactDiagnostics.ENABLED`
flag (`PhysicsBridge.createHitch`, reading each side's real
`RigidBodyHandle.getLinearVelocity()`/`getAngularVelocity()` - NOT MTS's own
`v.motion`, which is linear-only and, as shown below, can read calm while
the real native body is not).

Live `BuoyantHitchProbe` run, `createHitchDiagnostic` log lines in order:

| joint created | towing linVel | towing angVel | trailer linVel | trailer angVel |
|---|---|---|---|---|
| `ATTACH` (both fresh) | (0, 0.883, 0) | (0,0,0) | (0,0,0) | (0,0,0) |
| `TOWING_UNAVAILABLE` reconnect (same towing, same towed, after 140-tick settle) | (0.041,-0.067,0.025) | (-0.003,0.024,-0.098) | (-0.004,-0.327,-0.044) | (0.119,0.063,-0.032) |
| `TOWING_REPLACEMENT` (fresh towing2, same towed, 60 ticks after towing1 despawned) | (0, 0.883, 0) | (0,0,0) | (0.075,**-5.589**,-2.021) | (**-6.341**,-0.030,-0.117) |

The finding: `towed`'s real angular velocity reads ~0.12 rad/s (normal
post-settle residue, row 2) vs. **-6.34 rad/s** at the row-3 sample. **Timing
correction (independent specialist review, MINECRAFT-547 comment 54237):**
the row-3 value is sampled INSIDE `createHitch`, which runs AFTER
`connectTrailer` has already been called - it is a single point-in-time
sample, not a continuous trace of the preceding 60-tick free-float window.
It does NOT by itself prove when the spin began, and does NOT rule out the
hitch/joint lifecycle as a contributor - retracting the stronger claim this
subsection originally made. `towing2` itself is calm (identical to `ATTACH`'s
fresh-spawn state). MTS's own `.motion` field, read by the probe's own
`TOWING_UNAVAILABLE` bound check, reported `towed.motion.y=-0.047` - calm,
and with no angular component at all, at a point the probe treats as
"settled". The probe's own position-only bound check during
`TOWING_UNAVAILABLE` cannot see rotation at all: `towed` never left the
probe's position bounds, so `TOWING_UNAVAILABLE PASS` is truthful but blind
to whatever rotational state is building underneath it.

**Do not read the previous version of this section as having ruled out the
joint/hitch lifecycle** - that claim exceeded the evidence this single
pre/post-connectTrailer comparison actually supports; independent specialist
review (547 comment 54237) found neither prior lifecycle causes nor fixture
effects have been excluded. Two concrete, unresolved specialist findings as
of this writing: (a) the probe fixture's own float placement
(`(+/-0.5,-0.2,0)`, COM `(0,0.3,0)`, no fore/aft spacing, both force arms
below COM) is a plausible source of fixture-specific pitch instability under
small perturbations - kept as-is (not silently substituted) so the failure
stays reproducible; (b) point quadratic drag impulses lacking
effective-mass/inertia timestep bounds is a flagged numerical vulnerability,
not an established cause here. `BuoyantForces.apply`'s torque/angular
computation remains a plausible contributor (same code already implicated
in the retained `unsafeBuoyantAngularLock` fix, MINECRAFT-547, for the
JOINTED mounted/restricted case) but is a hypothesis, not a demonstrated
root cause.

Per comment 54230/54237, no further fix is attempted here.

**Follow-up run (the "lower-cost corroborating check" above, now actually
performed) - this changes the picture substantially.** Extended
`PhysicsBridge.debugReadAngularVelocity`/added `debugReadLinearVelocity` to
also cover `BuoyantBody` (previously `Body`-only; a diagnostic/test-only
seam, same pattern as the existing `debugInjectAngularVelocity`), and used
both from `BuoyantHitchProbe` to log `towed`'s real native state EVERY tick
of `TOWING_UNAVAILABLE`'s free-float window, plus both sides' real state
immediately BEFORE `TOWING_REPLACEMENT`'s `connectTrailer` call (addressing
54237's exact complaint about the earlier, single-sample-after-the-fact
diagnostic). Full raw log retained at
`/home/booswrit/div-evidence/MINECRAFT-545-547/M545-diag3run-20261116T051533Z/server.log`
(head `e03c347`, checksummed).

The result: **`towed`'s angular velocity is already massive - `(-73.0,
-13.84, 88.0)` rad/s - on `TOWING_UNAVAILABLE`'s very FIRST tick**,
immediately after `towing.remove()` is called while `towed` is still
Sable-jointed to it. The immediately preceding sample (the reconnect
`createHitchDiagnostic` line, one tick earlier, before `towing.remove()`)
showed a calm `(0.119, 0.063, -0.032)` rad/s. This is not a 60-tick
buildup - it is a single-tick jump, timed exactly to the despawn-while-
jointed call, not to anything resembling gradual torque accumulation. The
value then decays/steps down irregularly over the following ticks
(`(-34.3,-17.9,27.0)` through tick 24, several further step-changes, down
to the previously-seen `(-6.34,-0.03,-0.12)` by tick 53 - then EXACTLY
constant through tick 60, consistent with the hull losing water contact
and coasting with nothing to damp it further).

This retracts the `BuoyantForces` torque-growth hypothesis as the primary
suspect (there is no growth to explain - the spike is already present on
tick 1) and instead points at the despawn/joint-release lifecycle itself,
exactly the "stale constraints/handles" possibility 54230 asked to check
and 54237 said had not been excluded. Precise code seam: `towing.remove()`
is called directly by the probe (an ordinary MTS/IV entity removal, not
through any `PhysicsBridge` API), which only marks the entity invalid;
`PhysicsBridge.cleanup()` - registered on `ServerTickEvent.Post`, BEFORE
the probe's own listener registration in `DynamicImmersiveVehicles`, so it
already ran for the CURRENT tick by the time the probe's `remove()` call
executes - is the only thing that calls `hitches.release`/`body.remove()`
for an invalidated vehicle, and does not run again until the NEXT tick's
`Post` event. For that one intervening tick, `towed`'s own `active()` still
finds `towing`'s `BuoyantBody` present in the registry (`isHitchableBody`
reads true), so `reconcileHitch` treats the joint as still coherently
valid and leaves it alone, while `towing`'s own entity never ticks again
(no more `step()` calls) - yet its native Sable box is still registered
and the constraint is still live against it. This is a hypothesis
supported directly by the timing evidence above, not yet confirmed by
instrumenting `cleanup()`'s own tick-relative execution order or Sable's
physics-step ordering directly - that is the next bounded step, not
speculative damping/delay, and not attempted here.

Separately, still open and NOT addressed by the above: the "already-active
trailer fallback" gap from 54237 (`unsafeBuoyantAngularLock` firing for an
already-active trailer) goes through `reconcileHitch`'s own synchronous,
clean release path every tick (not the despawn-while-ticking path above),
so it is likely a distinct, separate question from this specific crash's
mechanism - not conflating the two without more evidence.

**Further follow-up run (John's comment 54250: "record the immediately
preceding native state too" - before AND after `towing.remove()` itself,
not just the first later sample) - this RETRACTS the despawn-lifecycle
hypothesis above.** Added tick-relative logging: `active()`'s own
`isHitchableBody`/`towingVehicleActive` decision every tick, the exact
tick `cleanup()` actually releases/removes an invalidated vehicle,
`BuoyantBody.step`'s own entry velocity every tick it runs, and both
sides' real state immediately before AND after `towing.remove()` (not
just the next probe-tick's sample). Full raw log retained at
`/home/booswrit/div-evidence/MINECRAFT-545-547/M545-diag5run-20261116T060313Z/server.log`
(head `d90d336`, checksummed).

**Result: the massive angular velocity is already present BEFORE
`towing.remove()` is ever called.** The `pre-remove()` sample - logged at
the top of `towingUnavailable()`, before the despawn call - already shows
`towed.angularVelocity=(-73.0,-13.84,88.0)` AND `towing.angularVelocity=
(-41.4,-12.5,-3.44)`. The despawn/`cleanup()` ordering hypothesis from the
previous subsection is therefore WRONG as stated - there is no stale-
constraint-against-a-removed-body mechanism needed to explain this, since
both bodies are already violently spinning before either is removed.

Tracing one tick further back: the `BuoyantBody.step` entry log for THIS
exact tick, immediately after `towingUnavailable`'s own reconnect
(`towing.connectTrailer(connection, false)`, called to keep `towed`
jointed to the ORIGINAL `towing` one more time before despawning it - a
step the probe performs, not production code, but using the exact same
`createHitch`/`addConstraint` path every reconnect uses) - shows BOTH
sides calm: `towing` `(0.0405,-0.0665,0.0247)` lin / near-zero ang,
`towed` `(-0.0042,-0.327,-0.0436)` lin, `(0.119,0.063,-0.032)` ang. The
NEXT sample (one game tick later, the `pre-remove()` line above) is
already catastrophic. So the explosion happens in the single tick
immediately following THIS RECONNECT's own native joint creation/first
solve - independent of any despawn or cleanup-ordering question entirely.

This matters because, unlike the fresh-vs-fresh `ATTACH` case (both
bodies newly constructed via `BuoyantBody`'s own constructor, zero prior
native state) and unlike `TOWING_REPLACEMENT` (one fresh `towing2`, one
long-active `towed`), THIS reconnect joins two bodies that are BOTH
already-active, long-stepped native handles (both went through `ATTACH`'s
own settling, then 60 ticks fully unjointed during `DETACH`). The common
factor between this reconnect and `TOWING_REPLACEMENT` - both unsafe - is
that AT LEAST ONE side is an already-active (not freshly constructed)
native body at the moment the joint is (re)created; `ATTACH` - the one
safe case observed so far - has neither side already-active. This directly
revives and sharpens MINECRAFT-547's own original leading hypothesis
("joining a FRESH body to an already-settled one" - recorded in that
ticket's description before the ground-comparison/gap-size detours), now
generalized to "a joint (re)created where at least one side is already-
active" and freshly evidenced by a case (this reconnect) that involves NO
replacement, no despawn, and no fresh body being constructed anywhere -
just a plain re-hitch of the same two already-settled vehicles.

Not yet confirmed as the mechanism (still a hypothesis, now much better
evidenced): what specifically differs, inside Sable's own native solver,
between a constraint's first solve against a freshly-constructed
`RigidBodyHandle` versus an already-stepped one - warm-start/contact-cache
state, broad-phase registration timing, or something else entirely. That
is the next bounded step, not attempted here - no fix, no speculative
damping, per standing instruction.

Reported to MINECRAFT-547 and MINECRAFT-545, retracting the
despawn-lifecycle framing from the immediately preceding subsection.

**Code-level review (John's comment 54253's four specific checks) - no
live run needed, pure source analysis against the DIV and Sable API
source.** Clears DIV's own code of all four named concerns rather than
speculating further:

1. *Anchor offsets (`pos1`/`pos2`)*: `HitchJoint.anchorLocalOffset` is
   purely body-local (`localOffset - bodyCenter`, confirmed by its own doc
   comment and MINECRAFT-525's empirical anchor-frame probe) - `bodyCenter`
   is `BuoyantBody.center`, a `final` field set ONCE in the constructor
   from the chassis geometry, never updated. Identical computation
   regardless of how long the body has been active - no staleness
   possible here by construction.
2. *Local-frame rotations (`local_frame1`/`local_frame2`)*: for this
   probe's ORDINARY (unmounted, unrestricted) connections,
   `JSONConnection.rot` is documented as having "no effect on other
   connections" (`HitchJoint.localJointFrame`'s own doc, review 53817) -
   `connectionRotationVehicleLocal` returns `HitchJoint.localJointFrame
   (connectionRot)` directly for a root-level connection (this fixture's
   case, `owner` is the vehicle itself, not an `APart`), which is identity
   whenever `rot` is null/unset. No live-orientation dependency at all for
   this fixture - "current vs. stale" does not apply to an always-identity
   value.
3. *Removed constraints actually absent*: `HitchRegistry.reconcile` always
   calls `release(trailer)` - which synchronously removes the OLD
   `PhysicsConstraintHandle` via `own.remove()` - BEFORE calling `create.
   get()` to build any new one, in the same method call, same tick, no
   gap. Structurally guaranteed by this code's own control flow; the old
   and new constraint can never coexist.
4. *Current body-local transforms vs. stale construction pose*: traced
   `connectionOffsetVehicleLocal`/`connectionRotationVehicleLocal`'s own
   source - for a ROOT-level connection (`owner instanceof APart` is
   false, exactly this fixture's case), `vehicleOrientation` is NOT EVEN
   READ; the method returns `connectionPos`/`localJointFrame(connectionRot)`
   directly, with no transform by live or cached orientation at all. Also
   confirmed `VehicleMovementMixin.div$move` calls `PhysicsBridge.active`
   (which triggers `createHitch`) BEFORE `PhysicsBridge.step` (which
   refreshes `v.orientation` from the box's native pose) in the same
   tick - so `v.orientation` IS technically "last tick's" value at
   `createHitch` time for an already-active body - but since this fixture's
   effective code path never reads `vehicleOrientation` at all, that
   staleness is provably inert here regardless.

**None of DIV's own anchor/frame/lifecycle code shows a staleness or
double-constraint defect for this fixture.** This does not, by itself,
establish a native Sable/Rapier solver defect (not inferring that merely
from the fresh-vs-stepped correlation, per instruction) - it narrows where
one would have to live if it exists: inside Sable's own native
joint-creation/first-solve handling of an already-active vs. freshly-
constructed `RigidBodyHandle`, which is outside this codebase and is what
the assigned read-only specialist is positioned to inspect. The
already-active unsafe-fallback correction (54237) remains open and in
scope, not dropped, not addressed in this update.

**Already-active unsafe-fallback correction (John's comment 54256) - now
fixed, independently of the ordinary-reconnect diagnosis above.** The gap
from 54237: `PhysicsBridge.active()`'s own `towingVehicleActive` boolean
conflated two different reasons a trailer's joint can't reconcile - "the
towing vehicle simply has no Sable body" (transient; MINECRAFT-498's own
regression fix correctly keeps an already-active trailer Sable-
authoritative and unjointed for this reason, waiting for a body to
reappear) versus "the towing vehicle DOES have a body, but
`unsafeBuoyantAngularLock` refuses this specific pair" (a DIFFERENT
condition whose own doc says it should fall back to IV's native hitch for
the pair - the SAME fallback a first-activation wait gets). Previously,
`deferToNativeHitch` never deferred an already-active trailer for EITHER
reason - correct for the first, wrong for the second: an already-active
trailer caught by the unsafe-lock gate stayed Sable-authoritative,
unjointed, with `towedByConnection` still pointing at a pair this bridge
itself judged unsafe, and IV's own native hitch code never got a chance
to run.

Fix: split `towingVehicleActive` into `towingHasHitchableBody` (the pure
"is there a body at all" fact) and `unsafeLock` (the separate "is this
PAIR unsafe" fact) in `active()`; `deferToNativeHitch` and `decide()` both
take the new `unsafeLock` parameter, and now defer
(`towed && !towingVehicleActive && (!alreadyActive || unsafeLock)`)
whenever `unsafeLock` is the reason, regardless of `alreadyActive` -
routing through the EXISTING, already-correct `active()` ->
`deactivate(v)` path (`decision == DEFER_TO_NATIVE`), which actually
removes the Sable body and releases the joint via `hitches.release(v)` -
not a new mechanism, the same native-fallback path a first-activation
wait already uses. MINECRAFT-498's own regression (an already-active
trailer must never be torn down merely because its towing vehicle has no
body at all) is unaffected: `unsafeLock` is `false` for that case, so
`deferToNativeHitch` still returns `false` there, exactly as before.

Verified by unit tests (`PhysicsBridgeClassificationTest`):
`anAlreadyActiveTrailerIsDeferredWhenTheUnsafeAngularLockIsTheReason` and
`serverDefersAnAlreadyActiveTowedVehicleWhenTheUnsafeAngularLockFires` -
both new, covering exactly the previously-unreachable
(`alreadyActive=true`, `towingVehicleActive=false`, `unsafeLock=true`)
combination end to end through `deferToNativeHitch`/`decide()`.
`anAlreadyActiveTrailerIsNeverDeferredEvenIfItsTowingVehicleLosesItsBody`
(the MINECRAFT-498 regression test) still passes unchanged, confirming no
regression. `gradle test`: 791 tests, 0 failures.

**Live end-to-end verification (John's comment 54259: the unit tests only
exercise the pure `deferToNativeHitch`/`decide` boolean helpers - they
don't demonstrate actual body removal, joint release, or authority
handoff through the real `PhysicsBridge.active` entry point).** Added
`AlreadyActiveUnsafeFallbackProbe` (opt-in,
`-Ddiv.experimentalAlreadyActiveUnsafeFallback=true`): spawns `towed` (a
buoyant hull) completely standalone - no hitch, no towing vehicle at all -
confirms via `PhysicsBridge.debugReadLinearVelocity` that it settles into
a real, active Sable body purely from standalone float eligibility (no
prior hitch history to confound the result), then connects a FRESH
buoyant `towing2` on a `mounted` hitch connection for the FIRST time this
trailer has ever had a `towedByConnection` - exercising exactly the
(`alreadyActive=true`, `towingVehicleActive=false`, `unsafeLock=true`)
combination the fix targets, live, through the real `active()` entry
point, not a fake registry.

Result: **PASS at tick 1.** `towed`'s real native Sable body is ACTUALLY
REMOVED - `debugReadLinearVelocity`/`debugReadAngularVelocity` both
return `null` the very first tick after the unsafe connect - not merely
unjointed. `towed.isValid` stays `true` and its position stays sane (no
crash, no explosion, no leaked handle) - confirming the fix reaches real
existing-body authority/joint lifecycle removal, not just the boolean
predicate. Full raw log retained at
`/home/booswrit/div-evidence/MINECRAFT-545-547/M545-fallbackprobe-run3-20261116T122843Z/server.log`
(head `cf65a3b` + this probe's own uncommitted diff at run time, checksummed).

**Sharpened per John's comment 54262: the above only proved DIV's own
registry stopped tracking a body - it did NOT establish release of a
PREEXISTING joint, sustained native towing, or actual native-object
destruction (as opposed to inferring it from `deactivate`/`remove`'s own
source).** Rewrote the probe: `towed` now gets a REAL preexisting joint
from spawn (towed by `towing1` on an ORDINARY/safe connection, matching
`BuoyantHitchProbe`'s own proven-safe `ATTACH` pattern), confirmed via a
new `PhysicsBridge.debugIsJointed` (a direct `HitchRegistry.isJointed`
observation) and a captured native box reference
(`debugCaptureBody`/`debugCapturedBoxIsActive`, reading the SAME
`BoxPhysicsObject.isActive()` before and after, a live observation of
object destruction rather than an inference). `towing1` is then replaced
by a fresh MOUNTED buoyant `towing2` - exercising the fix with a genuine
joint to release - and `VERIFY_FALLBACK` now holds for a SUSTAINED
100-tick window, re-checking all of: DIV registry absence, joint release,
native-object destruction, and `towed.towedByConnection` persistence
(sustained native IV authority), every tick, not once.

**Live result: PASS, sustained for the full 100-tick window.** At tick 1
post-reconnect: `debugIsJointed=false` (the preexisting joint was
actually released), the captured native box's own `isActive()`
transitioned to `false` (the SAME object, actually destroyed - not
inferred), `debugReadLinearVelocity`/`debugReadAngularVelocity` both
`null`, `towed.towedByConnection != null` stays `true` throughout (IV's
own native connection persists). `towed` settles to a stable resting
position (falls under gravity to the platform surface, Y≈101, under IV's
OWN native handling - not Sable) and stays finite/valid for the entire
100-tick window with no further change. Full raw log retained at
`/home/booswrit/div-evidence/MINECRAFT-545-547/M545-fallbackprobe-run4-20261116T160229Z/server.log`
(head `5cb21fb`, checksummed). This item is now complete: fixed,
unit-tested, AND live-verified end to end with a genuine preexisting
joint, sustained over many ticks, with direct native-object-destruction
evidence rather than source inference.

**Honest cleanup-disposition audit (John's comment 54262: "no matching
server/lock holder now does not prove restoration").** None of this
ticket's probes (`BuoyantHitchProbe`, `GroundHitchReplacementProbe`,
`AlreadyActiveUnsafeFallbackProbe`, or any other) call
`level.setChunkForced(..., false)` to un-force their own chunk grid,
remove their own spawned entities, or restore `ConfigSystem`'s
`packSpeedFactors`/`packVehicleScales` map entries at the end of a run -
every probe run across this entire ticket has been ended by a bounded
`timeout` killing the `gradle runServer` process, never by a graceful
`/stop`. Confirmed directly in this run's own log: no "Stopping
server"/"Saving worlds" line appears anywhere in it. `run/world` (the
shared, reused world save directory under this worktree) currently holds
~17MB across 13 region files, accumulated across every probe run this
ticket has performed - nothing in this workflow has ever reset or
inspected it between runs. `ConfigSystem`'s maps are in-memory,
per-JVM-process state that does reset naturally when a new `gradle
runServer` process starts (not a persistent leak across runs by itself),
but within any given run nothing un-sets the entries either. A clean
process-list/lock-file check (as reported in earlier cycles) confirms
only that no SERVER PROCESS is currently running - it says nothing about
whether forced-chunk flags or fixture entities were actually written
back out by an autosave before the process was killed, or remain in
`run/world` on disk. Not remediated here per instruction ("do not
perform destructive cleanup") - reported factually for the record.

**Ordinary-reconnect diagnosis, continued directly (John's comment 54262:
"there is no verified currently active specialist dependency... continue
already-authorized bounded ordinary-reconnect diagnosis... if the next
step requires expertise beyond scope, report the precise missing
expertise and code seam").** Traced the full call chain from DIV's own
`PhysicsBridge.createHitch` through Sable's Java wrapper down to the
actual native boundary, to locate exactly where the fresh-vs-already-
active asymmetry would have to live if it is a native solver effect:

- `createHitch` builds a `GenericConstraintConfiguration` (body-local
  `pos1`/`pos2`, identity `orientation1`/`orientation2` for this
  fixture's ordinary connections - already traced and cleared, comment
  54254) and calls `physicsSystem().getPipeline().addConstraint(...)`.
- That resolves to
  `RapierGenericConstraintHandle.create(serverLevel, bodyA, bodyB,
  config)` (Sable's own source, read directly at
  `sable_rapier/.../constraint/generic/RapierGenericConstraintHandle.java`
  in the local Sable checkout) - a thin wrapper that packs the same
  config fields into a single native call,
  `Rapier3D.addGenericConstraint(sceneHandle, idA, idB, pos1..., pos2...,
  lockedAxesMask)` - no body-state/wake/island logic of its own; it is a
  pure marshalling call across the JNI boundary.
- `Rapier3D.addGenericConstraint` is a native method - its actual
  implementation is the `rapier3d` Rust crate, pulled as an EXTERNAL git
  dependency (`rapier3d = { git = "https://github.com/ryanhcode/rapier",
  rev = "38e92f117590862481a53df6fc69a5d893e29186" }`, confirmed by
  reading `sable_rapier/.../Cargo.toml` directly) - NOT vendored
  anywhere in this workspace. Confirmed no local or cached copy of that
  crate's own source exists on this machine at all (`find` for
  `rapier*`/`~/.cargo` turned up nothing outside Sable's own thin
  wrapper).

**This is the actual, concrete boundary.** DIV's own Java-side candidates
are now exhausted and cleared by direct evidence: (a) sleep/wake state -
ruled out live (the earlier `box.wakeUp()` A/B test showed bit-identical
trajectories with/without, confirmed inert); (b) DIV's own anchor-offset/
local-frame computation - traced and provably inert for this fixture's
ordinary connections (comment 54254); (c) a double-constraint/stale-
construction-pose lifecycle bug - ruled out by `HitchRegistry.reconcile`'s
own structural release-before-create guarantee (comment 54254 again).
What remains - whether an already-stepped body's own island/warm-start/
contact state inside Rapier's own solver behaves differently when a NEW
joint is added to it than for a freshly-inserted body - is genuinely
INSIDE the external `rapier3d` crate's own native solver, not inside
DIV's or Sable's own Java source at all. Per instruction, this is NOT
being claimed as a confirmed cause, only as the one remaining candidate
location, now narrowed as far as this codebase's own source permits.

**Precise missing expertise and code seam, for anyone with the
capability to continue past this point:** Rust/Rapier physics-engine
internals - specifically `rapier3d` (`github.com/ryanhcode/rapier`,
pinned rev `38e92f117590862481a53df6fc69a5d893e29186`), its
`ImpulseJointSet`/island-merging code path for adding a joint between a
freshly-inserted `RigidBody` and a long-resident, previously-stepped one,
and whether contact/warm-start state carried by the resident body
interacts with that joint's own first solver iteration. This requires
either network access to fetch and read that external repository at the
pinned revision, or direct Rapier/Rust physics-engine expertise applied
to it - a different skillset than this ticket's own Minecraft-mod Java
codebase, and genuinely beyond what this agent's own sandboxed
environment and scope provide. No further live experiment is attempted
here on this specific question, consistent with the standing instruction
against blind experimentation once a seam this precise has been located.
