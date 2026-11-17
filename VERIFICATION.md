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

**Retraction and correction (John's comment 54267): external source was
NOT actually unreachable (`gh api` against `github.com/ryanhcode/rapier`
at the pinned revision works fine - confirmed live), and Sable has its
OWN in-repo Rust JNI implementation
(`sable_rapier/src/main/rust/rapier/src/joints.rs`) that a keyword-only
grep had skimmed past without reading its actual control flow - an
overlooked, in-scope seam, not an external one. First verified the exact
installed Sable revision: the local checkout at
`sable-local` is tagged `mc1.21.1-2.0.5-fabric-3-geb2a698` (commit
`eb2a698`), matching this worktree's own dependency
(`dev.ryanhcode.sable:sable-neoforge-1.21.1:2.0.5`) exactly - so the
earlier reading WAS of the correct revision, just not read carefully
enough.**

Read `joints.rs` in full: `Java_..._addGenericConstraint` builds the
joint via `GenericJointBuilder` (which leaves `local_anchor1`/
`local_anchor2` at their zero/origin default - the REAL anchor offsets,
`pos_a`/`pos_b` minus center-of-mass, are only installed later by a
SEPARATE function, `joints::tick`), then calls
`sim_data.impulse_joint_set.insert(rb_a, rb_b, joint.build(), true)` -
this is a genuinely real, previously-overlooked detail: the joint is
inserted with WRONG (origin-to-origin) anchors at `insert()` time.

**Discriminating check performed (not an asserted cause, per
instruction): does a native solve ever run against those wrong
default anchors before `joints::tick` installs the real ones?** Found
`joints::tick(scene)` is called from exactly one place that also calls
the actual solve, `Java_..._step` (the sole call site for
`sim.pipeline.step(...)`, confirmed by grep - no other call site
exists) - and `joints::tick` runs IMMEDIATELY BEFORE `pipeline.step()`,
in the same native function, every single invocation, no gap. So by the
time any solve happens, the real anchors are always already installed.

**Followed the SAME question into Rapier's own upstream source itself**
(fetched directly via `gh api repos/ryanhcode/rapier/contents/...?ref=
38e92f117590862481a53df6fc69a5d893e29186` - confirming external source
access works fine): `rapier3d`'s own `ImpulseJointSet::insert` (`src/
dynamics/joint/impulse_joint/impulse_joint_set.rs`) adds both bodies to
`to_wake_up` and the pair to `to_join` - its own doc comment: "A set of
rigid-body pairs to join in the island manager during the next
timestep" - a genuinely deferred operation, not immediate. Checked
`PhysicsPipeline::step` (`src/pipeline/physics_pipeline.rs`): both
`to_wake_up` and `to_join` are drained and applied at the very START of
`step()` (the "user_changes" stage), unconditionally, BEFORE collision
detection or any solving - every single `step()` call, regardless of
whether a body was just inserted this exact tick or has been resident
for thousands of ticks.

**Result: this specific discriminating question is answered NO, at
both the Sable-wrapper layer and the Rapier-core layer, with exact
source citations rather than inference.** Neither the deferred-anchor-
install mechanism in `joints.rs` nor the deferred-island-join mechanism
in `ImpulseJointSet`/`PhysicsPipeline` introduces any gap, and neither
treats a freshly-inserted body differently from an already-resident
one - both are flushed unconditionally before the first solve, every
time. This retracts the premature "all Java/Rust-side candidates
exhausted, must be external Rapier" framing from the previous
subsection - that framing was reached without having actually read
Sable's own in-repo Rust code at all, only grepped it for keywords. Now
having read it (and the relevant upstream Rapier source it calls into),
this SPECIFIC timing-gap mechanism is cleanly ruled out, not merely
assumed unreachable.

What remains unexamined, and is the next genuinely narrower candidate:
the actual numerical solver/constraint-resolution passes themselves
(warm-started impulse state, velocity/position solver iteration order)
once a joint IS correctly installed and its island correctly joined -
whether an already-stepped body carries forward solver state (e.g. from
its own prior settling) that interacts differently with a brand-new
joint's zero-initialized impulse than a freshly-inserted body (which has
no such history) would. Not yet investigated - this would require
reading the actual solver code
(`src/dynamics/solver/island_solver.rs`/`parallel_island_solver.rs` and
the velocity/position constraint types beneath them), a deeper dive than
what has been done so far.

**Three corrections, John's comment 54270 (read-only, no live run or
edit performed in response to this comment - exactly as instructed).**

1. **Cleanup-disposition claim was overbroad - corrected with per-probe
   evidence.** The retained `diag3`/`diag5` logs DO show a clean
   shutdown: `server.log` lines 183/240/252 (`diag3`) and 1045/1107/1119
   (`diag5`) read "released terrain chunk grid" / "Stopping server" /
   "ThreadedAnvilChunkStorage: All dimensions are saved" - confirmed by
   direct re-read, contradicting the earlier blanket "no probe... every
   run has been ended by killing the process" claim. Checked why:
   `BuoyantHitchProbe` (the probe both of those runs used) DOES call its
   own `unforceChunkGrid(level)` unconditionally, exactly once, the
   moment `phase` reaches `DONE` or `FAILED` (confirmed by reading its
   source directly, not assumed) - a real, working cleanup path this
   ticket's own earliest probe has had all along. What triggers
   "Stopping server" itself is NOT this probe's own code (no
   `server.halt`/stop call found anywhere in `BuoyantHitchProbe`) - an
   external mechanism (outside this Java source) sent that stop for
   those two specific runs; not fully traced, and not needed to correct
   the factual claim. **Checked the two NEWER probes this ticket wrote
   (`GroundHitchReplacementProbe`, `AlreadyActiveUnsafeFallbackProbe`):
   neither one calls `setChunkForced(..., false)` anywhere - confirmed
   by grep, zero matches for "unforce" in either file.** So the
   corrected, now per-probe-accurate claim is: `BuoyantHitchProbe`
   cleans up its own chunk-force grid correctly; the two newer probes
   this ticket added do not - a real, specific gap in code this agent
   wrote, not a ticket-wide pattern. `run4` (the sustained fallback
   probe PASS) specifically still lacks any release/shutdown receipt,
   as flagged - consistent with it using the newer, non-cleaning probe.

2. **Sable source revision correspondence - relabeled PROVISIONAL, not
   confirmed, per instruction.** The local checkout's own tag
   (`mc1.21.1-2.0.5-fabric-3-geb2a698`) is a FABRIC tag, three commits
   past it, which does not by itself establish NeoForge artifact/source
   correspondence - a fair catch. Checked what IS locally verifiable:
   the cached `sable-neoforge-1.21.1-2.0.5.jar`
   (`~/.gradle/caches/modules-2/files-2.1/dev.ryanhcode.sable/...`) has
   SHA-256 `c8710f85bc780bbf523e6726ceaaf76dd0b8c61479db497b382c6bb34317e7ab`
   - confirmed to match John's own independently-computed value, and it
   matches the PUBLISHED Gradle module's own declared checksum (read
   directly from the cached `.module` file) - so this IS genuinely the
   published 2.0.5 artifact, not a corrupted/substituted one. The
   `sable-sable_rapier-1.21.1:2.0.5` runtime dependency John flagged as
   having no corresponding Gradle cache directory is explained: it is
   NOT a separate resolved artifact at all for this build - it is
   bundled INSIDE `sable-neoforge-1.21.1-2.0.5.jar` itself via
   NeoForge's own jar-in-jar mechanism
   (`META-INF/jarjar/dev.ryanhcode.sable.sable-sable_rapier-1.21.1-2.0.5.jar`,
   extracted and separately checksummed:
   `f7a89044a47bf0b766b460c8784e5f81b255f6806177d8d357a8c3c01362961b`).
   That embedded jar's own manifest, README, and the vendored Rapier
   LICENSE file contain NO commit hash or other source-provenance
   marker anywhere - confirmed by reading all three directly. The
   actual native binary itself
   (`natives/sable_rapier/sable_rapier_binaries.zip.l4z`, LZ4-compressed)
   was NOT decompressed to search for an embedded build-id string (no
   `lz4` CLI or Python `lz4` module available in this sandbox, and
   installing one was not attempted further given the bounded, read-only
   scope of this check). **Conclusion: the loaded artifact is confirmed
   genuine (checksum-verified against the published module), but its
   correspondence to the specific git commit `eb2a698` this analysis
   read source from is, at most, a same-declared-version-number match
   (`2.0.5` on both sides) - not a cryptographically or provenance-proven
   exact match. The `joints.rs`/Rapier-core analysis above should be read
   as PROVISIONAL on that basis, not confirmed**, per instruction.

3. **Proposed discriminating measurement for joint impulse/warm-start
   initialization and existing-body solver state - NOT executed, no live
   run or edit performed, per explicit instruction to identify this
   BEFORE proposing either.** `ImpulseJointSet::insert` (read directly,
   same source as above) initializes every joint's own `impulses` field
   via `Default::default()` regardless of either body's history - so the
   JOINT's own warm-start state starts at zero identically for a fresh-
   vs-already-active pairing; no asymmetry there. The candidate that
   remains is BODY-side state: an already-active body free-floating for
   many ticks (no joint, no engine) accumulates real velocity/angular-
   velocity and positional/orientation drift from ordinary force
   integration (gravity, buoyancy, drag) that a freshly-constructed body
   (zero ticks old) never has time to accumulate - already partly
   evidenced by this file's own retained data (e.g. `TOWING_REPLACEMENT`
   tick 1: `anchorDistance=1.995` blocks, `towed.angularVelocity`
   already substantial). **Proposed measurement:** for a given reconnect
   event, compute the EXPECTED velocity/angular-velocity change a single
   rigid (fully linear-locked, undamped) constraint solve would produce
   from the KNOWN pre-solve anchor-gap (position error) and pre-solve
   velocity alone - via basic momentum/impulse arithmetic
   (impulse ≈ effective-mass × positional-error / `PhysicsBridge.DT`,
   per axis) - and compare that EXPECTED magnitude against the ACTUAL
   observed post-solve velocity jump (already capturable via the
   existing `debugReadLinearVelocity`/`debugReadAngularVelocity`
   accessors, one tick before and one tick after a reconnect).
   **Expected alternatives:** (a) if the observed jump is the same
   order of magnitude as the value basic momentum arithmetic from the
   KNOWN anchor gap/pre-solve velocity predicts, the explosion is the
   ordinary, mathematically-expected consequence of snapping a real,
   large position/velocity error with a fully rigid, undamped joint in
   one step - not a defect in Sable/Rapier at all, and the real fix
   would be at the DIV/joint-configuration level (a softer/staged
   engagement, not a "bug fix") - squarely back in this ticket's own
   scope, not Sable's; (b) if the observed jump is orders of magnitude
   LARGER than that basic arithmetic predicts, that is positive evidence
   of an actual solver-internal anomaly (warm-start poisoning, an
   incorrect effective-mass/inertia computation, or similar), and
   reading the actual solver code
   (`island_solver.rs`/`parallel_island_solver.rs`) becomes justified
   rather than speculative. This measurement requires no new live run
   for alternative (a)'s rough check (existing retained data already
   has some of the needed numbers) but a clean one-tick-before/one-tick-
   after capture of BOTH anchor-gap and velocity for the SAME event would
   make it rigorous - not run here, awaiting direction per instruction.

**Corrected calculation, John's comment 54278 - read-only, from retained
logs and identified source constants only; no new live run, build, or
edit.** The prior proposal's units were wrong (effective-mass × gap / DT
is an impulse, not a velocity) - corrected here via the actual
impulse-to-velocity/angular-velocity relations
(`Δv = J/m`, `Δω = I⁻¹(r×J)`), using ONLY the SAME pre/post-solve event
(the `TOWING_UNAVAILABLE` reconnect spike, `diag5run`'s own
`createHitchDiagnostic` lines 924/925 → `pre-remove()` line 929 - NOT
substituting `TOWING_REPLACEMENT`'s later ~1.995 gap, as flagged).

Identified source constants (read directly, not assumed): `m` = 400 kg
both sides (`run/config` `massKg`); local inertia, from
`BoxPhysicsObject.BoxMassData` (used whenever `massProperties == null`,
which is always true for a `BuoyantBody` constructed via the
scalar-mass constructor) = `diag(m/6, m/6, m/6)` = `diag(66.67, 66.67,
66.67)` kg·m² - **this is Sable's own FALLBACK formula, not a true box
inertia tensor from the half-extents; flagged as an assumption that
Rapier does not further recompute or override it internally, not
independently confirmed**; `r` (anchor lever arm from each body's own
origin/COM, since COM is `ZERO` under this same fallback) = the logged
`hitchLocalOffset=(0,-0.3,-1.0)` (towing) / `hookupLocalOffset=
(0,-0.3,1.0)` (towed), in each body's OWN LOCAL frame; `PhysicsBridge.DT`
= 0.05s (one call per Minecraft tick); the joint's own compliance,
`GenericJointBuilder::softness(JOINT_SPRING_FREQUENCY=550.0,
JOINT_SPRING_DAMPING_RATIO=4.0)` (read from `rapier/src/config.rs`) -
**this is a SOFT/spring constraint, not a perfectly rigid one; its
exact discretized response at `DT=0.05s` depends on solver internals
not derived here, only noted - 550 Hz is far above the ~20 Hz tick
rate, so a near-rigid response is plausible but not derived
rigorously.**

Measured, from retained logs: `Δv_towed = (-2.850, 8.270, -0.513)`,
`Δω_towed = (-73.1, -13.9, 88.0)` rad/s; `Δv_towing = (2.850, -8.896,
0.513)`, `Δω_towing = (-41.4, -12.5, -3.34)` rad/s (both over the SAME
single tick, confirmed by log line adjacency - 924/925 immediately
precede 929 with no other `createHitchDiagnostic`/step line for either
body in between).

**Bounded check performed, explicitly NOT assuming world ≈ local
orientation (flagged below as the single largest source of error in
this check) - this is a first-order consistency check, not a
rigorous derivation:**

1. Linear momentum: solving `J = Δv_towed × m` gives `J ≈ (-1140, 3308,
   -205) N·s`. Predicting `Δv_towing = -J/m = (2.85, -8.27, 0.513)`
   against the ACTUAL `(2.85, -8.896, 0.513)`: X and Z match closely;
   Y differs by ~7.6% - within the range independent per-tick forces
   (gravity, buoyancy, drag - NOT part of the joint impulse at all)
   acting on both bodies independently each tick would plausibly
   account for, not a large unexplained residual.
2. Angular: predicting `Δω_towed = I⁻¹(r_towed × J)` (using `r_towed` in
   its OWN local frame, i.e. ASSUMING near-identity orientation - the
   flagged assumption) gives `(-48.7, -17.1, -5.13)` against the ACTUAL
   `(-73.1, -13.9, 88.0)`: X and Y are the same order of magnitude and
   sign (ratio ~1.5x and ~0.8x); **Z is wildly different - wrong sign
   and ~17x too large.**

**This Z discrepancy is NOT reported as evidence of a solver defect.**
The single largest unaccounted variable in this check is each body's
ACTUAL orientation at the event - `towed` was spawned at a 180+20°
yaw and this check applied `r` UNROTATED (identity-orientation
shortcut), which is almost certainly wrong by the time of this event;
yaw rotation specifically mixes a lever arm's own X/Z components, the
exact axes where the discrepancy appears and is worst. A wrong-sign,
over-an-order-of-magnitude miss on exactly the axes a missing yaw
rotation would scramble is NOT distinguishable, from this data alone,
from a genuine solver anomaly - per instruction, comparable order of
magnitude on X/Y cannot prove absence of a defect, and the Z excess
alone cannot isolate a solver-internal cause, especially with this
specific, identified, unaddressed confound.

**Minimal missing measurements, listed rather than guessed:**
- Each body's actual orientation (quaternion) at the exact pre-solve
  tick of this same event - not currently logged anywhere, needed to
  rotate `r` into world frame correctly before repeating this check.
- Confirmation (not assumption) that Rapier's own internal mass/inertia
  representation for this body matches the `diag(m/6,m/6,m/6)` fallback
  derived from `BoxMassData`'s Java source, with no further native-side
  recomputation.
- The actual number of solver iterations/substeps `IntegrationParameters`
  applies within one `PhysicsBridge.DT`-sized `pipeline.step()` call -
  not retrieved.
- Each body's own external-force contribution (gravity, buoyancy,
  directional drag - computed independently by `BuoyantForces.apply`,
  separate from the joint impulse) over the SAME tick, to subtract from
  the measured `Δv`/`Δω` before attributing the remainder to the joint
  alone - not isolated here; the current check conflates all of it.

**ONE bounded discriminating test PLAN (not executed - a plan, not an
execution request):** for the SAME `TOWING_UNAVAILABLE` reconnect event
(preserving the original reproducer unchanged), add log-only
instrumentation reading each body's orientation quaternion alongside
the already-logged linear/angular velocity, at the same two points
(inside `createHitch`, and one tick later) - then redo this exact
calculation with `r` correctly rotated into world frame by each body's
own measured orientation. **Expected alternatives:** (a) with correct
orientation, X/Y/Z all become consistent (same order of magnitude and
sign as the single-impulse prediction) - supporting "this is the
ordinary, momentum-conserving consequence of a stiff joint correcting a
real error, confounded earlier only by the missing-orientation
shortcut," pointing any further fix toward DIV's own joint
configuration (softness/staging), not Sable/Rapier; (b) even with
correct orientation, a large, sign-flipped or order-of-magnitude
residual remains on one or more axes - supporting an actual
solver-internal anomaly, justifying the earlier-flagged
`island_solver.rs`/`parallel_island_solver.rs` read. Not run here,
pending direction.

**Finished bounded diagnostic plan, John's comment 54290 - read-only;
no run/edit/build performed in producing this plan itself.** Target
event unchanged: the SAME `TOWING_UNAVAILABLE` reconnect spike, via the
existing, unmodified `BuoyantHitchProbe`.

**New provenance/ordering facts established this pass (read-only,
directly resolve several previously-open items):**
- The ACTUAL native binary this worktree runs is present unpacked at
  `run/.sable/natives/sable_rapier_x86_64_linux.so` (SHA-256
  `442c1ffb2f13250caab0e9e26d8e0aa507bad7d401474a723670f8f76b26832f`).
  Its own embedded debug-symbol paths read directly via `strings`
  include `.../cargo/git/checkouts/rapier-b7330b82f1d0b14c/38e92f1/
  crates/rapier3d/.../dynamics/solver/joint_constraint/
  generic_joint_constraint.rs` - the short hash `38e92f1` matches the
  FULL pinned revision (`38e92f117590862481a53df6fc69a5d893e29186`)
  exactly. **This is a direct, artifact-level confirmation that the
  Rapier source read in the previous two entries (fetched via `gh api`
  at that same revision) is genuinely what this build compiled against**
  - stronger than the earlier same-version-number correspondence, though
  still not independent of the local Sable checkout's own Cargo.toml
  pin (which declares the identical revision) being accurate. Sable's
  OWN `joints.rs` appears in the binary's strings only as the relative
  path `rapier/src/joints.rs` (a path/local crate, not a hashed git
  checkout) - no commit hash recoverable for Sable's own wrapper
  specifically; that correspondence remains at the same-declared-version
  level, as already stated.
- `run/config/sable-server.toml` (this worktree's own live config)
  reads `sub_level_substeps_per_tick = 2` - confirms, rather than
  assumes, the previously-missing substep count.
- Traced WHERE Sable's own physics step is invoked relative to MTS/DIV's
  own vehicle entity tick (the previously-unestablished ordering this
  comment specifically asked for): `ServerLevelMixin` (Sable's own
  source) injects `SubLevelPhysicsSystem`'s own tick at the `HEAD` of
  `ServerLevel.tick(BooleanSupplier)` - i.e. BEFORE entity ticking (where
  `VehicleMovementMixin.div$move` → `PhysicsBridge.active` →
  `createHitch` runs) in the SAME Minecraft tick. **Consequence: a joint
  created during a vehicle's own entity tick is never solved during that
  same Minecraft tick - Sable's physics step for that tick already ran,
  at `HEAD`, before entity ticking began. It is first solved at the
  NEXT tick's `HEAD`-injected step**, which runs `substepsPerTick` (= 2,
  confirmed above) native substeps, each one immediately preceded by
  `joints::tick()`'s anchor installation (confirmed in the prior entry).
  So "one probe tick later" (the existing `pre-remove()` sample) is
  confirmed, by this ordering, to be AFTER exactly one Minecraft tick's
  worth of Sable physics (2 substeps), not an unverified assumption -
  this closes the "log adjacency alone does not establish no
  intervening steps" gap for THIS specific pair of samples.
- An EXISTING, currently-unused extension seam for finer bracketing:
  `SablePrePhysicsTickEvent`/`SablePostPhysicsTickEvent` fire once PER
  SUBSTEP from inside `SubLevelPhysicsSystem.tickPipelinePhysics`'s own
  loop. DIV does not currently subscribe to either (confirmed by grep -
  no match in this codebase). Subscribing here is the smallest addition
  that would bracket EACH of the 2 substeps individually, rather than
  only the full Minecraft tick - see the plan below.

**Per-quantity: existing seam, or explicit blocker/bound (not
measurement substituted as truth):**
- Linear/angular velocity, both bodies, both existing sample points:
  EXISTING (`debugReadLinearVelocity`/`debugReadAngularVelocity`,
  already in use for this exact event).
- Pose/orientation, both bodies, both sample points: MISSING but an
  EXISTING internal value to expose - `BoxPhysicsObject.getPose()` (the
  same cached pose `BuoyantBody.step()` already reads every tick via
  `box.updatePose()`). A new debug accessor mirroring the existing
  `debugCaptureBody` pattern, returning `box().getPose()` directly,
  would close this - a small, read-only addition, NOT made in this plan
  itself.
- World-space mass/inertia as Rapier internally represents it: BLOCKED.
  Confirmed by reading the binary's own exported JNI symbols directly
  (`strings`, filtered for `Mass`/`Inertia`): only `setMassProperties`/
  `setCenterOfMass` exist (write-only, used at box construction) -
  no `get` counterpart. The Java-side `BoxMassData` formula
  (`diag(m/6,m/6,m/6)`, `COM=ZERO`) is defensible as WHAT WAS SENT to
  Rapier at construction (confirmed by reading `BoxPhysicsObject`'s own
  `MassData` interface and its caller), not as independently confirmed
  of what Rapier internally still holds/uses after its own processing -
  marked as a bound, not a measurement, exactly as instructed.
- Joint frame/error/relative anchor velocity (the constraint's own
  computed error term each solve): BLOCKED - no existing Java-callable
  accessor exposes it. The exact solver files are now precisely located
  in the running binary's own embedded paths -
  `dynamics/solver/joint_constraint/generic_joint_constraint.rs` and
  `dynamics/solver/joint_constraint/joint_velocity_constraint.rs` - but
  not read in this pass. Reading them (read-only, no run) is the single
  most direct way to resolve this without a new live measurement at all.
- Integration timestep/substeps: CONFIRMED - `DT`=0.05s per Minecraft
  tick, `substepsPerTick`=2 (confirmed above), so substep `dt`=0.025s.
  Within-substep solver ITERATION count (Rapier's own
  `IntegrationParameters::num_solver_iterations` or equivalent) is still
  unconfirmed - a quick, bounded, read-only follow-up, not done here.
- Bias/softness: CONFIRMED - `JOINT_SPRING_FREQUENCY`=550.0,
  `JOINT_SPRING_DAMPING_RATIO`=4.0 (`rapier/src/config.rs`), now
  cross-confirmed via the embedded-path check above to be the actual
  running build's own constants, not merely the local checkout's.
- Force/torque contribution over the interval (gravity/buoyancy/drag,
  separate from the joint's own impulse): BLOCKED as a direct
  measurement (not separately logged anywhere) but COMPUTABLE as a
  bound from `BuoyantForces.apply`'s own known Java formula, evaluated
  substep-by-substep against the known pre-interval pose/velocity
  (forces are applied once per substep via `applyQueuedForces`, inside
  the same loop identified above) - a hand/script calculation, not a
  single clean number, and not performed in this pass.
- **Angular-constraint torque - correctly flagged as unresolved, not
  assumed.** Whether Rapier's `GenericJoint` solver decomposes
  linear/angular DOF independently when only `LINEAR_X/Y/Z` are locked
  (matching this ticket's own simple lever-arm model) or solves them in
  a coupled block that could introduce torque beyond simple `r×J`
  transfer is exactly the open question `generic_joint_constraint.rs`
  (now precisely located, not yet read) would resolve. Not assumed
  either way in this plan.

**What the complete measurement could actually distinguish, and its
remaining ambiguity, stated plainly:** even fully executed (orientation
logging added, recomputed with correctly-rotated lever arms), a
remaining residual on any axis would NOT by itself distinguish between
(a) a genuine solver-internal coupling in `generic_joint_constraint.rs`,
(b) the mass/inertia bound being wrong (blocked, not measured), (c) an
unaccounted substep-by-substep force contribution (a bound, not
measured), or (d) compounding effects of the softness/bias parameters
over 2 substeps that the simple single-impulse model does not capture
at all. Conversely, a clean match would only remove the single
currently-available piece of suggestive evidence - it would not prove a
defect absent, exactly as instructed.

**Concrete next decision proposed:** given that genuine ambiguity
persists even after full execution of the live-measurement half of this
plan, and that the actual solver files are now precisely located in the
confirmed-matching running binary, the recommended single next step is
a READ-ONLY read of `generic_joint_constraint.rs` (and, if needed,
`joint_velocity_constraint.rs`) to resolve the angular-coupling question
directly from source - BEFORE adding any new logging or running
anything live. If that read confirms a decoupled (lever-arm-only)
treatment matching this ticket's own model, the live-measurement half of
this plan (orientation logging) becomes the right next step to isolate
the remaining bounds. If it instead finds a coupled treatment, that
would directly explain the Z-axis residual from source alone, without
any new live run. Not performed in this pass - reported as the proposed
next decision, not executed.

**Authorized read of `generic_joint_constraint.rs`/
`joint_velocity_constraint.rs`, John's comment 54298 - read-only, no
run/build/instrumentation/edit performed.** Fetched both files at the
confirmed-matching Rapier revision via `gh api`.

**What the actual solver equations establish.** `GenericJointConstraint::
lock_axes` (in `generic_joint_constraint.rs`) builds ONE SEPARATE scalar
constraint row PER LOCKED AXIS - looping `for i in 0..DIM` (linear) and
`for i in DIM..SPATIAL_DIM` (angular) independently, calling
`lock_linear_generic`/`lock_angular_generic` once per set bit in
`locked_axes`. For THIS fixture's ordinary connection (`LINEAR_X|
LINEAR_Y|LINEAR_Z` only, no angular bits), this produces exactly 3
per-axis rows (X, then Y, then Z, in that loop order) and ZERO angular
rows - there is no explicit, separate angular-lock constraint for this
connection type, confirming the earlier assumption that angular change
comes only through the linear constraints' own coupling, not a direct
angular lock.

**However, each of those 3 rows is NOT a simple point-mass impulse.**
`JointConstraint::solve_generic` (`joint_velocity_constraint.rs`)
computes, per row, a SCALAR `delta_impulse` from the CURRENT
`solver_vel` state of both bodies, then applies it via BOTH
`lin_impulse = self.lin_jac * delta_impulse` (linear) AND
`ii_ang_impulse1/2 = self.ii_ang_jac1/2 * delta_impulse` (angular,
precomputed inverse-inertia×lever-arm Jacobian terms) - updating
`solver_vel.angular` as part of EVERY axis's own solve, not only via a
separate post-hoc `r×J` step. Critically, this happens SEQUENTIALLY
(Gauss-Seidel) - axis X's solve updates both bodies' linear AND angular
`solver_vel` state BEFORE axis Y's own `dangvel`/`dlinvel` terms (which
read that same, now-updated `solver_vel`) are computed, and likewise
into Z. Rapier's own default `IntegrationParameters` (read from
`integration_parameters.rs` at the same revision) runs this full 3-row
pass `num_solver_iterations` = 4 times per substep by default (NOT
confirmed whether Sable overrides this default - not checked in this
pass).

**This changes the diagnostic plan's own model, not its conclusion.**
The earlier hand check (`Δv_towed → J → Δω_towed` via a single combined
`r×J`) is now confirmed to be a genuine SIMPLIFICATION of the real
mechanism: the actual process is 3 sequential scalar per-axis solves,
each independently coupling into angular velocity, repeated across
(unconfirmed count of) solver iterations and 2 confirmed substeps. A
sequential, iterated, per-axis process can plausibly diverge from a
single simultaneous 3D impulse's prediction, particularly for large
errors/velocities - **but per instruction, this structural difference is
NOT concluded to explain the measured Z residual by itself.** Doing that
would still require the blocked/assumed quantities already listed
(actual orientation, actual per-axis effective masses/Jacobians from
real world-space state, the actual solver iteration count, and each
axis's own intermediate `solver_vel` state after the PRECEDING axis's
solve within the same iteration) - none of which are available from
retained data, and reading this source cannot retroactively produce
them, exactly as instructed.

**Four provenance/claim corrections to the previous two entries, John's
comment 54298:**

1. The embedded `38e92f1` debug paths are evidence of **Rapier lineage
   only** - they say nothing about Sable's OWN wrapper source
   (`joints.rs`) or its `config.rs` spring constants (`550.0`/`4.0`),
   which are Sable's own values, compiled separately from the Rapier
   dependency. That correspondence remains at the same-declared-version
   level stated earlier, not newly strengthened by this binary check.
2. **Tick-ordering claim upgraded from provisional to bytecode-confirmed,
   for the specific HEAD injection.** Extracted
   `dev/ryanhcode/sable/mixin/plot/ServerLevelMixin.class` directly from
   the cached jar (the actually-loaded artifact, not the separate source
   checkout) and disassembled it (`javap -v -p`): its
   `RuntimeVisibleAnnotations` show
   `@Inject(method=["tick(Ljava/util/function/BooleanSupplier;)V"],
   at=@At(value="HEAD"))` on `sable$tickPlotContainer`, which calls
   `ServerSubLevelContainer.tick()` when `tickRateManager.runsNormally()`
   - confirmed directly from the loaded bytecode, not assumed from
   source. The FURTHER link from `ServerSubLevelContainer.tick()` down to
   `SubLevelPhysicsSystem.tickPipelinePhysics`'s own substep loop was
   NOT independently re-verified at the bytecode level in this pass -
   that specific link remains sourced from the separate checkout only.
3. **`substeps_per_tick=2` is this worktree's CURRENT config, not a
   retained historical receipt for `diag5`'s own run.** No config
   snapshot was captured as part of that run's own retained evidence;
   whether it used the same value at that time is not established,
   corrected from the prior entry's unqualified phrasing.
4. **Mass/inertia relabeled: an assumption/input, not a numerical
   bound.** The Java-side `BoxMassData` formula is what DIV/Sable's own
   Java code SENDS to the native layer at construction - stating it as
   a "bound" implied a proven constraint on the true value, which it is
   not absent a read-back accessor (still blocked, as already stated).

**Still not established; still the next decision, unchanged in
substance:** reading these two files did not retroactively produce any
of the actual historical state this specific event would need (real
orientation, confirmed iteration count, confirmed native inertia). The
smallest next measurement remains what the prior entry already named -
logging orientation alongside the existing velocity capture for a NEW
occurrence of the SAME reconnect event - now with the correct
sequential-per-axis model in hand rather than the oversimplified
single-impulse one, but still not run in this pass.

**One further bounded read-only check (John's comment 54332: "sequential
scalar solves alone neither violate aggregate impulse accounting nor
explain the observed residual; distinguish net impulses/torques and
unknown state") - still no run/edit/build.** Checked whether Sable
overrides Rapier's own default iteration counts (previously flagged
unconfirmed): `PhysicsConfigData` (Sable's own Java config, read
directly) sets `solverIterations = 18` (Rapier's own upstream default
is 4), `pgsIterations = 2` (upstream default 1),
`stabilizationIterations = 2` (upstream default 1). This worktree's own
`run/config/sable-server.toml`/`sable-common.toml` do not override any
of these - same "current config, not a historical receipt for a past
run" caveat already stated for `substepsPerTick`.

**This matters for exactly the distinction John drew.** 18 outer
Gauss-Seidel iterations (plus 2 inner PGS passes each) is a heavily-
iterated solve; a well-posed linear system solved this many times by
Gauss-Seidel should converge close to the SAME aggregate result a
single simultaneous block solve would reach - the sequential,
per-axis mechanism found in the previous entry changes HOW the impulse
is computed en route, not, on its own, the converged END result enough
to explain a residual this large. **This narrows (does not eliminate)
the earlier structural finding as the likely explanation, and shifts
weight back toward the "unknown state" bucket** (real orientation,
real native inertia, real substep-by-substep force contributions) -
consistent with, and now better-supported than, John's own correction.

**Concrete bounded instrumented-run PROPOSAL (NOT authorized to execute
by this comment - prepared only, per explicit instruction).** This
specifies the smallest live measurement that would close the "unknown
state" gap for ONE occurrence of the SAME, unmodified
`BuoyantHitchProbe` reconnect event, and the complete restoration
procedure a run of it would need (addressing the per-run cleanup gap
directly, not just flagging it again):

*Measured quantities (new):* each body's own orientation quaternion
(via a new, read-only `PhysicsBridge.debugReadOrientation(v)` returning
`registry.get(v)`'s `box().getPose().orientation()` - mirrors the
existing `debugCaptureBody`/`debugReadLinearVelocity` pattern exactly,
same risk profile). Captured at the SAME two existing bracket points
already used for velocity (inside `createHitch`, and the probe's own
`pre-remove()` sample one Minecraft tick later) - no new bracket points
needed; the existing ones are already confirmed, this pass and the
prior one, to correctly bracket exactly one Minecraft tick of physics
(2 substeps, 18 iterations each).

*What remains missing/bounded even after this measurement, stated
up front:* world-space mass/inertia as Rapier internally holds it
(still blocked - no read-back accessor exists; the Java `BoxMassData`
formula stays an assumption/input, not upgraded by this run); each
substep's own external-force (gravity/buoyancy/drag) contribution
(still not isolated - would require a SEPARATE instrumentation pass
inside `BuoyantForces.apply` itself, deliberately NOT included here to
keep this one proposal minimal/single-purpose); the exact relative
anchor velocity/error terms Rapier's own solver computes internally
(still blocked - no accessor; resolvable only by further source
reading, not a live run).

*Expected alternatives, stated before any execution:* (a) recomputing
`Δω` with `r` correctly rotated by the MEASURED orientation (replacing
the identity-orientation shortcut) brings the prediction within the
same rough order of magnitude John's own "comparable order of
magnitude cannot prove absence of a defect" caveat already bounds -
narrowing the residual without eliminating remaining uncertainty from
the still-blocked mass/inertia and force-contribution items; (b) a
large, still-unexplained residual remains even with correct
orientation - strengthens (does not prove, absent the still-blocked
items) the case for a genuine solver- or Sable-side anomaly, and would
make isolating the force-contribution and native-inertia blockers the
next proposal, not a conclusion drawn from this run alone.

*Evidence preservation:* identical to every prior retained run in this
ticket - full raw `server.log`, `HEAD.txt`, `config.json`, and
`SHA256SUMS`, written to a new, separate
`/home/booswrit/div-evidence/MINECRAFT-545-547/<run-id>-<timestamp>/`
directory; no existing evidence directory touched or deleted.

*Complete owned-fixture/config/chunk restoration and orderly shutdown,
specified so it is no longer a gap in THIS run specifically* (the
concrete fix for the cleanup gap already identified against
`AlreadyActiveUnsafeFallbackProbe`, generalized here rather than
silently repeated): run via `BuoyantHitchProbe` ONLY (the one probe in
this ticket already confirmed, by direct source read, to call its own
`unforceChunkGrid(level)` unconditionally on `DONE`/`FAILED`) - not the
newer, non-cleaning probes. After the probe reaches a terminal phase and
its own chunk-release log line is confirmed in the live output, send an
explicit `stop` to the server's own stdin (the same mechanism, now
intentionally applied rather than relied on implicitly) rather than
letting a bounded `timeout` kill the process - producing the same
"Stopping server"/"All dimensions are saved" receipt `diag3`/`diag5`
happened to have, for every future run, not just those two. Lock
claim/release logged to `/tmp/div-gradle-slot.owner` exactly as every
prior run in this ticket. Only after a clean stop and lock release:
copy the run's complete log into the new evidence directory as above.

**John's comment 54337 authorized exactly this ONE run; executed.**
Acknowledging scope: one bounded instrumented rerun of the ORIGINAL
`BuoyantHitchProbe` reconnect scenario, under the existing lock, no
fixture/tuning substitution, no new worker.

Added `PhysicsBridge.debugReadPose` (explicitly documented as the box's
own cached `Pose3dc`, refreshed only by that body's own prior `step()`
call) and logged pose/world-space-anchor-position/`gameTime` at the
existing `createHitchDiagnostic` (both bodies) and the probe's own
`pre-remove()` sample. Pre-launch snapshot captured first: `HEAD`
(`bda28b0` + this diff), uncommitted diff, `run/config/*` files, and
checksums of the actual native binary/jar.

Ran under the exclusive lock (after a legitimate ~3-minute wait for two
other concurrent sprint agents' own test runs to finish - never forced).
One real operational mistake, corrected before any gradle process
started: the first FIFO-based stdin attempt deadlocked (opening a FIFO
for read blocks until a writer exists) - no gradle process ever
launched, no world/log ever created, nothing needed restoring; logged
honestly in `/tmp/div-gradle-slot.owner` and the stuck wrapper killed to
free the lock before retrying with a read-write FIFO open that doesn't
block. The actual run: `BuoyantHitchProbe` reproduced the known
`TOWING_REPLACEMENT` crash exactly as every prior run (`FAILED - left
bounded area`, tick 8) - same reproducer, unmodified. Sent an orderly
`stop` via the FIFO once the probe's own terminal phase and chunk-
release log line were confirmed; verified "Stopping server" / "All
dimensions are saved" / `BUILD SUCCESSFUL` in the log, then released the
lock (confirmed free via `flock -n`). No leftover `java`/`gradle`
processes; no `server.log`/`server.stdin` left in the worktree. Full
evidence retained at
`/home/booswrit/div-evidence/MINECRAFT-545-547/M545-authrun-probe2-20261116T220757Z/`
(corrected directory name - a real transcription error, confirmed by John's
comment 54350: the previous entry and comments cited a nonexistent
`...T140742Z` directory, apparently copying the LOCAL server-log timestamp
format (`14:07:42`) instead of the actual UTC directory name. `server.log`
SHA-256 `bb1857d536192d1a0e3e1cd6f18e7775bfcee750be5caec8643cbabfe710fdfc`,
independently confirmed by John against his own computation - matches.)
(server.log, pre-launch HEAD/diff/config/artifact-checksum snapshot,
post-run checksums).

**Coverage/verification gaps, per John's comments 54344/54346 (received
mid-run; the run had already started by 22:05 UTC, so per 54346's own
"do not interrupt/duplicate" instruction it was allowed to complete -
not re-run to patch these, reported honestly instead):**
- Both bodies' pose/anchor-world ARE captured at the RECONNECT bracket
  (`createHitchDiagnostic` logs both `towing` and `trailer`). At the
  POST bracket (`pre-remove()`), only `towed`'s pose was added -
  `towing`'s own pose at that second point was NOT captured. This run
  cannot speak to `towing`'s own orientation change across the interval,
  only `towed`'s.
- The `ONE_TICK_STALE`/`CURRENT_THIS_TICK` labels are based on source-
  level call-ordering reasoning (`active()` precedes `step()` in
  `VehicleMovementMixin.div$move`, and Sable's own physics step runs at
  `ServerLevel.tick()`'s `HEAD` - the latter bytecode-confirmed in an
  earlier entry, the former not independently re-verified via an actual
  event/tick marker AT this exact sampling site). `gameTime` DOES
  numerically confirm the two brackets are exactly one Minecraft tick
  apart (9703→9704) - that part is solid; the PRECISE staleness of the
  POSE value itself within that tick is still inferred from source, not
  from a dedicated marker - more honestly "assumed one-tick stale by
  call-ordering reasoning," not independently proven.
- The probe's own `pre-remove()` anchor-world calculation uses a
  HARDCODED `(0,-0.3,1.0)` offset rather than reading the actual
  `hookupLocalOffset` this exact connection computed (logged separately,
  inside `createHitch`) - it happens to match that logged value exactly
  for this fixture, but is not derived/verified from the connection
  object at that call site. A real, specific limitation, not fixed in
  this run.

**Exact cited operands, John's comment 54350 ("cite these exact
operands and show subtraction") - server.log line numbers from the
corrected directory above, values transcribed verbatim, not rounded
until the final subtraction step:**

- Line 930 (`towing`, `gameTime=9703`): orientation
  `(-0.563,-0.142,0.807,-0.11)` [x,y,z,w] - labeled `cachedPose
  (ONE_TICK_STALE)`, i.e. the box's own cached pose, last refreshed by
  `towing`'s own PRIOR `step()` call, not independently re-verified as
  exactly one tick old at this call site (per 54344/54346, already
  flagged). `linearVelocity=(0.04051,-0.06650,0.02474)`,
  `angularVelocity=(-0.002943,0.02401,-0.09756)`.
- Line 931 (`trailer`/`towed`, `gameTime=9703`): orientation
  `(0.148,0.11,-0.982,0.036)` - same `ONE_TICK_STALE` label/caveat.
  `linearVelocity=(-0.004217,-0.3266,-0.04360)`,
  `angularVelocity=(0.1194,0.06279,-0.03232)`.
- Line 935 (`towed`, `gameTime=9704` - one Minecraft tick later,
  confirmed by the numeric `gameTime` difference `9704-9703=1`, not by
  log-line adjacency): `towing.linearVelocity=(2.890,-8.962,0.5378)`,
  `towing.angularVelocity=(-41.40,-12.50,-3.443)`,
  `towed.linearVelocity=(-2.854,7.943,-0.5569)`,
  `towed.angularVelocity=(-73.00,-13.84,88.00)`. `towed.pose` is labeled
  `CURRENT_THIS_TICK` (this body's own `step()` already ran this tick
  before this sample, per the mixin ordering already cited) -
  `towing`'s own pose at THIS second point was not captured at all (the
  honest gap already reported).

**Subtraction, shown explicitly (not just final numbers):**
`Δv_towed = towed.linearVelocity@9704 - towed.linearVelocity@9703
 = (-2.854,7.943,-0.5569) - (-0.004217,-0.3266,-0.04360)
 = (-2.8498,8.2696,-0.5133)`.
`Δv_towing = (2.890,-8.962,0.5378) - (0.04051,-0.06650,0.02474)
 = (2.8495,-8.8955,0.5131)`.
`Δω_towed = (-73.00,-13.84,88.00) - (0.1194,0.06279,-0.03232)
 = (-73.12,-13.90,88.03)`.
`Δω_towing = (-41.40,-12.50,-3.443) - (-0.002943,0.02401,-0.09756)
 = (-41.40,-12.52,-3.345)`.

**Native inertia labeled explicitly as UNMEASURED, not a bound**:
`I = diag(m/6,m/6,m/6) = diag(66.67,66.67,66.67)` kg·m² is the Java-side
`BoxMassData` fallback formula alone - what DIV/Sable's own Java sends
to the native layer, not confirmed as what Rapier internally still
holds or uses (no read-back accessor exists, as already stated). Each
substep's own `BuoyantForces`-applied external force/torque over this
exact interval is likewise UNMEASURED - not isolated or bounded here
(see "ONE next decision" below for why no numerical bound is claimed).

**Retraction, John's comment 54350: "linear momentum does not tighten
merely by rotating lever arms."** Correct, and the prior entry's claim
that it did was wrong - rotating the lever arm `r` only enters the
ANGULAR prediction (`Δω=I⁻¹(r×J)`); the LINEAR check
(`J=Δv_towed·m`, predicted `Δv_towing=-J/m`) never used `r` at all, so
nothing about its own inputs changed between the identity-orientation
version and this one. Recomputing explicitly:
`J = Δv_towed · m = (-2.8498,8.2696,-0.5133) × 400 = (-1139.9,3307.8,-205.3)`
N·s (unchanged from the prior entry's own value, since `Δv_towed` and
`m` are unchanged). Predicted `Δv_towing = -J/m = (2.8498,-8.2696,0.5133)`
vs actual `(2.8495,-8.8955,0.5131)` - X/Z match closely (as they already
did before); Y's residual (`-8.2696` predicted vs `-8.8955` actual, ~7.6%)
is IDENTICAL to the prior entry's own number, for the identical reason
(independent per-tick forces, not isolated) - NOT an improvement
produced by this run's own new orientation data, as the prior wording
wrongly implied.

**Angular, with the lever arm now correctly rotated by the REAL measured
orientation (this part IS affected by orientation, unlike the linear
check above):** `Δω_towed` predicted `≈(-49.5,-18.0,-14.5)` vs actual
`(-73.12,-13.90,88.03)` (shown above) - one axis wrong-signed and ~6x too
large. `Δω_towing` predicted `≈(-12.6,-7.4,-49.4)` vs actual
`(-41.40,-12.52,-3.345)` - one axis right-signed but ~15x too small. The
residual PERSISTS substantially on both bodies even with the real,
correctly-rotated lever arm.

**Per John's explicit interpretation guidance, stated as given, not
reinterpreted:** orientation capture eliminates a major unknown but is
NOT itself a solver-defect discriminator; native inertia and unmeasured
force contributions remain unknown (labeled as such above, not
substituted); convergence is not inferred from iteration count;
sequential scalar solving alone is NOT a missing aggregate-impulse term
- this residual is NOT called a solver anomaly, and aggregate impulse
accounting remains valid under fixed Jacobians regardless of scalar
solve ordering. The persisting angular residual is consistent with
EITHER a genuine Sable/Rapier-side effect OR this simple model's own
remaining gaps (unmeasured native inertia, un-isolated per-substep
external force/torque contributions, and the true sequential multi-axis
solve structure found in an earlier entry) - not concluded as either.
Reproduction/non-reproduction: reproduced, exactly as every prior run.

**Cleanup/fixture disposition, corrected to precise claims, John's
comment 54350 ("correct 'no stray files/full cleanup' to precise gaps
unless receipts support it"):**
- Config identity: `run/config/sable-server.toml` and
  `dynamic-immersive-vehicles.json` both match their retained pre-launch
  snapshots byte-for-byte (confirmed independently by John too) - this
  specific claim IS supported by a receipt.
- World: this run REUSED the existing shared `run/world` directory, like
  every prior run in this ticket - NOT an isolated/disposable one
  (stated plainly, not left ambiguous).
- Chunk force: `BuoyantHitchProbe`'s own terminal-phase
  `unforceChunkGrid` call is confirmed in the log ("released terrain
  chunk grid") and the server then saved/stopped cleanly - this proves
  an orderly SHUTDOWN, not full fixture restoration.
- Fixture entities: `towing`/`towed`/`towing2` (the vehicles this run's
  probe spawned) were NOT explicitly removed by the probe at any point -
  it has no such cleanup path on `FAILED`, only the chunk-force
  unforce. Whatever final state they were in at the moment of the clean
  world save is what persists in `run/world` going forward - an actual,
  unremediated gap, not covered by "chunk-release/save."
- Other in-memory state (`ConfigSystem`'s own maps, etc.): resets
  naturally on process exit (a new JVM), as already noted in an earlier
  entry - not itself a persistent-world concern.
- The first, stuck FIFO attempt: `ps` confirmed, at the time it was
  killed, no `java` process existed anywhere in that process's own
  lineage (only the shell wrapper itself, blocked on the FIFO's own
  open() call) - `gradle`/the JVM never launched, so killing it affected
  only that wrapper shell, nothing resembling a running server or world.

**No further runtime/build/probe performed or proposed - this comment's
own "one-run grant is now consumed; NO further runtime/build/probe
authorized" is honored as stated.** The previously-proposed
`BuoyantForces` force/torque bound is NOT attempted: the actual inputs
it would need (submerged fraction/water-surface height at this exact
tick - not logged anywhere; confirmed per-float tuning constants for
this fixture - not independently re-verified in this pass) are not
available from retained data, so per instruction this is listed as a
missing-input blocker, not a speculative numerical claim.

## Gate-review packet for the one still-pending isolated instrumented rerun (54359)

John's comment 54359 reviewed my prior uncommitted diff LIVE, before I'd
even finished or reported it, and named four concrete gaps. "No run until
independent packet review releases it" (54359) is honored as stated: this
section is a PACKET for review, not a report of a run. No `runServer`
invocation that launches the actual Minecraft/Java process has been made
this round - only `prepareServerRun`/`createServerLaunchScript` (which only
write config/launch-script files; they do not start the JVM) were run,
specifically to verify item 1 below BEFORE any actual launch.

### Gap 1: isolated run directory, non-aliasing proof, resolved target verified pre-launch

- Directory created: `/home/booswrit/div-evidence/MINECRAFT-545-547/M545-isolated-rundir-20261116T224049Z/rundir`
  (never under this worktree's shared `run/`).
- Populated only with `config/` (8 files), `eula.txt`, `server.properties`,
  copied with `cp -L` (dereferencing any symlink) from the shared `run/`.
  No `world/` directory present - a fresh world will be generated there if
  this run is later authorized; it cannot alias `run/world`.
- Non-aliasing proof:
  - `realpath` on both directories differ (`.../minecraft-545/run` vs
    `.../div-evidence/.../rundir`).
  - Per-file `stat -c '%d:%i'` (device:inode) comparison for all 10 copied
    files: every pair is DISTINCT between original and copy - no hardlink
    aliasing.
- Input hashes recorded in
  `.../M545-isolated-rundir-20261116T224049Z/isolated-input-manifest.sha256`
  (SHA-256 of all 10 copied files, listed above in this session's tool
  output).
- Resolved Gradle run target, verified BEFORE Minecraft itself starts:
  - **Found and fixed a real defect in my own prior uncommitted diff**:
    `build.gradle`'s override set `workingDirectory`, a property that does
    not exist on ModDevGradle's `RunModel` - confirmed by actually running
    `prepareServerRun` with `DIV_ISOLATED_RUN_DIR` set, which failed
    configuration outright: "Could not set unknown property
    'workingDirectory' for Run[server] of type
    net.neoforged.moddevgradle.dsl.RunModel." This means the ORIGINAL
    version of this override, if it had been run, would have failed before
    ever reaching Minecraft - not a silent no-op, but it was never actually
    exercised until this check.
  - Fixed by decompiling the actual `RunModel.class` from the resolved
    `moddev-gradle-2.0.148.jar` via `javap` (not guessing from
    documentation) - the real property is `gameDirectory`
    (`DirectoryProperty`). Changed the override to
    `gameDirectory = project.file(System.getenv('DIV_ISOLATED_RUN_DIR'))`.
  - Re-ran `prepareServerRun createServerLaunchScript` with
    `DIV_ISOLATED_RUN_DIR`/`DIV_BUOYANT_HITCH_PROBE` set: `BUILD SUCCESSFUL`.
    The generated `build/moddev/runServer.sh` (inspected directly, not
    executed) resolves to
    `(cd '/home/booswrit/div-evidence/MINECRAFT-545-547/M545-isolated-rundir-20261116T224049Z/rundir'; exec '.../java' ...)`
    - the isolated directory, confirmed in the actual generated artifact,
    before any `java` process runs.
  - Checked that `prepareServerRun`'s own UP-TO-DATE status on the second
    run is not masking anything: its outputs are build-directory-scoped
    (classpath/assets files), not the game directory - confirmed the
    isolated `rundir/` contains only the 10 originally-copied files after
    this task ran, nothing written into it.
  - Re-ran plain `gradle test` with NO env vars set afterward:
    `BUILD SUCCESSFUL` - the default (shared `run/`) path is unaffected by
    this fix.

### Gap 2: `createHitch`'s own `ONE_TICK_STALE` label

`PhysicsBridge.createHitch`'s diagnostic log (both the towing and trailer
lines) and its surrounding comment previously asserted `ONE_TICK_STALE`
based on call-ordering reasoning alone. Per 54359 ("createHitch insertion
is not itself proof of when native impulse was applied"), both lines are
now labeled `cachedPose(UNVERIFIED_AGE)`, and the comment states plainly
that call-ordering shows only that this read happens before this tick's
own `step()` - not a precise substep-level age - matching the same
correction already applied to the probe's own log site in the prior round.

### Gap 3: `BuoyantForces` diagnostic tick/substep/event association

Both `buoyantForcesDiagnostic` log lines (per-float buoyancy/drag, and the
rotational-drag term) now include `gameTime=` (the real Minecraft tick
counter, `level.getGameTime()`) and an explicit `substep=UNAVAILABLE_PRE_SUBSTEP`
marker - stating, not omitting, that this call site runs exactly once per
Minecraft tick, before Sable's own native substeps, so no finer substep
index exists to attach here. Additionally, an explicit "else" branch was
added so that when the rotational-drag term does NOT fire (true for this
fixture, since `tuning.angularWaterDrag` defaults to 0/`Overrides.FALLBACK`
and this fixture sets no override), a log line explicitly confirms
`rotationalDrag=INACTIVE_THIS_TICK` rather than leaving that absence
silent/inferred.

### Gap 4: cleanup-branch and shutdown design (plan only, not executed)

Written to
`.../M545-isolated-rundir-20261116T224049Z/CLEANUP_PLAN.md`:
- Shutdown: reuse the already-proven FIFO read-write-open pattern
  (`exec 3<>fifo` before launch, write `stop` to fd 3 once the probe's own
  COMPLETE/CRASHED marker or a tick ceiling is observed in the ISOLATED
  directory's own `logs/latest.log`), the same mechanism that produced the
  clean "Stopping server / All dimensions are saved" shutdown evidence in
  `M545-authrun-probe2`.
- Cleanup: reduced to a single binary choice over the WHOLE isolated
  directory (RETAIN by default, as evidence, consistent with "do not
  alter/delete evidence" - or DELETE only on explicit request), because the
  isolated directory shares no path/symlink/hardlink with `run/` or any
  other retained evidence directory (proven in Gap 1) - no per-entity
  fixture remediation is needed, unlike the still-open gap in the prior
  shared-`run/`-world cleanup disposition noted above.

### Validation performed this round

- `gradle test`: `BUILD SUCCESSFUL` after all four gaps above were
  addressed in the uncommitted diff (claimed/released
  `/tmp/div-gradle-slot.lock` each time, logged in
  `/tmp/div-gradle-slot.owner` as `M545-gatefix-test`/`M545-isolated-dryrun`/
  `M545-isolated-dryrun2`/`M545-gatefix-test2`).
- `prepareServerRun`/`createServerLaunchScript` (config/script generation
  only, no JVM launch) under the same lock, twice: once reproducing the
  `workingDirectory` failure, once confirming the `gameDirectory` fix
  resolves to the isolated directory.
- No `runServer` (actual Minecraft launch) was performed. No existing
  evidence directory, world, or the shared `run/` directory was read from
  or written to by this round's isolated-directory work.

### What is NOT yet done

- The isolated diagnostic run itself has not been launched - per 54359,
  that requires a separate, later authorization after this packet is
  reviewed.
- This diff (build.gradle `gameDirectory` fix, `PhysicsBridge`/`BuoyantForces`/
  `BuoyantHitchProbe` relabeling and association changes) is about to be
  committed alongside this VERIFICATION.md entry, as its own commit.

## 54365 follow-up: real freshness markers, corrected substep claim, corrected cleanup predicate

John's comment 54365 reviewed the prior packet: approved the isolated-
directory/non-aliasing work and the gameDirectory fix, but found three
further concrete gaps on top of 54359's four. Addressed here, still no
Minecraft launch performed.

**(1) Real pose-freshness markers, not an inferred label.** Added a plain
diagnostic field, `debugLastPoseUpdateGameTime`, to both `BuoyantBody`
and `PhysicsBridge.Body`, set immediately after their own
`box.updatePose()` call to `level.getGameTime()` - the REAL tick at
which that body's cached pose was last refreshed, not inferred from call
order. Exposed via a new `PhysicsBridge.debugLastPoseUpdateGameTime(v)`
accessor. `createHitch`'s diagnostic log and `BuoyantHitchProbe`'s own
`TOWING_UNAVAILABLE` pre-remove() bracket now log this value alongside
the read's own `sampleGameTime`, so staleness is a checkable comparison
in the log, not an asserted label (`UNVERIFIED_AGE` is gone). Also found
and fixed a real correctness error while doing this: `createHitch`'s
companion javadoc (`debugReadPose`) still asserted the pose sample is
"EXACTLY one tick stale" from call-ordering alone - the same overclaim
54359 already corrected in the log line itself, missed in the javadoc.
Corrected to point at the new accessor instead of asserting an age.

**(2) Corrected, and previously WRONG-direction, substep-scheduling
claim.** The prior round's `BuoyantForces` diagnostic comment claimed this
method runs "before Sable's native substeps run." Re-checked against this
ticket's own established facts (`ServerLevelMixin` injects Sable's native
step at the HEAD of `ServerLevel.tick()`, strictly BEFORE entity ticking,
and `BuoyantForces.apply` runs FROM entity ticking) - the claim had the
direction backwards: this tick's native substeps have ALREADY run by the
time this line executes; the impulse applied here is consumed by the NEXT
tick's substeps, not this tick's. Fixed the comment and the log's own
label to state that single known event-phase fact, with
`substepIndex=UNAVAILABLE` logged as an explicitly separate field (per
54365: "log actual known event phase and unavailable substep index
separately," not one merged/inferred label). Same correction applied to
`createHitch`'s own log (known event phase: before this tick's own
`step()`; substep index: unavailable - logged as two fields, not one).

**(3) Corrected cleanup terminal predicate + prelaunch manifest, in
`/home/booswrit/div-evidence/MINECRAFT-545-547/M545-isolated-rundir-20261116T224049Z`:**
- `CLEANUP_PLAN.md` rewritten: the real terminal predicate is
  `[DIV buoyantHitchProbe] (COMPLETE|FAILED)` (confirmed verbatim against
  this exact scenario's own prior log line, `M545-authrun-probe2/server.log`
  line 1050: "FAILED - left bounded area ... phase=TOWING_REPLACEMENT"),
  not a generic COMPLETE/CRASHED placeholder. Added explicit, bounded
  startup-failure (180s with no log at all) and no-progress (900s total)
  handling. Retention is now UNCONDITIONAL - no delete branch at all, per
  54365's explicit correction.
- `launch-wrapper.sh` (new, executable, NOT yet run): the actual bounded
  launch/monitor/orderly-stop commands, not a description of them - FIFO
  read-write open, polls for the real terminal pattern or either bound,
  sends `stop\n` via the process's own stdin only (never a signal, never
  a peer process), and if an orderly stop doesn't exit within 120s it
  STOPS (exit 3) rather than escalating to SIGKILL automatically - a
  human/coordinator decision point, not an automatic destructive action.
  Lock lifetime documented: the caller claims/releases
  `/tmp/div-gradle-slot.lock` around this wrapper; the wrapper itself
  never touches that lock.
- `MANIFEST.md` (new) indexes the fuller prelaunch manifest 54365 asked
  for: `generated-launch-artifacts.sha256` (hashes of the actual
  `runServer.sh`/classpath/VM-args/program-args files), 
  `effective-classpath-entries.txt` (101 entries, one per line),
  `compiled-instrumentation.sha256` (SHA-256 of every compiled `.class`
  under `io/github/brooswitminecraft/div`, taken AFTER a fresh
  `gradle test` compile of this round's edits - the actual bytecode, not
  a source-level claim), and baseline hashes of the ORIGINAL (shared)
  `run/config`/`run/world/level.dat` for comparison.
- Verified (not merely asserted): VM args file contains ONLY this probe's
  own two flags, no other probe's; none of the three generated args/
  classpath files reference the shared `run/` path (grepped, zero
  matches); the 8 copied config-file hashes match the CURRENT original
  `run/config` hashes (re-verified live, not just at copy time); the
  resolved full isolated world path
  (`/home/booswrit/div-evidence/MINECRAFT-545-547/M545-isolated-rundir-20261116T224049Z/rundir/world`,
  from `server.properties`' `level-name=world`) does not exist yet.

**Validation:** `gradle test` BUILD SUCCESSFUL after each source edit in
this round; `prepareServerRun createServerLaunchScript` re-run once more
under the lock after recompiling, to regenerate the launch artifacts
against the current commit's compiled classes (confirmed still resolves
to the isolated directory). Lock claims/releases logged in
`/tmp/div-gradle-slot.owner` as `M545-gatefix2-test`,
`M545-isolated-dryrun3`, `M545-gatefix3-test`.

**Not yet done:** the isolated diagnostic run itself - still gated on
independent review of this packet.

## 54368 follow-up: self-contained lock ownership in the wrapper, real classpath-byte hashes

John's comment 54368: pose-refresh marker and event-phase separation from
the prior round accepted as progress, no broad new requirements. Two
narrow remaining checks, both in the isolated-run packet at
`/home/booswrit/div-evidence/MINECRAFT-545-547/M545-isolated-rundir-20261116T224049Z`, no source-code changes this round (git
status is clean on the worktree):

**(1) Lock ownership/child-result correction.** The prior `launch-wrapper.sh`
left lock ownership to an external caller and `exit 3`ed after an
orderly-stop timeout while merely asserting the slot "stays held" - an
unenforced claim. Rewrote the wrapper to claim/release
`/tmp/div-gradle-slot.lock` ITSELF (own `flock`,
`CLAIMED`/`RELEASED` lines in `/tmp/div-gradle-slot.owner`) and removed
the `exit 3` path entirely: if an orderly stop doesn't finish within its
120s wait, the script does not exit - it keeps the lock held and keeps
polling `kill -0 $JAVA_PID`, logging `STILL_WAITING_FOR_JAVA_EXIT` every
30s, until the one java process it launched is actually gone, for EVERY
terminal reason (clean marker, startup failure, no-progress timeout, or a
stuck orderly stop) - never a different process, never a signal ("no peer
signals/lock changes"). The script's own final exit code is now the real
`wait "$JAVA_PID"` exit code ("preserve actual child result"), not an
internal wrapper status.

**(2) Real classpath-byte hashes, not just a path list.** The prior
`effective-classpath-entries.txt` listed paths only. Also found and fixed
an extraction bug while redoing this: the prior 101-entry count included
the literal `-classpath` flag token itself as a bogus "entry" (an
artifact of naively splitting the whole @-args file on `:`, rather than
just its actual path line) - corrected to the real 99 entries. Added
`effective-classpath-hashes.sha256`: SHA-256 of every jar file directly,
and of every file found inside the 2 directory entries
(`build/classes/java/main`, `build/resources/main` - confirmed these are
the SAME 2 directories `-Dfml.modFolders` points at, so this already
covers "relevant resource directories/mod inputs" without a separate
list) - 244 hashed lines total. Cross-checked live: this worktree's own
instrumented `PhysicsBridge.class`/`BuoyantForces.class` hashes here are
IDENTICAL to the ones already recorded in `compiled-instrumentation.sha256`
- confirming the effective classpath will load the exact bytecode already
verified there.

**No Minecraft launch was performed.** Still gated on independent packet
review, per "No run until independent packet review releases it."

## The one authorized isolated diagnostic run (54371/54372/54373) - executed, non-reproduction

John's comment 54371 accepted the gate-review packet and authorized EXACTLY
ONE isolated original-reproducer run via the prepared `launch-wrapper.sh`.
This run was executed at `/home/booswrit/div-evidence/MINECRAFT-545-547/M545-isolated-rundir-20261116T224049Z`. This
section reports it per 54371/54373's own instructions: same-event
accounting with phase/refresh-aware operands and native unknowns,
reproduction/non-reproduction WITHOUT a solver-cause claim, the stale
terminal-wording reconciliation 54373 asked for, and the omitted-phase
limitation. **This consumes the one-run grant - no retry or additional run
without new direction.**

### Execution

Launched `launch-wrapper.sh` directly (no redundant outer `flock`, per
54371 - the wrapper owns its own lock). Lock claimed by `M545-isolated-run1`
at 2026-11-16T23:18:07Z, released at 2026-11-16T23:19:47Z, both logged in
`/tmp/div-gradle-slot.owner`. Wrapper's own terminal-pattern match:
`[DIV buoyantHitchProbe] COMPLETE - ATTACH/DETACH/TOWING_UNAVAILABLE/
DESPAWN_TRAILER/LEVEL_UNLOAD/INSTALLED_PART_ATTACH all passed
(TOWING_REPLACEMENT disclosed as not attempted...)` (log line 6141). Java
exited with code 0; `Stopping server` (line 6205) and `All dimensions are
saved` (line 6217) both confirmed present. Isolated world retained
UNCONDITIONALLY at `/home/booswrit/div-evidence/MINECRAFT-545-547/M545-isolated-rundir-20261116T224049Z/rundir/world`
(9.3M). Post-run re-verification: the original (shared) `run/config`'s 8
files and `run/world/level.dat`/`level.dat_old` both STILL match their
pre-run baseline hashes exactly - the isolated run touched nothing shared.

### Reconciling the stale terminal wording (54373)

The COMPLETE line's `(TOWING_REPLACEMENT disclosed as not attempted...)`
is a FIXED string, always printed verbatim at that point in
`BuoyantHitchProbe.java` (line 677) regardless of what actually happened -
it is NOT a report on this run specifically. Checking the actual log: there
are 32 `TOWING_REPLACEMENT`-tagged lines in this run (log lines
2125-3683) - the phase genuinely started (2125), the fresh `towing2` was
genuinely connected to `towed` (2126-2128), anchor distance was tracked
tick-by-tick (2129 through 3682, reaching tick=140 with
`anchorDistance=0.04569687473078841`), and it ended with an explicit
`TOWING_REPLACEMENT PASS` (line 3683) - NOT a crash. So
TOWING_REPLACEMENT genuinely ran for the MAIN pair and genuinely settled.
The fixed string's actual referent (confirmed via the class's own doc
comment, lines 625-641) is the SEPARATE `installedPartAttach` pair
(`towing3`/`towed3`) - by design, that pair's own hitch is never put
through a replacement cycle at all, sidestepping the still-unresolved
TOWING_REPLACEMENT interaction entirely for that specific proof. The fixed
string is honestly confusing out of context (it reads as if
TOWING_REPLACEMENT itself was skipped for everything) and is flagged here
as a real, PRE-EXISTING clarity gap in the probe's own log text - not
something newly introduced by this round's edits, and not altered by this
report.

### Non-reproduction (not a fix claim)

This run's TOWING_REPLACEMENT phase did NOT reproduce the known runaway/
out-of-bounds crash (the `FAILED - left bounded area ... phase=
TOWING_REPLACEMENT` outcome seen in every previously retained crash, e.g.
`M545-authrun-probe2`). anchorDistance settled to 0.0457 blocks by
tick=140 (well under `ANCHOR_TOLERANCE`) instead of diverging.

**Source/compiled code is confirmed unchanged for the exercised phase**:
`git diff 077d6a6..75af6b4` (077d6a6 is the commit `M545-authrun-probe2`
ran, the last confirmed crash) touching `BuoyantHitchProbe.java`,
`PhysicsBridge.java`, `BuoyantForces.java`, `BuoyantBody.java` shows
ONLY: new log fields/relabeled comments, one plain diagnostic-bookkeeping
field write (`debugLastPoseUpdateGameTime`), and new debug-only static
accessors never called from production code - confirmed by direct
inspection of every hunk, not merely by description. The
`towingReplacement()` method itself (and `createHitch`'s actual
connection-forming logic) has ZERO lines changed in that diff. So this is
NOT explained by any code change between the last crash and this run.

**Environment/retained-state is genuinely different, uncertainty stated
explicitly, no causal claim (per 54373's direction):** this run used a
FRESHLY GENERATED isolated world (`level-seed=` is empty in
`server.properties`, so a brand-new world gets a random seed; this
isolated `rundir` had no `world/` before this run) - unlike every prior
crash-reproducing run, which used the SAME long-reused, already-populated
`run/world`. Checked whether this plausibly matters: `BuoyantHitchProbe`
builds its OWN deterministic pool/platform (`buildPlatform`, fixed
absolute `PLATFORM_Y`, explicit stone/water/air block placement) rather
than relying on natural terrain, so the water geometry itself should NOT
differ by seed - checked via source, not assumed. What is NOT checked or
ruled out: whether a brand-new world's first-time chunk generation at
those coordinates introduces timing differences, whether the shared
world's many prior sessions left ANY accumulated state (chunk/entity/
region data) that could matter, or whether the native solver/Rapier has
any run-to-run nondeterminism independent of world identity at all. None
of these is claimed as the cause - they are named as open, unverified
candidates, exactly as uncertain as they were before this run, and
explicitly NOT "physics fixed."

### Same-event accounting, phase/refresh-aware (TOWING_UNAVAILABLE
reconnect-adjacent bracket - NOT the TOWING_REPLACEMENT phase itself,
which has no comparable reconnect-adjacent bracket logged)

At the TOWING_UNAVAILABLE pre-remove() bracket (log line ~2061,
`sampleGameTime=204`, `knownEventPhase=ServerTickEvent.Post(after-entity-
ticking)`, `substepIndex=UNAVAILABLE`):
- `towing` (99d17011): `linearVelocity=(2.890, -8.962, 0.538)`,
  `angularVelocity=(-41.40, -12.50, -3.443)` rad/s,
  `lastPoseUpdateGameTime=203` (ONE tick behind this tick's own
  `sampleGameTime=204` - towing's own `step()` had NOT yet refreshed its
  cached pose by this read, an observed fact, not explained here).
- `towed` (05fddcea): `linearVelocity=(-2.854, 7.943, -0.557)`,
  `angularVelocity=(-73.00, -13.84, 87.99)` rad/s,
  `lastPoseUpdateGameTime=203` (same one-tick lag).
- Both bodies' `lastPoseUpdateGameTime` match each other at 203, but
  BOTH lag this read's own `sampleGameTime=204` by one tick - a real,
  measured asymmetry/staleness, logged via the new freshness markers (not
  inferred from call-ordering), and NOT explained here (native cause
  unknown).
- Immediately prior (createHitch's own log, `sampleGameTime=203`):
  `towing.lastPoseUpdateGameTime=203` (matches its own sample tick -
  fresh), `trailer(towed).lastPoseUpdateGameTime=202` (one tick behind
  ITS sample tick) - confirming the two bodies' own refresh timing is not
  simply "always in lockstep," independent of the later observation above.
- Large angular-velocity spikes on BOTH bodies are present at this
  bracket in THIS run - the SAME kind of spike magnitude historically
  associated with the eventual TOWING_REPLACEMENT divergence in other
  runs - but this run's TOWING_REPLACEMENT nonetheless settled rather than
  diverging. This means the TOWING_UNAVAILABLE-adjacent angular spike
  ALONE is not sufficient to predict a later TOWING_REPLACEMENT failure -
  an observation, not a mechanism.

Per-float impulse accounting immediately before this bracket (gameTime=
202/203, both bodies, log lines ~2040-2060): buoyancy-only and drag-only
forces and the resulting applied impulse are fully itemized per float
(e.g. towing's float at `floatLocalOffset=(0.5,-0.2,0.0)`, gameTime=203:
`submergedFraction=0.504`, `buoyancyOnlyForce=(0,1778,0)`,
`dragOnlyForce=(2.61,61.99,-2.66)`, `appliedImpulse=(0.131,92.02,-0.133)`
- full per-float lines for both bodies at both ticks are in the retained
log, not reproduced in full here). Rotational water drag confirmed
INACTIVE for both bodies at both ticks (`angularWaterDrag=0.0`), matching
this fixture's own design (no override sets it above the 0 default).

**Native unknowns, stated not silently omitted:** Rapier's own internal
per-substep applied impulse/constraint force, and its internal
mass/inertia representation, remain UNREAD at this bracket (no accessor
exists) - only the Java-side computed/applied impulses above are
available. `substepIndex` stays explicitly `UNAVAILABLE` throughout, as
already established.

### Omitted-phase limitation (pre-existing design fact, not new)

`INSTALLED_PART_ATTACH`'s own separate `towing3`/`towed3` pair NEVER
exercises TOWING_REPLACEMENT by design (confirmed via the class's own doc
comment) - its PASS in this run is evidence about the installed-part
hookup-connection composition only, and says nothing about the main
pair's TOWING_REPLACEMENT behavior either way.

### Disposition

One-run grant is now CONSUMED. No retry or additional run is performed or
proposed without new direction, per 54371/54373. No instrumentation,
physics, or fixture changes were made as part of this run or this report.
All evidence (isolated world, server.log, wrapper stdout) is retained
UNCONDITIONALLY at the path above.

## Bounded read-only comparison: isolated (non-reproducing) run vs. authrun-probe2 (crash) (54379)

John's comment 54379: one bounded, READ-ONLY comparison/accounting between
the successful isolated run and the prior `authrun-probe2` crash. No
runtime/build/physics change; nothing executed this round beyond reading
the two already-retained logs.

### First available divergence, by phase-local tick

Comparing `TOWING_UNAVAILABLE tick=N towed.linearVelocity/angularVelocity`
(the only field logged in BOTH runs across this whole phase - towing is
despawned so has no comparable state here) tick-by-tick:

- **Ticks 1 through 59 are IDENTICAL between the two runs** (to the
  logged/displayed precision) - e.g. tick=59:
  `towed.linearVelocity=(7.543E-2 -4.489E+0 -2.021E+0)
  angularVelocity=(-3.431E+1 -1.786E+1 2.703E+1)` in BOTH logs
  (`authrun-probe2/server.log:997`, isolated `logs/latest.log:2123`).
- **First divergence is at tick=60** - the SAME tick that also fires the
  phase-transition (`TOWING_UNAVAILABLE PASS ... spawning towing2`) and
  the subsequent `TOWING_REPLACEMENT pre-connect`/`pre-connectTrailer`
  calls, all within this one tick:
  - prior (`server.log:998`): `towed.linearVelocity=(7.543E-2 -5.039E+0
    -2.021E+0) angularVelocity=(-6.341E+0 -3.029E-2 -1.173E-1)`
  - isolated (`logs/latest.log:2124`): `towed.linearVelocity=(1.976E-1
    -4.881E+0 -2.441E+0) angularVelocity=(-5.812E+0 -6.604E-1 -2.379E-1)`
- Checked one candidate mechanism for this specific tick-60 divergence
  (towed drifting past the probe's own deterministically-built pool into
  natural, seed-dependent terrain): does NOT appear to hold. `towed`'s
  position at the immediately-following `TOWING_REPLACEMENT pre-connect`
  line is `(17608.956, 105.682, 17605.683)` in BOTH runs (position itself
  still matches here even though native velocity already differs) -
  displaced only ~0.5-1.2 blocks from its spawn point
  `(17608.5, ~105.5, 17604.5)`, far inside `POOL_RADIUS=14` and within the
  platform's own explicitly-placed block range (`PLATFORM_Y=100` through
  `PLATFORM_Y+WATER_DEPTH+6=111`) - so this is NOT explained by leaving
  the deterministic, seed-independent pool. No other mechanism for this
  specific tick is confirmed; left as unexplained, not attributed.
- Connection/entity IDs are necessarily different UUIDs each run (fresh
  random identity per spawn) - noted as an expected, uninteresting
  non-divergence, not evidence of anything.
- From tick=60 onward both logs diverge further and qualitatively at
  TOWING_REPLACEMENT tick=2-3 (prior: `towing2.speed=260`blocks/s runaway
  by tick=3; isolated: `towing2.speed=0.15` blocks/s, settling) - already
  reported in the non-reproduction section above.

### A real, previously-unidentified confound: logging volume itself differs sharply between the two compared runs

Per John's own caution ("logging can perturb timing... does NOT prove zero
behavioral influence"), checked directly rather than assumed negligible:

- `authrun-probe2/server.log` (the crash run, 1136 total lines) contains
  **ZERO** `[DIV buoyantForcesDiagnostic]` lines - that logging did not
  exist yet at the commit it ran (predates this ticket's 371e63f).
- The isolated (non-crash) run's `logs/latest.log` (6217 total lines)
  contains **3445** `[DIV buoyantForcesDiagnostic]` lines alone, and 5933
  total DIV-diagnostic lines overall (vs. 872 in the crash run) - roughly
  a 6.8x increase in diagnostic logging volume between the two runs being
  compared.
- This is a REAL, measured difference, not a hypothetical one: the two
  runs do NOT differ ONLY in "fresh isolated world vs. reused shared
  world" - they also differ in how much synchronous logging I/O happens
  on the server thread every single tick. This cannot currently be
  separated from the world-identity difference with the data in hand -
  **both remain open, unattributed candidates**, exactly as uncertain as
  before this comparison, per the standing instruction not to claim a
  cause.

### Isolated run's own per-float impulse accounting over the TOWING_REPLACEMENT bracket (no cross-run comparison possible - field missing, not substituted)

The crash run (`authrun-probe2`) has NO `buoyantForcesDiagnostic` lines at
all for this or any phase, so NO comparable per-float impulse data exists
for it. Per instruction, this missing field is reported as missing, not
invented or substituted. The sum below is therefore descriptive of the
isolated run ALONE:

Bracket: `logs/latest.log` lines 2129 (TOWING_REPLACEMENT tick=1) through
3683 (tick=140, `PASS`), inclusive both endpoints. All `appliedImpulse`/
`worldArm` values summed are WORLD-FRAME quantities exactly as logged
(confirmed from source: `BuoyantForces.apply` logs `force` and `arm`
BEFORE the `inverse.transform(...)` that converts them to body-local for
the actual native `applyImpulseAtPoint` call) - NOT the native solver's
own measured response (no read-back of Rapier's resulting velocity change
is included; this is the COMMANDED Java-side impulse only). Pose-refresh
age: these lines do not themselves carry a `lastPoseUpdateGameTime` field
(that marker exists only on the `createHitch`/probe reconnect-bracket
logs) - not claimed or substituted here either.

- `towed` (05fddcea): 278 per-float log lines, `gameTime` range
  [264, 402]. SUM commanded linear impulse (world) =
  `(-69.468, 50363.400, 1428.443)`. SUM lever-arm angular impulse
  (`Σ worldArm × appliedImpulse` per float-tick, world frame) =
  `(6092.755, 140.064, 4638.743)`.
- `towing2` (c27f6492): 272 per-float log lines (fewer than towed's 278 -
  consistent with one or more early ticks where a float's own
  `submergedFraction<=0` suppressed that line, per the source's own
  `if (fraction <= 0) continue`, not a gap in logging), same `gameTime`
  range [264, 402]. SUM commanded linear impulse (world) =
  `(0.936, 48035.800, 1.620)`. SUM lever-arm angular impulse =
  `(16515.811, -0.891, 17175.995)`.
- Missing/excluded from both sums: gravity, the joint's own constraint
  impulse, propeller thrust (inactive for this fixture), and the native
  solver's own resulting velocity change - none of these is available
  from retained data for this bracket, stated as missing rather than
  assumed zero or substituted. Rotational water drag is confirmed
  genuinely inactive throughout this bracket (`angularWaterDrag=0.0`
  logged every tick, both bodies), so its absence from the sum is a
  confirmed zero, not a gap.

### Decision (not another run)

The retained evidence does **not** cleanly narrow the next experiment to
"environment/history" vs. "fresh-run variability" as a clean two-way
choice - it surfaces a THIRD, independently verified difference (the
~6.8x logging-volume increase between the two compared runs) that
confounds both candidates and was not previously accounted for. Given
that, the one actionable next-step decision is about EXPERIMENT DESIGN,
not a new run: any future comparison run should be deliberately
logging-matched between arms (same diagnostic verbosity in both) so that
world-identity/environment can be isolated from logging-volume effects,
rather than attributing a result to either while they remain entangled.
This is a design note for whenever a next run is separately authorized -
no run is requested or proposed here, and no solver-cause conclusion is
drawn from any of the above.

## Next-experiment design proposal (54384) - PROPOSAL ONLY, nothing executed

John's comment 54384: bounded, READ-ONLY design of the next experiment,
after acknowledging two corrections to the prior comparison's framing:
ticks 1-59 matching is only at DISPLAYED precision (not proven bit-
identical), and the first OBSERVED divergence at tick=60 does not by
itself establish a physical origin; likewise, the ~6.8x total log-volume
difference does not by itself establish per-tick latency or causation -
both were reported as observations, not conclusions, and remain so here.
No run, build, physics edit, or cleanup was performed for this section.

### Step 1: does a true pre-failure world snapshot exist? (checked, not assumed)

Searched every retained evidence directory under
`/home/booswrit/div-evidence/MINECRAFT-545-547/` for an actual copied
`world/` directory from any point in this investigation: **none exists**.
Every evidence directory holds logs/hashes/diffs, never a copy of the
shared `run/world` itself. The shared `run/world` has been continuously
reused across every run in this entire investigation (dozens of prior
probe invocations, crashes included) with no point-in-time backup ever
taken - today's `baseline-original-run-world-level-dat.sha256` is a
fingerprint of its CURRENT (now, post-every-prior-run) state, not a
snapshot from before any specific historical crash. **Conclusion: no
adequate pre-failure world state is recoverable.** Per 54384's own
branching instruction, this means a design that tries to isolate
"historical/shared world" as a controlled variable against "fresh world"
is not possible - there is no way to re-run history with the actual prior
world state as it existed at any past crash. A matched-state comparison
along that axis is ruled out here, not invented around.

### Proposal: logging-matched, fresh-world reproducibility-RATE repeats (tests fresh-run variability only, not history)

Since the history/environment axis cannot be tested, the only remaining
well-posed question is: under IDENTICAL logging/config/launch controls,
how often does a FRESH isolated world reproduce the known crash at all?
This is a reproducibility-rate measurement, not a causal/environment
study, and is proposed as the next bounded experiment - **not executed,
proposal only**.

**Controls (identical across every repeat):**
- Compiled instrumentation: the SAME commit already built and hash-
  verified this round (`compiled-instrumentation.sha256`,
  `effective-classpath-hashes.sha256`) - re-verified immediately before
  each repeat's launch (cheap hash check, no rebuild needed unless the
  worktree has changed, which it must NOT be for this proposal to hold).
  This holds logging volume constant and identical to the one successful
  run already measured - no MORE and no FEWER diagnostic lines per tick.
- Config/launch: identical `DIV_BUOYANT_HITCH_PROBE=true`, same config/
  eula/server.properties content (hash-verified against the existing
  `isolated-input-manifest.sha256`/`baseline-original-run-config.sha256`
  baseline) - reused as-is, since nothing about them is expected to
  differ run-to-run.
- World: intentionally NOT pinned - each repeat gets its OWN freshly
  generated, uniquely-named isolated directory (same realpath + per-file
  dev:inode non-aliasing proof already established for the first run),
  never reusing a world and never touching the shared `run/` or any
  existing retained evidence directory. This is the one dimension
  deliberately left to vary, since reproducibility-RATE under fresh
  worlds is exactly what's being measured.

**Finite run count / stopping criteria (fixed in advance, never silently
extended):** propose N=5 repeats, maximum, decided up front - stop at 5
regardless of outcome. An early-stop rule is allowed to shorten (not
lengthen) the run: stop early only if 2 CONSECUTIVE repeats reproduce the
known crash (`FAILED - left bounded area ... phase=TOWING_REPLACEMENT`),
since that alone would already demonstrate non-reproduction is not simply
the typical case - this is a stop-early-on-signal rule, not a reason to
exceed 5.

**Outcome interpretation (stated in advance, not fitted after the fact):**
- 0/5 reproduce: consistent with (but does not prove) the one successful
  run NOT being an outlier - reproducibility under these matched controls
  would appear to be rare or absent. Cannot distinguish this from "the
  historical shared world was somehow necessary" since that axis remains
  untestable (Step 1) - stated as an open question, not resolved by this
  design.
- Some mix (1-4/5 reproduce): establishes an actual empirical
  reproduction rate under matched logging for the first time in this
  investigation - every historical run before this differed in some
  uncontrolled way (see 54379's logging-volume finding), so no such rate
  has ever actually been measured. Still not a mechanism.
- 5/5 reproduce: suggests the one successful (non-reproducing) run was
  itself the outlier, not the historical world being special - still not
  a mechanism, and does not retroactively validate or invalidate anything
  already reported about that run.
- **No outcome of this experiment, under any result, licenses a solver-
  cause claim** - it bounds a reproducibility rate under stated controls,
  nothing else.

**Safe isolation / evidence retention / shutdown / lock lifetime (reuse,
not reinvent):** each repeat reuses the EXACT mechanism already built and
proven this round - `launch-wrapper.sh`'s own self-contained exclusive-
lock claim/release (never releases while its own java descendant is
alive, for any terminal reason), its real terminal-pattern detection
(`[DIV buoyantHitchProbe] (COMPLETE|FAILED)`), and its bounded startup-
failure (180s)/no-progress (900s) handling - parameterized ONLY by a
fresh, uniquely-timestamped `ISO_DIR`/`OWNER_TAG` per repeat (e.g.
`M545-isolated-run2` through `M545-isolated-run6`), nothing else changed.
Every repeat's isolated world/log is retained UNCONDITIONALLY (no delete
branch), exactly like the first. Runs are strictly SEQUENTIAL under the
one shared exclusive lock - never a second worker, never parallel.

**Available artifacts cited, missing inputs stated plainly:** this
proposal reuses `compiled-instrumentation.sha256`,
`effective-classpath-hashes.sha256`, `isolated-input-manifest.sha256`,
and `baseline-original-run-config.sha256` (all already produced and
verified this round) as the control baseline. The one missing input,
stated explicitly rather than invented: no pre-failure world snapshot
exists (Step 1) - this proposal does not attempt to supply one.

**This is a proposal for review, not an execution - no run has been
started, and none will be without separate, explicit authorization.**

## Narrow correction to the next-experiment proposal (54387) - PLAN ONLY, no execution/build/setup mutation

John's comment 54387 corrected three framing errors in the 54385 proposal
and asked for one narrower, concrete, launcher-correctness-aware plan.
**Nothing in this section has been executed, built, or set up** - per
54387's own instruction, this is read-only design, returned for parent
authorization before any of it runs.

### Corrections accepted

1. "No true pre-failure snapshot found in retained evidence" should have
   been scoped precisely: it means no such snapshot was found in the
   evidence directories actually SEARCHED
   (`/home/booswrit/div-evidence/MINECRAFT-545-547/`) - NOT a universal
   claim that none could exist anywhere. Restated correctly here.
2. The one existing successful isolated world
   (`M545-isolated-rundir-20261116T224049Z`) is a POST-run world (the
   state AFTER a non-crash completed) - it is NOT a pre-failure baseline
   and must not be reused or mutated as a "starting point" for the trials
   below. It stays exactly as retained; both new trials get their OWN,
   entirely separate, freshly-generated directories.
3. The prior N=5 with an outcome-dependent early-stop rule is not a clean
   fixed-sample design and does not support any precise rate claim.
   Replaced with exactly N=2, fixed in advance, no early stop, no
   extension - reported only as a descriptive count (0/2, 1/2, or 2/2),
   never as a rate or statistical bound.
4. Leaving the world seed to vary (as 54385 proposed) conflates
   "fresh-world variability" with "pure execution/solver nondeterminism."
   Corrected: both trials now use an explicit, FIXED, IDENTICAL seed -
   isolating whatever differs between two runs of the SAME scenario
   (same code, same config, same seed) to actual execution-time behavior
   alone, not world identity.

### The critical launcher-correctness issue (54387's own catch)

`launch-wrapper.sh` (as already built) hardcodes `ISO_DIR` and `RUN_SH`
as literal paths pointing at the FIRST trial's own directory and at
`build/moddev/runServer.sh`. That generated script itself hardcodes a
literal `cd '<old isolated rundir>'` as its FIRST shell command, BAKED IN
at the time `gradle prepareServerRun createServerLaunchScript` last ran
with that specific `DIV_ISOLATED_RUN_DIR`. **Changing `launch-wrapper.sh`'s
own `ISO_DIR` constant alone does NOT redirect where Minecraft itself
runs** - `runServer.sh` must be REGENERATED, once per trial, against that
trial's own new directory, and the regenerated script's own `cd` target
must be independently read and confirmed (not merely trusted from the
gradle log) before that trial's wrapper is ever invoked.

### Corrected plan: exactly TWO matched isolated trials, fixed seed

**Proposed unique destinations (not yet created):**
- Trial 1: `/home/booswrit/div-evidence/MINECRAFT-545-547/M545-isolated-trial1-seed545547/rundir`
- Trial 2: `/home/booswrit/div-evidence/MINECRAFT-545-547/M545-isolated-trial2-seed545547/rundir`

**Fixed seed:** `level-seed=545547` (an arbitrary but FIXED, documented
literal - the exact value doesn't matter, only that it is IDENTICAL and
pinned in both trials' `server.properties`, unlike the original run which
left it empty/random).

**Per-trial setup checklist, performed identically for trial 1 then
trial 2, strictly sequential under the one shared exclusive lock (never
parallel, never a new worker) - executed only once parent authorization
is given, not now:**
1. Re-verify the worktree is unchanged (`git status` clean at the same
   commit already hashed in `compiled-instrumentation.sha256`/
   `effective-classpath-hashes.sha256`) - if it has changed, re-hash
   before proceeding; do not launch against drifted artifacts.
2. Create that trial's own directory; copy `config/`/`eula.txt` from the
   ORIGINAL `run/` exactly as before (`cp -L`), but write
   `server.properties` with `level-seed=545547` substituted in (every
   other field identical to the original, hash-diffed to confirm ONLY
   that one line changed).
3. Confirm via `realpath` + per-file `stat -c '%d:%i'` that this trial's
   directory and copied files share no path/symlink/hardlink with
   `run/`, with the OTHER trial's directory, or with the already-retained
   `M545-isolated-rundir-20261116T224049Z` - exactly the non-aliasing
   proof already established, extended to cover the second trial and the
   first trial's own prior directory too.
4. Confirm no `world/` directory exists yet in that trial's own rundir.
5. Run `gradle prepareServerRun createServerLaunchScript` under the
   exclusive lock with `DIV_ISOLATED_RUN_DIR` set to THIS trial's own
   directory and `DIV_BUOYANT_HITCH_PROBE=true` (same flags as the first
   run, nothing added or removed - scenario/logging bytes unchanged).
6. **Independently read** the regenerated `build/moddev/runServer.sh`'s
   own literal `cd '...'` argument and confirm, byte-for-byte, it equals
   THIS trial's own directory - not the other trial's, not the original
   isolated run's, not `run/`. Do not proceed on a mismatch.
7. Only after steps 1-6 all pass: construct that trial's OWN wrapper
   (a fresh copy of `launch-wrapper.sh` with `ISO_DIR`/`RUN_SH`/
   `OWNER_TAG` set to match exactly what was just verified in step 6 -
   not edited independently of it) and launch it, same self-contained
   lock/terminal-detection/bounded-timeout behavior as already proven.

**Stopping:** exactly 2 trials, no more, no fewer, regardless of outcome.

**Retention:** both trials' worlds/logs retained unconditionally at their
own distinct directories; the original `run/` and the FIRST isolated
run's own world/evidence (`M545-isolated-rundir-20261116T224049Z`) stay
completely untouched throughout - never reused, overwritten, or deleted.

**Outcome reporting (descriptive only, stated in advance):** report
exactly which of trial 1 / trial 2 reproduced the known crash, as a count
- "0/2", "1/2", or "2/2" reproduced - explicitly NOT a rate, NOT a
statistical bound, and NOT evidence of a specific solver cause under any
outcome.

**This is a plan returned for parent authorization - no directory has
been created, no gradle task run, no world generated, and no launch
performed for this section.**

## Both trial gate packets prepared (54388) - setup/prep executed, NO launch performed

John's comment 54388: parent conditionally authorized the two-trial fixed-
seed design and explicitly authorized SETUP/PATH-FIX/PREP work now - but
"DO NOT launch either until coordinator independently verifies" each gate
packet, and "no implicit release from this comment." Accordingly,
everything below was actually executed; **no Minecraft/java process was
launched for either trial** - only directory/config preparation, the
exclusive-lock-serialized `gradle prepareServerRun createServerLaunchScript`
calls needed to produce and verify each trial's own launcher, and the
construction (never invocation) of each trial's own wrapper.

### Fixed seed and destinations

`level-seed=545547`, identical in both trials.
- Trial 1: `/home/booswrit/div-evidence/MINECRAFT-545-547/M545-isolated-trial1-seed545547/rundir`
- Trial 2: `/home/booswrit/div-evidence/MINECRAFT-545-547/M545-isolated-trial2-seed545547/rundir`

### Verified for BOTH trials (live, not assumed)

- `realpath` distinct from `run/`, from each other, and from the
  already-retained `M545-isolated-rundir-20261116T224049Z`.
- Per-file `stat -c '%d:%i'` on all 8 copied config files: every pair
  DISTINCT from the original `run/config` - no symlink/hardlink
  aliasing, for both trials independently.
- Trial 1's and Trial 2's own `eula.txt`/`server.properties` are
  distinct inodes from each other too (not accidentally sharing a file).
- Both trials' copied config files hash BYTE-IDENTICAL to the original
  `run/config` (confirmed via `isolated-input-manifest.sha256` in each
  trial's own directory). Both trials' `server.properties` hash
  IDENTICAL to each other (`bc96ed40c64df9e2498b5110cc68df9bfd8147eddaace2f3782b79f473f8205e`)
  - confirming the seed substitution is the only difference from the
    original and is identical between trials.
- No `world/` directory exists yet in either trial's `rundir`.
- Worktree drift check: `git status` clean at commit `fa1c803`;
  compiled classes re-hashed and matched the already-recorded
  `compiled-instrumentation.sha256` with zero drift before any gradle
  task ran.
- `gradle prepareServerRun createServerLaunchScript` run ONCE per trial,
  sequentially, under `/tmp/div-gradle-slot.lock` (claimed/released as
  `M545-trial1-prep` then `M545-trial2-prep`, logged in
  `/tmp/div-gradle-slot.owner`) - config-generation only, no JVM launch.
- For EACH trial, independently read the regenerated
  `build/moddev/runServer.sh`'s own literal `cd '...'` argument and
  confirmed byte-for-byte it equals THAT trial's own `rundir` (not the
  other trial's, not `run/`, not the original isolated run's) - BEFORE
  archiving a copy into that trial's own evidence directory.
- Each trial's own archived `runServer.sh`/`serverRunClasspath.txt`/
  `serverRunVmArgs.txt`/`serverRunProgramArgs.txt`, with
  `generated-launch-artifacts.sha256` recorded in that trial's own
  directory. The classpath/VM-args/program-args hashes are IDENTICAL
  across both trials AND identical to the original single-trial run's
  own recorded hashes (`317bc204...`/`1a25f7e5...`/`451a116b...`) -
  only `runServer.sh`'s own `cd` target differs by trial, exactly as
  expected (scenario/logging bytes unchanged, confirmed not merely
  assumed).
- VM args confirmed to contain ONLY this probe's own two flags in both
  trials' archived copies; no reference to the shared `run/` path or to
  the other trial's name in either trial's own archived files (grepped,
  zero matches).
- Each trial's own `launch-wrapper.sh` constructed (self-contained
  exclusive-lock claim/release, never releases while its own java
  descendant lives, real terminal-pattern detection, bounded startup/
  no-progress handling - identical to the already-proven single-run
  design) with `ISO_DIR`/`OWNER_TAG` set to that trial's own values
  (`M545-isolated-trial1`/`M545-isolated-trial2`) - syntax-checked
  (`bash -n`), never invoked.

### The one remaining precondition, stated loudly in both wrappers themselves

Because `build/moddev/runServer.sh` (and its companion args files) are a
SINGLE shared set of files in the worktree, Trial 2's prep necessarily
OVERWROTE what Trial 1's prep had generated (the live file currently
reflects Trial 2's `cd` target, confirmed via `grep -o 'trial[12]'`).
Each trial's own `launch-wrapper.sh` carries an explicit header
precondition: immediately before THAT specific trial is ever launched,
`gradle prepareServerRun createServerLaunchScript` must be re-run with
that trial's own `DIV_ISOLATED_RUN_DIR`, and the regenerated `cd` target
must be independently re-read and re-confirmed - the archived copies in
each trial's own directory are evidence of what was verified DURING PREP,
not a substitute for re-verifying the LIVE file immediately before that
trial's own launch.

### Disposition

**No launch performed for either trial.** Exactly two launches remain
authorized in total (per 54388), to happen sequentially, only after the
coordinator independently reviews this packet and explicitly releases
each trial - no implicit release is taken from either 54388 or this
entry. All prior evidence (original `run/`, `M545-isolated-rundir-
20261116T224049Z`, `M545-authrun-probe2-...`) remains completely
untouched. No destructive cleanup, no peer signals, no lock/permission
changes, no new worker.

## Trial 1's LIVE launch set prepared and left in place for independent readback (54389)

John's comment 54389 independently confirmed the prior packet but caught
exactly the gap already flagged in it: both wrappers invoke the SAME
shared `build/moddev/runServer.sh`, and that live file still reflected
Trial 2's `cd` target. Before Trial 1 can be released, the actual LIVE
launch set for Trial 1 had to be regenerated under the lock and LEFT
there (not launched) for independent readback - done here.

- Re-ran `gradle prepareServerRun createServerLaunchScript` with Trial
  1's own `DIV_ISOLATED_RUN_DIR`/`DIV_BUOYANT_HITCH_PROBE=true` under
  the lock (`M545-trial1-livegen`, logged in
  `/tmp/div-gradle-slot.owner`). The LIVE `build/moddev/runServer.sh`
  now `cd`s into Trial 1's own directory - confirmed by direct read,
  left in that state, not launched.
- Lock file identity re-confirmed: `664 2051 9214683` - matches John's
  own independently-read value exactly.
- Fresh runtime-jar/compiled-instrumentation BYTE manifest (not just the
  launch-text file hashes) regenerated NOW, right before this readback:
  `live-readback-effective-classpath-hashes.sha256` (244 entries - every
  jar on the classpath plus every file under the two
  `build/classes`/`build/resources` directory entries) - diffed against
  the ORIGINAL single-trial run's own full set and confirmed
  BYTE-IDENTICAL. This means the actual mod/engine/native jars and
  compiled `.class` files, not merely the generated launch-text files,
  are confirmed unchanged.
- Original-baseline comparisons re-verified FRESH (not re-cited from
  memory): `run/config`'s 8 files and `run/world/level.dat`/
  `level.dat_old` both still match their first-recorded baseline hashes
  exactly, right now.
- Canonical/non-aliasing re-checked fresh for Trial 1 specifically:
  `realpath` distinct from `run/` and Trial 2; per-file `dev:inode` for
  `config/sable-server.toml`/`eula.txt`/`server.properties` all
  DISTINCT from the original; no `world/` yet in Trial 1's `rundir`.

**Still no launch performed.** The live launch set is left exactly as
regenerated above, ready for independent review; Trial 1 will only be
started after explicit release, and per 54389, Trial 2 gets its own
separate readback/release only after Trial 1's own outcome/exit/slot
release.

## Trial 1 result: reproduced the crash, byte-identical to authrun-probe2 (54394/54395)

Trial 1 was released (54394) and executed via its own already-verified
`launch-wrapper.sh`, unchanged. **Result: Trial 1 REPRODUCED the known
crash.**

- Start: lock claimed by `M545-isolated-trial1` at 2026-11-17T00:36:29Z.
  Java pid 2603913 launched.
- Terminal: `[DIV buoyantHitchProbe] FAILED - left bounded area
  tag=towingReplacement/towing2 phase=TOWING_REPLACEMENT tick=8
  horizontal=65.44565659568023 vertical=50.060278582992964
  position=[X:17597.89253401834, Y:155.06027858299296,
  Z:17673.0803037519]` at local time 16:37:40.008 (log line 2200).
- Orderly stop/shutdown: `Stopping server` (line 2265),
  `All dimensions are saved` (line 2277). Java exited 0. Lock released
  by `M545-isolated-trial1` at 2026-11-17T00:37:45Z.
- `logs/latest.log` SHA-256: `1ead408a6558cc236f964edfc89ea35b9c5a4edb5dc05f91f9c203740b313ba2`
  - matches John's own independent computation exactly.
- World retained unconditionally at `/home/booswrit/div-evidence/MINECRAFT-545-547/M545-isolated-trial1-seed545547/rundir/world`.
- Original-baseline re-check: `run/config`'s 8 files and
  `run/world/level.dat`/`level.dat_old` still match their first-
  recorded baseline hashes - unaffected by this trial.

### Byte-identical match to the prior crash reference (authrun-probe2) - a clean result

Trial 1's own FAILED line is IDENTICAL, digit-for-digit, to
`authrun-probe2`'s own FAILED line (same `tick=8`, same
`horizontal=65.44565659568023`, same `vertical=50.060278582992964`,
same exact `position`). The phase-local progression leading up to it
also matches exactly: `TOWING_UNAVAILABLE tick=60`,
`TOWING_REPLACEMENT tick=1/2/3` (including the `260.06966433253604`
blocks/s runaway speed at tick=3) are all digit-for-digit identical
between Trial 1 (fixed `level-seed=545547`) and `authrun-probe2`
(empty/random seed). The `TOWING_UNAVAILABLE` pre-remove() bracket
(`towing.angularVelocity=(-41.40,-12.50,-3.443)`,
`towed.angularVelocity=(-73.00,-13.84,87.99)`,
`lastPoseUpdateGameTime=203` both bodies) also matches the SAME bracket
in BOTH `authrun-probe2` and the prior non-crash isolated run exactly -
confirming (again) this bracket is deterministic regardless of outcome.

**This rules out two more candidates with direct evidence, not
inference:**
- **World/seed identity is not the explanation.** Two DIFFERENT seeds
  (authrun-probe2's own unrecorded random seed, and Trial 1's fixed
  `545547`) produce digit-for-digit IDENTICAL physics outcomes. If seed
  identity mattered, two different seeds would not be expected to
  coincide this precisely by chance.
- **Logging volume is not the explanation either.** Trial 1 has the SAME
  heavy `BuoyantForces` diagnostic logging as the prior NON-crash
  isolated run (same current commit, same instrumentation) and still
  reproduced the crash - directly contradicting the logging-volume
  confound raised after the first comparison (54379/54382).
- The one remaining, still-unexplained data point is the SINGLE original
  isolated run that did NOT crash - every other variable now controlled
  (code, seed, logging volume) still reproduces the crash identically.
  What made that one run different remains genuinely unknown - NOT
  attributed to any mechanism here, native/solver-internal or otherwise.

**This consumes Trial 1 - no substitute run.**

## Trial 2's LIVE launch set prepared for independent readback - NOT launched

Per 54395, Trial 2's own live launch set was prepared next, same
procedure as Trial 1's own readback:

- Re-ran `gradle prepareServerRun createServerLaunchScript` with Trial
  2's own `DIV_ISOLATED_RUN_DIR`/`DIV_BUOYANT_HITCH_PROBE=true` under
  the lock (`M545-trial2-livegen`). LIVE `build/moddev/runServer.sh`
  now `cd`s into Trial 2's own directory - confirmed by direct read.
- Lock identity re-confirmed: `664 2051 9214683`.
- Fresh runtime-jar/compiled-instrumentation byte manifest (244 entries)
  regenerated and diffed BYTE-IDENTICAL against the original run's own
  set - same instrumentation, unchanged between arms.
- Original-baseline config/world hashes re-verified fresh: unchanged.
- Non-aliasing + world-absence re-checked fresh for Trial 2: distinct
  from `run/`, no `world/` yet in its own `rundir`.

**No launch performed for Trial 2.** Awaiting explicit coordinator
release, per 54394/54395 ("do not launch Trial2 until explicit
release").

## Trial 2 result: also reproduced the crash, byte-identical to Trial 1 and authrun-probe2 (54396)

Trial 2 was released (54396), the second and FINAL launch under the two-
trial grant. Executed via its own already-verified `launch-wrapper.sh`,
unchanged. **Result: Trial 2 also REPRODUCED the known crash.**

- Start: lock claimed by `M545-isolated-trial2` at 2026-11-17T00:41:09Z.
  Java pid 2619180 launched.
- Terminal: `[DIV buoyantHitchProbe] FAILED - left bounded area
  tag=towingReplacement/towing2 phase=TOWING_REPLACEMENT tick=8
  horizontal=65.44565659568023 vertical=50.060278582992964
  position=[X:17597.89253401834, Y:155.06027858299296,
  Z:17673.0803037519]` at local time 16:42:22.417 (log line 2199) -
  digit-for-digit IDENTICAL to both Trial 1's and `authrun-probe2`'s own
  FAILED line.
- Orderly stop/shutdown: `Stopping server` (line 2264),
  `All dimensions are saved` (line 2276). Java exited 0. Lock released
  by `M545-isolated-trial2` at 2026-11-17T00:42:28Z.
- `logs/latest.log` SHA-256:
  `afe210296a4b8bd2a1b5e3a52c49ff281857c15039c7a3e3d48dfcbe758dbc1b`.
- World retained unconditionally at `/home/booswrit/div-evidence/MINECRAFT-545-547/M545-isolated-trial2-seed545547/rundir/world`.
- Original-baseline re-check: `run/config`'s 8 files and
  `run/world/level.dat`/`level.dat_old` still match their first-
  recorded baseline hashes - unaffected by this trial either.

### Final descriptive outcome: 2/2 reproduced

Both fixed-seed, logging-matched, otherwise-identical trials reproduced
the known crash, each byte-identical to the original `authrun-probe2`
reference. This is a descriptive count only - **2/2, not a rate, not a
statistical bound, and not a solver-cause conclusion.** Per 54396: STOP
here - no third/substitute run, no tuning/logging change, no destructive
cleanup, no signals to peers, no permission/lock changes, no deployment.
Both trial worlds and full raw logs are retained unconditionally. The one
remaining unexplained data point in this whole investigation is still
the SINGLE original isolated run that did not crash - its cause remains
genuinely unknown; nothing in this round attributes a mechanism to it or
to anything else.

**Two-trial grant is now fully consumed.**

## Fix-phase mechanism investigation (54403) - candidate identified, concrete blocker found

John's comment 54403 authorized a new fix phase: identify a falsifiable
mechanism and the smallest production correction, develop a patch and a
regression that fails on prior code and passes after, with source
analysis/implementation/serialized (JVM-only) regression validation
authorized now - no native run until mechanism evidence + diff +
regression receipt are returned. This section reports exactly that - a
concrete, quantified mechanism candidate, and why the fix itself cannot
yet be implemented.

### Mechanism investigated and ruled out: no joint-stiffness knob exists

Checked Sable's own public API (`GenericConstraintConfiguration`, the
actual class DIV's `createHitch` constructs) for any exposed solver
stiffness/ERP/max-corrective-velocity parameter DIV could tune per joint:
none exists. The record has exactly five fields -
`pos1/pos2/orientation1/orientation2/lockedAxes` - with `lockedAxes`
being a hard, binary per-axis lock, no softness. Also confirmed `pos1`/
`pos2` (what `createHitch` passes as `hitchOffset`/`hookupOffset`) are
PURE body-local geometry (`HitchJoint.anchorLocalOffset`), with zero
dependency on either body's live pose - so a "stale cached pose" theory
(the `UNVERIFIED_AGE`/`lastPoseUpdateGameTime` work from earlier this
session) does NOT feed into the actual joint configuration at all, only
into diagnostic logging. This rules out both a joint-stiffness workaround
and a stale-pose-anchor bug as the mechanism - confirmed by reading the
real API, not assumed.

### Mechanism candidate: Sable's own box-inertia fallback ignores shape entirely

Read `BoxPhysicsObject`'s actual mass/inertia path (decompiled directly
from the PINNED jar, `sable-neoforge-1.21.1-2.0.5.jar` - the real
dependency this build resolves, not a possibly-different local checkout).
Confirmed: the legacy scalar-mass constructor
(`BoxPhysicsObject(Pose3dc, Vector3dc halfExtents, double mass)` - the
ONLY constructor this jar exposes) produces an inertia tensor of
`Matrix3d().scale(mass / 6.0)` - i.e. a single scalar divided by 6,
IDENTICAL on all three axes, with **zero dependency on `halfExtents`** -
confirmed directly from the class's own mass-data implementation, not
inferred.

Computed the ACTUAL error for this exact fixture's own hull (mass=400kg,
half-extents (0.6, 0.3, 1.0), from `run/config/dynamic-immersive-
vehicles.json` and `BuoyantHitchProbe`'s own `HALF_EXTENT_X/HALF_HEIGHT_Y/
HALF_EXTENT_Z` constants) against the correct solid-box inertia formula
(`I_xx = m/3*(hy²+hz²)`, etc., for half-extents hx,hy,hz):

| axis | Sable's fallback (mass/6) | correct solid-box value | ratio |
|---|---|---|---|
| X (pitch) | 66.67 kg·m² | 145.33 kg·m² | fallback is **2.18x too small** |
| Y (yaw)   | 66.67 kg·m² | 181.33 kg·m² | fallback is **2.72x too small** |
| Z (roll)  | 66.67 kg·m² | 60.00 kg·m²  | fallback is ~11% too LARGE (roughly correct) |

This hull (long and flat, like a canoe) should resist pitch/yaw rotation
roughly 2.2x-2.7x MORE than Sable's own fallback gives it credit for.
Mechanistically, this is consistent with every observed symptom in this
investigation: (1) `towed` accumulates an extreme, unrealistic angular
velocity (observed ~88 rad/s on one axis) while free-floating unjointed
during `TOWING_UNAVAILABLE` - an under-inertia body responds to ANY
asymmetric torque (buoyancy/drag, already logged in detail this session)
with an outsized angular acceleration; (2) when `TOWING_REPLACEMENT`
creates a position-locking joint against that already-fast-spinning
body, the position-correction impulse couples into angular velocity via
the lever arm (the anchor offset is NOT at the body's center), and an
under-inertia body again responds with an outsized change for a given
corrective impulse - consistent with the observed violent linear/angular
snap (260+ blocks/s) rather than a smooth settle. This is a candidate
mechanism, not a proven one - no read-back of Rapier's own internal
per-substep response exists to confirm it quantitatively; it is reported
as the best-supported falsifiable candidate found, not an established
fact.

**Note:** this same legacy constructor call
(`new BoxPhysicsObject(pose, half, mass)`) is used identically by BOTH
`BuoyantBody` (this ticket's own class) AND the pre-existing
`PhysicsBridge.Body` (ground/flight/rotor) - this is a latent,
codebase-wide characteristic, not something MINECRAFT-545 introduced,
and not unique to float-only hulls. It only manifests catastrophically
here because a position-locking joint's sudden correction is a uniquely
demanding test of inertia accuracy that ordinary driving/floating never
exercises.

### The concrete blocker: no API exists, in the PINNED Sable version, to supply a correct inertia tensor

Checked for a way to give DIV's own code control over the inertia tensor
without touching Sable's own source: `BoxPhysicsObject` (in a DIFFERENT,
newer-looking local checkout under
`MINECRAFT-449/sable-local/common/src/main/java/...`) DOES have a second
constructor accepting a `BoxMassProperties` record (mass + local center
of mass + a caller-supplied inertia tensor, matrix validated symmetric-
positive-definite). **This is NOT what's actually pinned and used by
this project.** Decompiled the REAL resolved dependency
(`sable-neoforge-1.21.1-2.0.5.jar`, confirmed via `build.gradle`'s own
pin and the resolved file in `~/.gradle/caches/modules-2/...`) directly
with `javap`: that jar's own `BoxPhysicsObject` has exactly ONE
constructor, the legacy scalar-mass one - no `BoxMassProperties` class
exists in it at all. Only two Sable versions are present in this
machine's Gradle cache at all (`1.2.2`, `2.0.5` - the pinned one); no
newer version is available locally to even consider as an upgrade
target, and this investigation does not have authorization to fetch or
pin a new third-party dependency version on its own.

**Concrete blocker, stated plainly: DIV's own code has no available,
supported way to give the ACTUALLY PINNED Sable dependency a
shape-aware inertia tensor.** The only constructor Sable 2.0.5 exposes
unconditionally computes the flawed `mass/6` fallback for every box body
in this mod, with no override. A correction at this specific mechanism
therefore cannot be made inside DIV's own source without either (a)
bumping the pinned Sable dependency to a version that exposes
`BoxMassProperties` (a cross-cutting dependency change, outside what one
mod-side commit can respobsibly decide alone, and unverified whether
such a published version even exists upstream from here), or (b) some
other DIV-side compensating mechanism, which risks falling into the
explicitly forbidden "arbitrary damping/reset" category John's own
instruction already ruled out.

**No patch or regression is implemented in this entry - reporting the
mechanism evidence and this specific blocker for review, per 54403's own
instruction to report "concrete mechanism/patch/regression or exact
blocker."**

## Fix implemented: shape-aware inertia, via public Sable API, no dependency change (54407)

John's comment 54407 corrected 54405/06's overclaims and directed following
the PUBLIC backing pipeline/mass APIs past `BoxPhysicsObject`'s own
constructors before declaring "no DIV-side option." Doing exactly that
found a real, viable, supported fix path.

### Correction accepted

54405/06 overstated two things: (1) the solid-box inertia formula is a
CHOSEN shape/mass-distribution model (uniform density), not automatically
proven to match the body's INTENDED effective mass properties - treated
as a principled, standard, falsifiable candidate below, not an
established fact. (2) "Ruled out" was too broad: the absence of a
stiffness field in `GenericConstraintConfiguration` rules out only THAT
specific public knob, and body-local anchor inputs rule out only the
DIRECT stale-world-pose path - neither claim covers every possible
lifecycle/timing defect. Both corrections are accepted; this entry does
not repeat the overbroad framing.

### Following the public API past the constructors (what 54407 asked for)

Decompiled the full set of public backing interfaces/classes in the
ACTUALLY PINNED `sable-neoforge-1.21.1-2.0.5.jar` (`javap`, exact
signatures below) that `BoxPhysicsObject` implements or returns:

- `PhysicsPipelineBody` (interface `BoxPhysicsObject` implements):
  `getRuntimeId()`, `getMassTracker()` returning `MassData`, `isRemoved()`.
- `MassData` (interface): `getMass()`, `getInverseMass()`,
  `getInertiaTensor()` returning `Matrix3dc`, `getInverseInertiaTensor()`,
  `getCenterOfMass()`, plus two `default` methods - ALL of its abstract
  methods are `public`, nothing package-private or final-by-interface.
- `BoxPhysicsObject.getMassTracker()` bytecode flags: `(0x0001) ACC_PUBLIC`
  - NOT final. `BoxPhysicsObject`'s own class flags:
  `(0x0021) ACC_PUBLIC, ACC_SUPER` - NOT final either.

**This means `BoxPhysicsObject` is a normal, non-final public class with
a normal, non-final public method returning a public interface type -
fully overridable via ordinary Java subclassing, a completely supported
operation requiring no reflection, no native patch, and no dependency
version change.** `getHalfExtents()`/`getMass()`/`getPose()` are already
public getters on the base class, giving a subclass everything it needs
to compute a real inertia tensor itself.

### The fix

- `BoxInertia.java` (new, pure math, zero Sable/Minecraft dependency):
  the standard solid-rectangular-box inertia tensor about its own center,
  `I_xx=m/3*(hy²+hz²)` etc. - textbook rigid-body mechanics. This is a
  CHOSEN model (uniform-density solid box), not a proven match to this
  hull's actual mass distribution. It is a DIFFERENT model from the
  flawed fallback, not a correction to the same one: `mass/6` has no
  shape term and no coherent physical model behind it at all - it is
  neither uniform-density nor point-mass (a true point mass has ZERO
  rotational inertia, which `mass/6` is not).
- `ShapeAwareBoxPhysicsObject.java` (new): extends `BoxPhysicsObject`,
  overrides `getMassTracker()` to return a `MassData` built from
  `BoxInertia.solidBoxTensor(mass, halfExtents)` instead of the base
  class's own `mass/6` fallback. Center of mass stays at the box's own
  geometric center (matching the base class's own implicit assumption -
  this changes how resistant to rotation the native solver is told the
  body is, not where the body is). Everything else (collision geometry,
  pose, handle lifecycle, actual native rigid-body creation/stepping) is
  inherited UNCHANGED from `BoxPhysicsObject`.
- `BuoyantBody.java` (changed): constructs `ShapeAwareBoxPhysicsObject`
  instead of the plain `BoxPhysicsObject`, same arguments, same call
  site - the smallest possible change, scoped to only the class this
  ticket's own hull domain uses. `PhysicsBridge.Body` (ground/flight/
  rotor) is NOT touched - it shares the same underlying characteristic
  (noted in 54405/06) but changing its established, separately-tuned
  behavior is explicitly out of this ticket's own scope.

No arbitrary damping, reset, or gap delay; no acceptance weakening; no
reflection; no native patch; no Sable dependency version change; no mass
fudge (the new value is a real, different, principled physical quantity,
derived from the body's own already-known shape, not an arbitrary
multiplier).

### Regression receipt

`BoxInertiaTest.java` (new, 4 tests, pure math, no Sable/Minecraft
runtime): verifies the cube-case formula independently, verifies
off-diagonal terms are zero for an axis-aligned box, verifies the EXACT
fixture-hull numbers already hand-computed in 54405
(400kg/0.6/0.3/1.0 → 145.33/181.33/60.0 kg·m²), and verifies linear mass
scaling. All 4 pass.

**Confirmed classpath limitation (reported plainly, not hidden):** a
second test file constructing `BoxPhysicsObject`/`ShapeAwareBoxPhysicsObject`
directly (to show the live before/after contrast on the actual Sable
class) was written and run, but failed with
`NoClassDefFoundError: net/minecraft/core/Position` - `Pose3d`
(required by `BoxPhysicsObject`'s own constructor) transitively needs a
Minecraft class that is NOT present on this project's `test`
sourceSet runtime classpath (confirmed: `createMinecraftArtifacts` feeds
the MAIN sourceSet only; no existing test in this codebase constructs
`Pose3d`/`BoxPhysicsObject` directly, for exactly this reason). That test
was removed rather than left broken. The actually-running regression
(`BoxInertiaTest`) instead locks in the pure-math formula the fix relies
on; the characterization of Sable's OWN flawed baseline behavior
(`mass/6`, confirmed via `javap` on the pinned jar's bytecode in 54405)
stands as the "before" evidence, since it cannot be re-demonstrated live
under plain `gradle test`.

Full suite re-run forced (`--rerun-tasks test`, not relying on any cached
UP-TO-DATE result): **795 tests, 0 failures, 0 errors** - no regression
anywhere else in the codebase from this change.

### Disposition

Patch + regression ready for review. No native/Minecraft run has been
performed for this fix - still gated on independent review of the actual
mechanism/diff per 54407's own "native run remains gated on coordinator
review." PR #39 stays draft/unmerged.

## Consumer/wiring evidence, frame-convention check, and wording corrections (54411)

John's comment 54411 independently reviewed `b1264a7` (confirmed clean
tree, 84 XML files/795 tests/0 failures/errors/skips) and raised four
concrete, still-open items before treating this as more than candidate
progress: (1) does the native-facing path actually invoke the OVERRIDDEN
`getMassTracker()`, with correct constructor-ordering; (2) the exact
tensor/inverse/COM frame convention; (3) finite/positive/nondegenerate
input guarantees against BuoyantBody's EXISTING invariants, not new
speculative robustness; (4) reuse the project's own established
Minecraft-aware unit-test pattern (from MINECRAFT-546) rather than
treating the classpath gap as absolute impossibility - plus two wording
corrections. All four addressed below; no native run performed.

### (4) Reused the established `unitTest` pattern - the classpath gap is now closed

Found the exact precedent in this project's own history: commit
`0ce3285` ("MINECRAFT-546: enable moddev unitTest support...") added
`unitTest { enable(); testedMod = mods.dynamicimmersivevehicles }` inside
`build.gradle`'s `neoForge { ... }` block - the moddev plugin's own
official mechanism for wiring real Minecraft/NeoForge classes onto the
`test` sourceSet's classpath. That commit is NOT an ancestor of this
branch's current HEAD (confirmed via `git merge-base --is-ancestor`) -
it exists in this repo's history but was never merged in here. Re-added
the identical one-line change (same block, same pattern, same comment
style) to `build.gradle`.

**This resolved the classpath gap entirely.** Re-created
`ShapeAwareBoxPhysicsObjectTest.java` (removed in the prior round) - it
now PASSES, 5/5 tests, constructing real `Pose3d`/`BoxPhysicsObject`/
`ShapeAwareBoxPhysicsObject` instances directly, no `NoClassDefFoundError`.
This is still NOT a native-pipeline run - no dedicated server, no
`onAddition`/`addBox`/live Rapier instance - `getMassTracker()` remains a
plain Java method call on a never-added object, exactly as before; only
the class-loading gap (needing `net.minecraft.core.Position` to resolve)
is what `unitTest` fixes.

### (1) Consumer/wiring evidence: virtual dispatch and constructor ordering

**Constructor ordering, checked via bytecode (not assumed):** disassembled
the PINNED jar's own `BoxPhysicsObject(Pose3dc, Vector3dc, double)`
constructor with `javap -c`. It calls `Object.<init>`, then directly sets
`pose`/`halfExtents`/`active`/`mass` fields - **it does not call
`getMassTracker()` or any other overridable method during construction.**
This rules out the classic "virtual call from a superclass constructor
reads an uninitialized subclass field" hazard: nothing in the
superclass's own constructor can observe `ShapeAwareBoxPhysicsObject`'s
`massData` field before the subclass's own constructor body finishes
setting it (immediately after `super(...)` returns, before the
constructor exits - ordinary, safe Java semantics).

**Virtual dispatch through the interface the native-facing path actually
uses, checked with a real test (not assumed):** disassembled
`BoxPhysicsObject.onAddition()` - it calls
`physicsSystem.getPipeline().addBox(this)`. The new
`fixedIsActuallyDispatchedThroughPhysicsPipelineBodyNotJustItsOwnReference`
test holds a `ShapeAwareBoxPhysicsObject` only through a
`PhysicsPipelineBody`-typed reference (the same interface
`getMassTracker()` is declared on, and the type `addBox` itself takes a
value as) and confirms `getMassTracker()` still returns the overridden,
shape-aware tensor through that reference - standard Java virtual
dispatch, verified live, not merely argued from the JLS. The actual
native `addBox`/JNI boundary beyond this Java-level call is still
untraced (native code, not reachable from a unit test) - stated as a
real, remaining limit, not closed.

### (2) Frame/inverse convention

Checked a NEWER, differently-versioned Sable checkout's own native JNI
mass-properties function (`Java_..._Rapier3D_setMassProperties` in
`sable_rapier/.../rapier/src/lib.rs`) for how an inertia matrix is
actually consumed natively: it reads a flattened 9-element array as 3
COLUMN vectors (`Mat3::from_cols`) and applies it via
`MassProperties::with_inertia_matrix`. **This is NOT proven to be the
PINNED 2.0.5 version's own implementation** - stated as supportive
context only, not established fact for the version this build actually
uses. The mitigating factor that actually matters here: `BoxInertia`'s
own tensor is PURELY DIAGONAL (confirmed by the already-passing
`offDiagonalTermsAreZeroForAnAxisAlignedBox` test) - a diagonal matrix is
identical whether read row-major or column-major, so this fix's own
correctness does not depend on resolving that open convention question.

### (3) Finite/positive/nondegenerate inputs, checked against EXISTING invariants only

Re-read `BuoyantBody`'s own constructor: it already throws
`InvalidChassisGeometry` unless `max.x>min.x && max.y>min.y && max.z>min.z`
AND `min.x` is finite (and by the same per-axis min/max reduction over
finite collision-box values, every component of both `min`/`max` is
finite) - BEFORE `half`/`mass` are ever computed. This guarantees
`half.x/y/z` are each strictly positive and finite by the time
`ShapeAwareBoxPhysicsObject`'s constructor runs - the SAME guarantee the
plain `BoxPhysicsObject` constructor already relied on, not a new one.
`mass` itself (`tuning.massKg` or a `v.getMass()`-derived fallback) has
no NEW exposure here either: the plain legacy constructor already
divides by `mass` for inverse mass, so a degenerate `mass` was already a
pre-existing risk surface, unchanged by this fix - per 54411's own
direction, no new speculative validation was added for it.

### Wording corrections

- `BoxInertia.java`'s own doc comment wrongly implied `mass/6` reflects
  "the same implicit uniform-density assumption" the new formula uses -
  corrected: `mass/6` has NO shape term and no coherent physical model
  at all - it is neither uniform-density NOR point-mass (a true point
  mass has ZERO rotational inertia, which `mass/6` plainly is not). The
  new solid-box tensor is a DIFFERENT chosen model, not a correction to
  the same one.
- Restated plainly, again: the solid-box model remains a CHOSEN
  assumption, not proven to match this hull's actual mass distribution;
  the crash mechanism remains a candidate, not an established fact,
  pending discriminating validation.

### Updated regression receipt

Full suite forced (`--rerun-tasks test`, not cached): **800 tests, 0
failures, 0 errors, 0 skipped** (795 + 5 new `ShapeAwareBoxPhysicsObjectTest`
cases, now actually running instead of removed). `unitTest` enablement
required one network fetch (Minecraft asset index, already a documented,
one-time cost from the MINECRAFT-546 precedent's own commit message) -
completed successfully in this environment.

No native/Minecraft dedicated-server run was performed. Native gate
remains closed pending the coordinator's own review of this evidence and
an isolated packet, per 54411.

## Mechanism FALSIFIED by direct native-source trace (54413) - the inertia fix is inert

John's comment 54413 independently traced the ACTUAL pinned native
`createBox` implementation and found the premise behind `b1264a7`/`a29c8d9`
is wrong. Traced it myself too, fetching the exact pinned Sable source
(`gh api repos/ryanhcode/sable/contents/.../boxes.rs?ref=<tag
mc1.21.1-2.0.5-neoforge's own commit, 6966d292...>` - the REAL pinned
tag, resolved via `gh api .../git/refs/tags/mc1.21.1-2.0.5-neoforge`, not
guessed). **Confirmed, independently, exactly what 54413 reported:**

```rust
// Java_..._Rapier3D_createBox (sable_rapier/src/boxes.rs, pinned tag)
let collider = ColliderBuilder::new(SharedShape::cuboid(
    half_extent_x as Real, half_extent_y as Real, half_extent_z as Real,
)).mass(mass as Real).friction(0.45).build();
```

`createBox` receives raw `mass`/`half_extent_x/y/z`/`pose` as plain JNI
scalar parameters - **no inertia tensor, and no call to Java's
`getMassTracker()`, anywhere in this function.** Rapier's own
`ColliderBuilder::mass(mass)` on a `SharedShape::cuboid(...)` makes
Rapier compute ITS OWN internal mass properties (including inertia)
directly from the collider's real shape and the given scalar mass - a
built-in Rapier mechanism, entirely independent of Sable's Java-side
`MassData`/`BoxMassData`/`getMassTracker()` fallback.

**This means: the native rigid body already gets a shape-aware inertia
tensor, computed by Rapier itself, regardless of what Sable's Java
`getMassTracker()` returns for a `BoxPhysicsObject` created this way.**
`ShapeAwareBoxPhysicsObject`'s override changes what a JAVA caller
reading `getMassTracker()` observes - and nothing else. It is NEVER
consulted by `createBox`, so it has **NO EFFECT on the actual native
simulation.** The `mass/6` fallback this fix targeted was never the
native body's real inertia in the first place - it was Java-side
metadata, orthogonal to the native value. Also confirmed, per 54413: the
package-private `setMassPropertiesFrom(MassData)` (the ONE place
`MassData`/`getMassTracker()` output actually gets uploaded to native)
is called from `onStatsChanged(ServerSubLevel)`, not from box creation -
a path `BuoyantBody`'s own construction never triggers.

**The candidate mechanism identified in 54405/06/b1264a7/a29c8d9 is
RETRACTED - falsified by direct trace of the actual pinned native
source, not merely unproven.** The fix changes zero native behavior; the
Java-level unit tests added (`BoxInertiaTest`, `ShapeAwareBoxPhysicsObjectTest`)
are real and correctly demonstrate the JAVA override works as Java
code - they do NOT, and never did, demonstrate anything about native
behavior. That distinction - Java metadata vs. actual native mass
properties - is exactly what 54413 named, and is now confirmed directly.

Per 54413's own instruction ("preserve patch/evidence"), the existing
commits (`b1264a7`, `a29c8d9`) are NOT reverted or deleted - they remain
as retained evidence of this falsified attempt, same as every other
retained artifact in this investigation. `BuoyantBody` still constructs
`ShapeAwareBoxPhysicsObject` - this is now known to be harmless (the
override is simply never consulted) but is NOT a fix, and must not be
represented as one going forward.

**No real mechanism for the ordinary-reconnect crash has been
established.** The candidates investigated and their current status:
joint stiffness/ERP knob - none exists (54405/06); stale-pose anchor -
ruled out, pos1/pos2 are pure static body-local geometry (54405/06);
shape-ignorant Java-side inertia fallback - FALSIFIED as a native-
behavior explanation (this entry). Remaining, not yet investigated:
frame-conversion correctness in `connectionRotationVehicleLocal`/the
joint's `orientation1`/`orientation2` (though `lockedAxes` for an
ordinary connection locks no angular axis, which may make this moot -
not yet re-checked against this specific finding), and force/impulse
accounting/ordering in the actual per-tick sequencing. No further
production change is proposed in this entry - reporting the trace result
and the now-fully-open mechanism search back for direction, per 54413's
own request to "report actual trace/regression and next supported
correction or precise API blocker."

## Native joint source trace: hardcoded stiff softness, confirmed ordering, identity orientation for this fixture (54418)

John's comment 54418: continue investigating joint frame/anchor conversions
and force/impulse ORDERING using the pinned native source, select ONE
concrete falsifiable discrepancy. Traced `joints.rs`/`lib.rs` directly at
the same real pinned tag (`mc1.21.1-2.0.5-neoforge`, commit
`6966d292...`) used in the prior retraction. No code change - read-only.

### Finding 1: EVERY generic/fixed/rotary joint gets a hardcoded, very stiff softness - confirmed, not DIV-configurable

`Java_..._Rapier3D_addGenericConstraint` (the real native function behind
`GenericConstraintConfiguration`) builds:
```rust
let mut joint = GenericJointBuilder::new(locked_axes).softness(
    SpringCoefficients::new(JOINT_SPRING_FREQUENCY, JOINT_SPRING_DAMPING_RATIO));
```
with `config.rs`'s own constants: `JOINT_SPRING_FREQUENCY: Real = 550.0`,
`JOINT_SPRING_DAMPING_RATIO: Real = 4.0` - a 550Hz, overdamped
(ratio>1) spring, IDENTICAL for `addGenericConstraint`/`addFixedConstraint`/
`addRotaryConstraint` alike. Checked for a matching setter (as exist for
other joint properties - `setConstraintMotor`/`setConstraintLimit`/
`lockConstraintAxes`/`setConstraintFrame` all exist as separate native
calls): **none exists for softness.** This corrects/sharpens the earlier
54405/06 finding (which only checked the JAVA-side `GenericConstraintConfiguration`
record for a stiffness field) with the actual native constants and
confirmation no native setter exists either - DIV genuinely has no
avenue to tune this, at either layer.

### Finding 2: the per-tick anchor "center of mass" correction is a no-op for entity-to-entity joints - DIV's own offsets pass through unmodified

`joints::tick()` runs EVERY tick for every joint and recomputes
`local_anchor_1 = joint.pos_a - center_of_mass_1` (and the `_2`
equivalent), where `center_of_mass_1` is looked up from
`sable_data.level_colliders` - **a registry exclusively for
terrain/block-level colliders** (confirmed: `ActiveLevelColliderInfo`,
populated/removed in `lib.rs` alongside level-collider chunk sync, never
touched by `createBox`'s own entity-rigid-body path). For a hitch joint
between two vehicle bodies, this lookup NEVER matches, so
`center_of_mass_1/2` always default to `DVec3::ZERO`, making this
subtraction a no-op - DIV's own `hitchOffset`/`hookupOffset` (already
computed body-local, offset from each body's own geometric center) pass
through to the native anchor UNMODIFIED. Not a bug - confirms this layer
is NOT silently corrupting the anchor DIV computes.

### Finding 3: for THIS EXACT fixture, the joint's frame orientation is confirmed IDENTITY - re-checked per 54418's own caution, not assumed

54418 cautioned that "no angular locks" does not itself make orientation
irrelevant to the locked LINEAR axes (`local_frame1.rotation`/
`local_frame2.rotation` define what "X/Y/Z" even means for the lock, not
merely an angular-constraint detail). Re-checked `BuoyantHitchProbe`'s
own actual connection setup rather than assuming: both
`connectionGroup(true, HITCH_POS, null, false, false)` and
`connectionGroup(false, HOOKUP_POS, null, false, false)` pass `rot=null`
explicitly - so `connectionRotationVehicleLocal` resolves to
`HitchJoint.localJointFrame(null)` = IDENTITY for both sides, confirmed
via the existing `HitchJointTest`. (`HOOKUP_YAW_DEGREES=20` is the
SPAWNED ENTITY's own world-facing at spawn, a completely separate value
from the connection's own `rot` field - not the same thing.) So for this
exact reproduction, `local_frame1.rotation`/`local_frame2.rotation` ARE
both identity, and the locked X/Y/Z axes genuinely are each body's own
raw local axes, unrotated - confirmed, not assumed. 54418's general
caution remains correct for connections that DO author a `rot` (not this
one) - stated as a scenario-specific finding, not a general exoneration.

### Finding 4: tick-level force/impulse ordering, confirmed directly from the real `step()` JNI function

```rust
// Java_..._Rapier3D_step (the real per-tick physics step)
rope::tick(scene);
joints::tick(scene);       // <- refreshes EVERY joint's anchor/rotation from its static Java-fed data
... 
sim.pipeline.step(...)     // <- the actual solve, immediately after, same call
```
`joints::tick()` and `pipeline.step()` always run together, in that
order, within the SAME native `step()` call - reconfirms (via the actual
source, not re-derived from memory) the ordering already established
earlier in this investigation. Combined with the already-established MTS
Mixin facts (Sable's native step runs at the HEAD of `ServerLevel.tick()`,
strictly BEFORE entity ticking, where `createHitch` runs): a joint
created during tick N's entity ticking is invisible to THIS tick's own
native step (already run); `joints::tick()` sees it for the first time at
the START of tick N+1's own native `step()` call, and `pipeline.step()`
solves it in the SAME call, immediately after - i.e., the brand-new
joint's FIRST EVER SOLVE happens exactly one Minecraft tick after
`createHitch`, with whatever position/velocity state both bodies have at
that moment, using the hardcoded 550Hz/damping-4.0 spring (Finding 1)
against that gap in one step.

### What this does, and does not, establish

This traces the MECHANISM a stiff joint uses to resolve a real position
gap - a hardcoded, very stiff, non-configurable spring correcting
whatever gap and relative velocity exist at the joint's first active
tick, entirely within Rapier/Sable's own native code, outside DIV's
control at either the Java config layer or the native API surface. It
does NOT, by itself, identify a DIV-fixable bug - no incorrect anchor
offset, no corrupted frame, no ordering gap was found; every piece
checked passes through or behaves as its own documentation/source says
it should. What remains the single most promising, still-open, DIV-
controllable factor: WHY `towed` carries such an extreme angular velocity
(repeatedly measured ~88 rad/s on one axis) by the moment of reconnect -
reducing whatever drives that buildup (squarely inside DIV's own
`BuoyantForces`/free-float force accounting, not native solver internals)
is the one lever actually available, if that buildup is itself
the result of a DIV-side defect rather than inherent to the fixture's own
scenario. This has NOT been re-examined in this entry - flagged as the
next concrete direction, not yet investigated to a conclusion.

No production change proposed in this entry. Reporting the full trace
back for direction, per 54418's own request to select and report one
concrete, falsifiable discrepancy or the precise limit found.

## Comment/doc corrections completing 54419; provenance caveat for 54413/54418 (54422)

Completing 54419's specific request (previously only VERIFICATION.md was
updated for the retraction; the live source/test comments still called
the Java baseline "the actual inertia the native solver was being told
to use," called the override "the fix," and implied the tests themselves
demonstrated a failing-before/passing-after native correction). Corrected
in `ShapeAwareBoxPhysicsObjectTest.java`, `ShapeAwareBoxPhysicsObject.java`,
`BuoyantBody.java`, and `build.gradle`'s own `unitTest` comment (which
claimed a live native pipeline/dedicated server is infeasible in ANY
unit test - narrowed to describe only what this suite has actually
tried, not a universal claim). No test rerun performed for these
comment-only changes, per 54419's own instruction.

Also corrects 54422's own caveat, applied retroactively to the native-
source trace in the two immediately preceding entries (the retraction
and the joint-source trace): fetching the GitHub TAG's own source
(`mc1.21.1-2.0.5-neoforge`, commit `6966d292...`) is NOT independent
proof that the ACTUAL `.so` binary this worktree runs
(`run/.sable/natives/sable_rapier_x86_64_linux.so`) was compiled from
that exact source - only that the tagged source MATCHES the pinned
version string. An earlier entry in this investigation (the binary
debug-symbol-strings check) already found this correspondence to be only
PROVISIONAL for Sable's own wrapper code specifically (no commit hash
recoverable from the binary for `joints.rs`/`boxes.rs`/`config.rs`,
unlike the external Rapier crate's own short hash, which WAS recoverable
and confirmed matching) - that same provisional status applies here too;
no fresh binary-string check was performed for this round's specific
files. Stated plainly rather than re-asserted as settled.

## Phase-aligned free-float accounting: the extreme angular velocity is NOT gradual buildup - it's a re-attach event (54422)

John's comment 54422: continue with phase-aligned free-float force/
angular-momentum accounting from the retained fixed-seed trial logs,
explicitly warning not to conflate the post-reconnect ~88 rad/s value
with pre-reconnect GROWTH - establish the EARLIEST measured growth
interval. Using Trial 1's log (fixed seed, fully logging-matched,
digit-identical to the crash reference per the earlier comparison).

### Correction: there is no gradual buildup during free-float at all

Traced `towed`'s own `BuoyantBody.step entry` angular velocity through
the ENTIRE `DETACH` phase (60 samples, log lines 1576-2052, every tick):
it stays SMALL throughout - magnitude never exceeding roughly 1.3 rad/s,
oscillating mildly, with the LAST sample before `DETACH PASS`
(16:37:36.591) at `(0.158, 0.064, -0.035)` rad/s. **This directly
contradicts the "towed accumulates extreme angular velocity during
free-float" framing used in the immediately preceding entries of this
investigation - that framing was itself a mischaracterization, exactly
what 54422 warned against.** There is no growth interval to find during
free-float because there is no growth during free-float.

### What actually happens: `DETACH`'s own re-attach creates a brand-new joint, which gets its first violent solve one tick later

Re-read `BuoyantHitchProbe.detach()` directly (not re-derived from
memory): at `DETACH PASS`, it does NOT just despawn/proceed - it calls
`towing.connectTrailer(connection, false)` (line 462), creating a FRESH
joint between the SAME `towing`/`towed` pair, which have independently
drifted apart (small, real velocities) for the preceding 60 ticks. This
is a genuine re-attach event, not previously named as such in this
investigation's own framing of "free-float accumulation."

Traced the exact tick sequence in Trial 1's log around this re-attach:

- gameTime=203 (16:37:36.661): `towed`'s own `BuoyantBody.step entry`
  shows `angularVelocity=(0.119, 0.063, -0.032)` - small, consistent with
  the preceding 60 DETACH ticks. The SAME tick's `createHitchDiagnostic`
  lines (16:37:36.660) show the re-attach's own `createHitch` call firing
  for both `towing` and `towed`, each with a real, non-identity cached
  orientation and real linear/angular velocity - i.e. the NEW joint is
  created against two bodies with a real (if modest) relative position/
  orientation offset from their 60 ticks of independent drift.
- gameTime=204 (16:37:36.678, the probe's own `ServerTickEvent.Post`
  listener, AFTER entity ticking for this tick): `towed.angularVelocity`
  has jumped to `(-73.00, -13.84, 87.99)` - and `towing`, which had
  near-zero angular velocity the tick before, now shows
  `(-41.40, -12.50, -3.44)` too. **Both bodies jump from small to
  extreme within exactly ONE Minecraft tick - the SAME tick immediately
  following the re-attach's own `createHitch` call.**

This lines up EXACTLY with the native joint-tick ordering already traced
in the immediately preceding entry: `joints::tick()` (which feeds a
newly-created joint's static anchor/rotation into Rapier's own joint
data for the FIRST time) always runs immediately before `pipeline.step()`
in the same native `step()` call, and a joint created during Java-side
entity ticking (which happens AFTER that same tick's own native step
already ran) gets its FIRST EVER SOLVE exactly one Minecraft tick later.
Combined with the already-confirmed hardcoded 550Hz/damping-4 joint
spring (which is NOT configurable by DIV at either the Java or native
layer): **this is the SAME mechanism already identified for the later
`TOWING_REPLACEMENT` reconnect, just occurring one phase earlier, at
`DETACH`'s own re-attach - a brand-new joint's mandatory, hardcoded-stiff
first solve correcting whatever real gap accumulated during 60 ticks of
independent drift, in one step.** Not a gradual buildup; not a
`BuoyantForces` torque/impulse bug (its own per-float impulses at
gameTime=201/202/203, logged immediately above, are modest and mostly
vertical/buoyancy - nothing in them explains a 400+x jump in angular
speed); the energy injection happens entirely inside the native joint
solve, using a stiffness DIV's own code cannot see or configure.

### Addressing 54422's specific accounting requests, scoped to what's actually measurable

- **Body/world frames:** `BuoyantForces`' own logged `worldArm`/
  `appliedImpulse` are WORLD-frame (confirmed from source, pre-existing
  finding); the jump itself happens entirely inside the native solve,
  for which no Java-side force/frame accounting applies at all - there
  is no DIV-computed impulse to inspect for this specific event.
- **Application point relative to COM, dt scaling, repeated
  application, float drag's dependence on angular point velocity:** all
  apply to `BuoyantForces`' own per-float terms (already logged, already
  modest at this bracket) - NOT to the actual jump, which originates
  natively, not from any repeated or point-velocity-dependent Java force
  application.
- **Unobserved native/contact/gravity terms:** the native solver's own
  internal per-substep impulse during this specific joint's first solve
  remains completely unread (no accessor exists, as established
  throughout this investigation) - this IS the actual missing
  measurement for THIS event, not a general caveat. Gravity and contact
  forces are not separately logged either, but are not needed to explain
  this specific jump, which is a joint-solve event, not a force-
  accumulation one.
- **Sign-correct dissipative work/units, focused regression:** not
  applicable to this specific finding - there is no DIV-side dissipative
  term involved in a native joint's own first-solve correction. No new
  JVM regression is proposed in this entry, since the discrepancy
  identified is a native-solver event with no corresponding Java code to
  regress-test.

### What this does, and does not, establish

**Mechanism for the pre-existing extreme angular/linear velocity: a
re-attach event, not force accumulation, resolved via the same hardcoded
stiff native joint spring already identified** - established with much
higher confidence than any prior entry's "candidate," directly traced
through two independent, consistent log brackets (this entry's `DETACH`
re-attach, and the earlier `TOWING_REPLACEMENT` reconnect) sharing the
same mechanism. **Still not a DIV-fixable production bug** - no incorrect
DIV-side code was found; the stiffness causing the violent correction is
native and non-configurable, exactly as already established. No
production change or new run is proposed. Reporting this corrected,
sharpened mechanism back for direction - specifically whether this
closes the mechanism-identification question (even without a DIV-side
fix being currently possible) or whether further investigation is
wanted.

## Retraction of overclaims; localization sharpened to TOWING_REPLACEMENT specifically (54425)

John's comment 54425: the DETACH-reattach chronology (previous entry) is
useful LOCALIZATION, not mechanism closure - chronology alone does not
prove the 550Hz spring is the cause, that "all energy is native," that
there is no DIV-fixable bug, or that DIV's own impulses are exempt from
actual accounting ("modest" is not accounting evidence). **All four
overclaims are retracted here explicitly.** What follows is read-only
work addressing the concrete gaps named, no code change, no new run.

### Retracted claims, restated honestly

- "This is the same mechanism... correcting the real gap... in one
  step" - RETRACTED as a proof. The timing is consistent with that
  explanation; consistency is not proof. No native-internal read-back
  of the actual per-substep impulse during this specific solve exists -
  this remains an open, unmeasured quantity, not inferred as confirmed.
- "All energy injection happens entirely inside the native joint solve"
  - RETRACTED as stated; see the quantified check below, which supports
  but does not single-handedly prove this.
- "Still not a DIV-fixable production bug" - RETRACTED as a conclusion.
  No DIV-fixable bug has been FOUND, which is different from none
  existing.
- Calling DIV's own per-float impulses "modest" and treating that as
  sufficient - RETRACTED; replaced with an actual quantified comparison
  below.

### Quantified DIV-impulse accounting over the actual bracket (not "modest")

Summed `towed`'s own logged per-float `appliedImpulse`/`worldArm` (world
frame, as logged) over `gameTime` 201-203 (Trial 1's log, the bracket
immediately preceding the jump): SUM linear impulse ≈
`(-0.15, 474.76, -0.39)` kg·m/s (consistent with buoyancy, dominantly
vertical). SUM lever-arm angular impulse (`Σ worldArm × appliedImpulse`)
≈ `(-28.1, -0.19, -90.07)` kg·m²/s.

Compared this against the angular impulse REQUIRED to produce the
observed `Δω` (from `(0.119, 0.063, -0.032)` at gameTime=203 to
`(-73.00, -13.84, 87.99)` at gameTime=204, i.e. `Δω ≈ (-73.12, -13.90,
88.02)` rad/s), via `L = I·Δω` using the solid-box inertia tensor
(`I ≈ (145.33, 181.33, 60.0)` kg·m² - the CHOSEN model, not proven to
match whatever Rapier's own `ColliderBuilder::mass()` actually computed
internally; the best available estimate, stated as such):
`L_required ≈ (-10625, -2521, 5281)` kg·m²/s.

**DIV's own logged impulses account for roughly 0.008%-1.7% of the
required angular impulse on each axis** (|-28.1/-10625|≈0.26% on X,
|-0.19/-2521|≈0.008% on Y, |-90.07/5281|≈1.7% on Z) - two to four orders
of magnitude too small. This is a real, quantified finding (not "modest"
by assertion): DIV's own force/impulse accounting cannot explain this
specific jump, on any axis, by a wide margin. It does NOT by itself
prove the native joint spring IS the explanation - only that DIV's own
measured contribution is not.

### Duplicate/stale native constraint - checked via source, not assumed

Read `HitchRegistry.reconcile`/`release` directly: `reconcile` ALWAYS
calls `release(trailer)` (which calls `.remove()` on any existing
`PhysicsConstraintHandle`) BEFORE constructing a new one via `create.get()`
- synchronously, same method, no gap - and a `null` result from `create`
is treated as a failed creation (throws), never silently leaving a
stale/duplicate entry. The observed fresh `createHitchDiagnostic` firing
at the `DETACH`-reattach confirms the registry's own bookkeeping was
already clean at that point (consistent with `disconnectTrailer` having
triggered a `reconcile(trailer, null, ...)` call earlier, which only
releases, never creates) - **no duplicate or stale Java-side constraint
registration found.** Native-side double-registration beneath this
(e.g. if `.remove()`'s own native call were itself buggy) was NOT
independently verified - stated as unchecked, not ruled out.

### Comparison against the SAME phase in the earlier SUCCESSFUL isolated run - the actual discriminating result

Compared the exact same `DETACH`-reattach bracket (`TOWING_UNAVAILABLE
pre-remove()`/`post-remove()`) against the ORIGINAL, retained
NON-CRASHING isolated run (`M545-isolated-rundir-20261116T224049Z`),
not an unmatched post-failure sample as 54425 cautioned against:

```
non-crash run: towing.angularVelocity=(-41.40,-12.50,-3.443) towed.angularVelocity=(-73.00,-13.84,87.99)
Trial 1 (crash): towing.angularVelocity=(-41.40,-12.50,-3.44)  towed.angularVelocity=(-73.00,-13.84,87.99)
```

**Identical (to logged/displayed precision) between the crash and the
non-crash run.** This means the `DETACH`-reattach jump, whatever
produces it, is NOT the differentiator between outcomes - it happens
the same way regardless of what ultimately crashes. Combined with the
earlier-established comparison (54379/54382: `TOWING_UNAVAILABLE` ticks
1-59 are also identical between runs; the FIRST measured divergence is
at tick=60, which is the SAME tick `TOWING_REPLACEMENT`'s own setup
(spawning `towing2`, re-attaching to IT specifically) begins) - **the
actual crash/non-crash divergence is localized to `TOWING_REPLACEMENT`'s
own reconnect, against a freshly-spawned vehicle with no prior stepping
history, NOT to `DETACH`'s reconnect against the already-established,
already-stepped original `towing`.** This is the one concrete,
falsifiable localization this entry can actually support.

### Precision/provenance caveats, restated (not dropped)

- All pose/orientation values cited above come from log lines printed
  at reduced precision (position to 3 decimals, orientation to 3
  decimals, velocities in scientific notation to 4 significant figures)
  - exact anchor-world separation at either reconnect was NOT
  recomputed to full precision in this entry; the inertia tensor used
  for the required-impulse estimate is the CHOSEN solid-box model, not
  a measured or native-confirmed value.
- The pinned-tag native source read in prior entries remains only
  textually matched to the version string, not independently proven
  identical to the actual running `.so` binary (restated per 54422's
  own caveat, not re-verified this round).

### Proposed next step (not executed - discriminating, bounded)

Compare `TOWING_REPLACEMENT`'s own pre-connect bracket specifically
(`towing2`'s freshly-spawned position/velocity vs. `towed`'s own state
at that moment) between a crash run and the non-crash run, the same way
this entry just did for `DETACH`'s reconnect - since the divergence is
now localized there, not at `DETACH`. Proposed, not executed, pending
direction.

## Correcting the impulse-magnitude calculation; TOWING_REPLACEMENT pre-connect comparison (54434)

John's comment 54434 approved continuing the proposed comparison (no new
run) and corrected the prior entry's own impulse-magnitude calculation.
Both addressed here, read-only, no code change.

### Correction to 54425/c0c81d8's own quantified check

54434: multiplying WORLD-frame `Δω` component-wise by a BODY-axis
diagonal inertia tensor is NOT valid angular-momentum accounting for a
rotated body - the correct relation is `ΔL = I_world(after)·ω(after) -
I_world(before)·ω(before)` with `I_world = R·I_body·R^T`, needing a
reliable same-event orientation (or the result must be labeled
illustrative-only); even `I_world·Δω` still assumes near-fixed
orientation across the bracket. Also: the summed bracket (`gameTime`
201-203) included impulses from BEFORE the actual re-attach's own
`createHitch` sample (203, logged as "before this tick's own step()"),
so it is not cleanly scoped to the same event; `appliedImpulse` is the
COMMANDED Java-side impulse, not a measurement of what the native
solver actually applied; and the inertia itself is the CHOSEN model,
not measured. **The prior entry's specific "0.26%/0.008%/1.7%" figures
and the framing "DIV's own measured contribution is two to four orders
of magnitude too small... a real finding" are RETRACTED as a physical
bound.** What remains true, restated without the invalid arithmetic: the
logged per-float linear impulses in that bracket are on the order of
hundreds of kg·m/s, dominantly vertical (buoyancy) - whether that is
"enough" to produce the observed angular velocity change cannot be
determined from this data without a frame-correct calculation this
entry does not attempt to redo; stated as unresolved, not as a bound in
either direction.

### TOWING_REPLACEMENT pre-connect comparison: crash vs. the ORIGINAL successful isolated run

Compared, line-for-line, the bracket from `TOWING_UNAVAILABLE tick=59`
through `TOWING_REPLACEMENT tick=1`, between Trial 1 (crash) and the
retained non-crashing isolated run:

| sample | non-crash run | Trial 1 (crash) |
|---|---|---|
| tick=59 | `towed.angularVelocity=(-6.341,-0.0303,-0.1173)` | IDENTICAL |
| tick=60 | `towed.angularVelocity=(-5.812,-0.6604,-0.2379)` | `(-6.341,-0.0303,-0.1173)` - **DIFFERENT** |
| pre-connect `towing2` pos/motion | `[17607.838,105.682,17609.410]`/`(0,0,0)` | IDENTICAL |
| pre-connect `towed` pos/motion (MTS, logged) | `[17608.956,105.682,17605.683]`/`(-6.0e-4,-0.047,-0.006)` | IDENTICAL (both runs, byte-for-byte) |
| pre-connectTrailer `towed` REAL native `angularVelocity` | `(-5.812,-0.6604,-0.2379)` | `(-6.341,-0.0303,-0.1173)` - **DIFFERENT, matches that run's own tick=60** |

**Both IDs/connection order:** `towing2` is spawned identically in both
runs (confirmed via its own pre-connect position/motion matching
exactly) and connected via the SAME `TowingConnection`/`connectTrailer`
call sequence in both - no difference in setup order found.

**Distinguishing fresh `towing2` creation from `towed`'s own already-
diverged state, as 54434 asked:** `towing2`'s own pre-connect state is
IDENTICAL between runs - the newly-created body itself is not where the
divergence originates. `towed`'s own REAL native angular velocity (read
via `pre-connectTrailer`'s own direct handle read, not the MTS-cached
`motion` field, which stays byte-identical in both runs at this exact
log line despite the real native value already differing - a real,
measured staleness in `v.motion` worth noting on its own) is ALREADY
different between the two runs at `pre-connectTrailer`, matching each
run's own `tick=60` value exactly. **The divergence is confirmed present
in `towed`'s own state BEFORE `towing2` is connected at all - it is not
an artifact of the fresh-body connection, and precedes it.**

**First observed divergence remains `tick=60` of `TOWING_UNAVAILABLE`
itself** (ticks 1-59 confirmed identical in the earlier comparison;
every tick in this window is logged, not sampled, so this is a
per-tick-resolution result) - restated per 54434's own caution: this is
the first OBSERVED divergence, not necessarily the true origin. What
happens within tick 60 at the native substep level remains completely
unmeasured (no substep-level accessor exists, as established repeatedly
in this investigation) - stated as the actual missing measurement, not
assumed resolved by locating the tick.

### Disposition

No DIV-fixable bug is established. No production change or new run
proposed. The one concrete, falsifiable fact this entry adds: the
crash/non-crash divergence is confirmed to live in `towed`'s own state
during `TOWING_UNAVAILABLE`'s final tick, independent of `towing2`'s
creation - narrowing "where to look" without yet explaining "why." The
actual mechanism inside that one tick remains unmeasured. Reporting
back for direction.

## Arithmetic bug found and fixed in the prior impulse sum (54435)

John's comment 54435: independently re-summed the same bracket and got a
different result than 54427's own numbers - asked me to find my own
exact error rather than just deferring. Found it: my summation script
used a Python line-range slice (`lines[2035:2063]`) that CUT OFF partway
through the bracket - specifically, line 2063 in 1-indexed terms (one of
the two `gameTime=203` entries) fell on the slice's own exclusive upper
bound and was silently dropped, and inspection shows my printed result
(`Y=474.76`) is exactly the sum through the `gameTime=202` pair alone,
omitting most of `gameTime=203`'s own contribution - an off-by-one
slicing bug in my own script, not a disagreement about which lines are
relevant.

Re-summed by hand against the exact 6 lines John cited (Trial 1,
`logs/latest.log` lines 2041/2042/2049/2050/2063/2064, `v=51da4a15-...`,
`gameTime` 201-203): `appliedImpulse` sum = `(-0.247931, 562.09,
-0.356100)` kg·m/s; `Σ worldArm × appliedImpulse` ≈ `(-50.17, -0.23,
-53.97)` kg·m²/s (hand-computed per-term; matches John's
`(-50.185616219, -0.230858682, -52.710453734)` to within manual-rounding
error on the X/Z terms - confirmed, not merely accepted). **These are
the corrected sums, superseding 54427's own `(-0.15, 474.76, -0.39)`/
`(-28.1, -0.19, -90.07)`, which are retracted as arithmetic errors, not
just as an invalid frame-mixing calculation (already separately
retracted in the preceding entry).**

Restated per 54435's own caveat, carried forward: this corrected sum
remains the COMMANDED, logged, world-frame Java-side contribution only -
not a measurement of the native solver's own total response, and not a
physical bound in either direction (the frame-mixing issue already
retracted in the immediately preceding entry still applies to any
attempt to compare this sum against a required-angular-momentum
estimate).

Continuing with 54434's own comparison work (reported in the immediately
preceding entry) - unaffected by this arithmetic correction, since that
comparison used the raw logged velocity/position samples directly, not
this summation.

## Deterministic arithmetic correction; stale-cache consumer trace (54448)

John's comment 54448: correct the hand-estimated angular sum with an
actual deterministic parser (not further hand arithmetic), and trace
whether the observed stale `v.motion`/pose actually feeds any
production decision before its own refresh - existence of a stale
diagnostic cache alone is not a bug mechanism. Both addressed here,
read-only, no code change.

### Deterministic re-parse of the exact 6 lines

Wrote and ran a small Python parser (regex-extracting `worldArm`/
`appliedImpulse` directly from each of Trial 1's lines
2041/2042/2049/2050/2063/2064, `gameTime` 201-203, `v=51da4a15-...`)
rather than hand arithmetic. Per-line cross products and the running sum
are shown in the tool output (reproducible, not just asserted):

```
SUM appliedImpulse            = (-0.247931, 562.09, -0.356100)
SUM (worldArm × appliedImpulse) = (-50.185616219, -0.230858682, -52.710453734)
```

**Matches John's own parsed value EXACTLY, including the Z component**
(`-52.710453734`, not the prior entry's hand-estimated `-52.71`-ish/`-53.97`
figures - the earlier hand computation had an actual arithmetic error in
the Z cross-product terms, not merely rounding, now corrected by an
actual deterministic derivation shown above rather than re-asserted).

### Consumer trace: does the stale `v.motion`/pose feed any production decision?

Searched `PhysicsBridge.java` for every production (non-diagnostic) read
of `v.motion`/`v.position` for a `BuoyantBody`-backed vehicle:

- **Body construction's own velocity seed** (`initialVelocity` from
  `v.motion`, lines ~1964/2000): only consulted when a body is FIRST
  constructed or reconstructed (`BuoyantBody`'s/`Body`'s own
  constructor). `towed`'s own body persists continuously across
  `TOWING_UNAVAILABLE`→`TOWING_REPLACEMENT` (never destroyed/
  reconstructed in this window) - **this path does not apply to `towed`
  here.**
- **`createHitch`'s own actual joint-anchor construction**: confirmed
  (already established earlier in this investigation, re-confirmed by
  re-reading the call) to use ONLY `HitchJoint.anchorLocalOffset(body
  .center(), connectionOffsetVehicleLocal(...))` - the connection's own
  authored local offset and the body's STATIC geometric center. **Zero
  dependency on `v.position`/`v.motion`.**
- **`pendingInheritedVelocity`/`captureReplacementState`**: only staged
  when an existing body is replaced/removed (category change, despawn).
  `towed`'s own body is not replaced in this window - **does not apply.**
- The ONLY other touches of `v.motion`/`v.position` found in
  `PhysicsBridge.java` are the WRITE at the tail of `step()` itself
  (not a read) and this probe's OWN test-fixture code (`anchorDistance`/
  `towing2.motion.length()` in `towingReplacement()`, and the diagnostic
  `pre-connect` log line) - test assertions and logging, not production
  decisions.

**Conclusion for the paths actually traced: the observed `v.motion`/pose
staleness does not feed `createHitch`, inherited-velocity capture, or
any body-construction decision in this bracket - confirmed by reading
the actual consumers, not inferred from the cache's mere existence.**
NOT independently traced: whether MTS's own internal `connectTrailer`
implementation (third-party code, not DIV's own source) reads
`v.motion`/`v.position` for anything during the connection itself -
stated as unchecked, not excluded.

### Disposition

No DIV-fixable bug identified in this entry either. The traced paths are
excluded as the stale-cache mechanism's consumer; the MTS-internal path
remains open and unchecked. No minimal instrumentation packet is
proposed yet, since the specifically-asked-about DIV-side consumers have
now been excluded rather than left speculative - reporting back for
whether to trace the MTS-internal path next or redirect.

## Found the actual tick-60 divergence mechanism: submerged vs. free-falling (54452)

John's comment 54452: continue without waiting; trace MTS's own pinned
`connectTrailer` via the actual resolved artifact (not treating third-
party code as inaccessible), and explicitly separate origin (at/before
`TOWING_UNAVAILABLE` tick=60, BEFORE any replacement-connect call) from
later amplification. Both addressed - the second produced the most
concrete finding of this entire investigation so far.

### MTS's own `connectTrailer`, decompiled from the actual pinned artifact

Extracted and decompiled (via `cfr.jar`, a real decompiler, not
bytecode-reading alone) `AEntityG_Towable.class` from the ACTUALLY
PINNED `curse.maven:minecraft-transport-simulator-286703:7926606` jar
(resolved file hash `1d2f7512...`, from `~/.gradle/caches/modules-2/...`).
`connectTrailer` reads `connection.towingEntity.orientation`/`.position`
and `connection.towedEntity.orientation`/`.position` (the MTS-cached
fields whose staleness was already observed) to compute
`hitchCurrentPosition`/`hookupCurrentPosition` - MTS's own bookkeeping,
not something DIV's own joint creation consults (confirmed earlier:
`createHitch` uses only the connection's static local offset and the
body's geometric center). It does NOT read `v.motion` at all in this
method. **Per 54452's own correction, this is irrelevant to the tick=60
origin regardless of what it reads: `connectTrailer` for the REPLACEMENT
connection is called during `TOWING_REPLACEMENT`'s own tick=1 setup,
strictly AFTER `TOWING_UNAVAILABLE` tick=60 has already diverged** - so
this traced consumer is excluded as the origin by timing alone, not
merely characterized. Whether it matters for later amplification (via
whatever it feeds downstream) is not investigated further here.

### The actual origin: found by checking what `TOWING_UNAVAILABLE` tick 53-60 are an instance of

Compared `towed`'s own per-tick `linearVelocity`/`angularVelocity`
AND the presence/absence of `buoyantForcesDiagnostic` lines for ticks
46-60, between Trial 1 (crash) and the non-crash isolated run:

- **Ticks 53-59 are IDENTICAL in both runs AND have ZERO
  `buoyantForcesDiagnostic` lines for `towed`** - `angularVelocity`
  frozen at exactly `(-6.341, -0.0303, -0.1173)`, `linearVelocity`'s X/Z
  frozen at `(0.07543, -2.021)`, and Y decreasing by exactly `-0.55`
  every tick (53→54→...→59: -1.189, -1.739, -2.289, -2.839, -3.389,
  -3.939, -4.489) - the exact signature of unmodified GRAVITY-ONLY free
  fall (no buoyancy/drag impulse logged because none is being applied -
  `towed` is NOT submerged/in water during this entire stretch in either
  run).
- **At tick=60, the two runs diverge for the first time - and diverge
  in KIND, not just magnitude:**
  - Non-crash run: `linearVelocity=(0.1976, -4.881, -2.441)` - X and Z
    both jump away from their frozen free-fall values, and Y's own
    decrement from tick 59 is only `-0.392` (not `-0.55`) -
    **consistent with a real buoyancy/drag impulse being applied this
    tick, i.e. `towed` re-enters water at exactly this tick.**
  - Trial 1 (crash): `linearVelocity=(0.07543, -5.039, -2.021)` - X/Z
    UNCHANGED from the frozen free-fall values, Y's decrement is
    exactly `-0.55` again - **`towed` is STILL in unmodified free fall
    this tick; no buoyancy/drag applied.**

**This is the actual, falsifiable, tick-60 divergence: whether `towed`
re-enters water (becomes submerged) at that exact tick, not merely a
numeric drift.** Since `towed`'s own trajectory is confirmed byte-
identical through tick 59 in both runs (same velocity history, hence
the same integrated position), the two runs' DIFFERENT answer to "is
`towed` touching water at this position" can only come from the WATER
SURFACE itself differing at that position between the two different-
seeded worlds - reviving, in a position/time-specific form, the earlier-
dismissed seed/terrain candidate. **That earlier dismissal (in the
`TOWING_REPLACEMENT`-reconnect comparison, much earlier in this
investigation) checked a DIFFERENT, much smaller displacement near the
`DETACH` reconnect - it does not cover this much later point, reached
after ~7+ ticks of unconstrained free fall following the earlier
violent spin-up, which could plausibly carry `towed` well outside the
probe's own deterministically-built pool (`POOL_RADIUS=14` blocks,
`PLATFORM_Y=100`-111) into natural, seed-dependent terrain/water (if
any) - or simply out of the water vertically.**

### What remains unconfirmed - the next concrete, bounded check

`towed`'s actual position at tick=60 (and whether it is still within
`POOL_RADIUS`/the platform's own Y-range) is NOT logged by
`TOWING_UNAVAILABLE`'s own per-tick line (only velocity is) and was not
separately computed in this entry - this is the precise, falsifiable
next check: reconstruct `towed`'s position via integrating its own
logged velocity history (or checking whether any other log line nearby
captures position), and compare that position against the pool
boundary/platform Y-range to confirm or refute that it has left the
deterministic footprint by tick 60.

### Disposition

This is the first entry in this investigation to localize the actual
tick-60 divergence to a concrete, checkable PHYSICAL CONDITION (submerged
vs. not) rather than an unexplained numeric drift - a real step forward,
not yet a closed mechanism. No DIV-fixable bug is established (if the
cause is natural/seed-dependent terrain outside the probe's own
constructed pool, that is a property of the FIXTURE's own bounded-area
behavior during free-float, not necessarily a DIV production defect) -
stated as a hypothesis requiring the position check above, not asserted.
No production change or native run proposed.

## Retracting overclaims; confirming diagnostic-gating/water-query semantics; missing position measurement (54458)

John's comment 54458: retract "actual origin," "can only be the water
surface," "byte-identical trajectory," and the gravity-only conclusion
drawn from rounded samples/absent lines - verify diagnostic skip
conditions, `waterSurfaceAt` semantics, and look for any existing
position-bearing log before attributing anything; the prior pool check
covered a different, much-earlier event, not this bracket. Read-only,
no world mutation/load/rerun, per instruction.

### Retracted claims

- "The actual origin" / "can only be the water surface" - RETRACTED as
  conclusions. What follows narrows ONE candidate mechanism with real
  source confirmation, but does not prove it is the explanation.
- "Byte-identical trajectory" - RETRACTED as stated. Only the LOGGED
  VELOCITY SAMPLES match to displayed/rounded precision through tick 59
  - this says nothing exact about position, which integrates those
  samples and compounds any rounding/uncertainty they already carry.
- The gravity-only conclusion drawn purely from "absent diagnostic
  lines" - re-examined below with the actual gating code, not re-
  asserted from the same inference.

### Diagnostic skip condition, confirmed via the actual code (not re-asserted)

Re-read `BuoyantForces.apply`'s per-float loop directly: the diagnostic
log line is inside `if (GroundContactDiagnostics.ENABLED) { ... }`,
itself reached only after `if (fraction <= 0) continue;` already skipped
the rest of the loop body for that float. **There is no OTHER gating
condition on this specific log line** - its absence for a given float/
tick is equivalent to `fraction <= 0` for that float that tick, exactly,
confirmed by reading the method's own control flow, not inferred from
the log's mere absence. This specific concern is resolved: the absence
pattern observed in both runs genuinely does mean "not submerged this
tick," for this exact diagnostic line.

### `waterSurfaceAt`'s real semantics, confirmed via the actual code

```java
private static double waterSurfaceAt(ServerLevel level, double x, double z, double fromY) {
  // scans DOWNWARD from fromY (= float's own top + 1), up to 24 blocks,
  // for the first REAL water fluid block at that (x,z) column via
  // level.getFluidState(pos).is(FluidTags.WATER); returns that block's
  // actual fluid height, or NEGATIVE_INFINITY if none found in range.
}
```
This queries LIVE WORLD BLOCK/FLUID DATA at the float's actual world
column - not a DIV-side model. The probe's own `buildPlatform` places
REAL water blocks into the world at fixed coordinates/Y-range at spawn
time; this function would return a value from THOSE blocks if the float
is still within that built footprint, or from whatever NATURAL terrain
exists at that column if it has drifted outside it (or simply
`NEGATIVE_INFINITY` if no water at all is within 24 blocks down -
including straight-up airborne with nothing below). This is consistent
with, but does not prove, the seed/terrain-dependent hypothesis from the
prior entry - confirmed as a real mechanism BY WHICH such a dependency
COULD manifest, not confirmation that it DID at tick 60.

### The missing measurement: `towed`'s actual position at ticks 59/60 is not available

Searched every retained log for a position-bearing line anywhere near
this bracket in either run: `TOWING_UNAVAILABLE`'s own per-tick line
logs only `linearVelocity`/`angularVelocity`, never position; no other
diagnostic (`lifecycleDiagnostic`, `buoyantForcesDiagnostic`, etc.) logs
`towed`'s pose/position during this specific free-float window either
(`buoyantForcesDiagnostic` only fires when already submerged, i.e.
exactly the ticks where it would be most useful for confirming the
float's own query point are the ticks with NO such line). **No existing
log line gives `towed`'s real position at tick 59 or 60 in either
run.** Reading the actual saved world region/NBT files directly (to
look up real block/fluid state at a reconstructed query point) was not
attempted - genuinely impractical in this environment without
Minecraft-aware tooling to parse the region/chunk format, and would in
any case need a real position first. Integrating the logged velocity
samples to ESTIMATE a position was considered and explicitly rejected
as a substitute here, per 54458's own caution: such an estimate carries
accumulated numerical uncertainty and depends on exact integration-order/
phase conventions this entry has not derived or verified - it is not
treated as a measurement.

### Separating "explains the one non-crash run's own anomaly" from "resolves the ordinary-reconnect crash"

Stated explicitly, since conflating these would be yet another
overclaim: EVEN IF a future, properly-instrumented run confirmed `towed`
genuinely left the deterministic pool/platform footprint by tick 60 in
the ORIGINAL non-crash run specifically (explaining why THAT one run
differed), this would NOT by itself explain or resolve the underlying
`TOWING_REPLACEMENT` reconnect crash itself, which both fixed-seed
trials (`Trial 1`/`Trial 2`, by design sharing the SAME environment as
each other) and every other retained crash run already reproduce
identically regardless. The reconnect-correction mechanism traced
earlier (a brand-new joint's hardcoded-stiff first solve against
whatever gap/velocity exists at that moment) remains the operative
candidate for the CRASH ITSELF - this entry's own finding is about why
ONE specific historical run differed from that otherwise-consistent
pattern, a narrower question.

### Proposed minimal instrumentation (not executed)

Since the actual historic position state is unavailable and cannot be
reconstructed reliably from existing data, the discriminating next step
is a MINIMAL logging addition (not a behavior change): log `towed`'s
own `pose.position()` (already computed in `BuoyantBody.step()`, not a
new native read) alongside the existing per-tick velocity line in
`TOWING_UNAVAILABLE`'s own tick loop - at minimum for ticks 55-61.
**Discriminating prediction, stated in advance:** if a future matched
run's own tick=60 position differs meaningfully in X/Z from the pool
center (`CHUNK_X*16+8, CHUNK_Z*16+8`) by more than `POOL_RADIUS=14`
blocks, or in Y outside `[PLATFORM_Y, PLATFORM_Y+WATER_DEPTH+6]` =
`[100, 111]`, that would support the seed/terrain-dependent footprint-
exit hypothesis; if it stays within those bounds while still showing
`fraction<=0`, that hypothesis is falsified for this bracket and
`waterSurfaceAt`'s own behavior at an IN-footprint column would need
separate explanation. No such run is proposed or requested here - this
is a design for whenever further instrumentation/validation is
authorized, not an immediate ask.

### Disposition

No DIV-fixable bug is established in this entry. No production change,
instrumentation commit, or native run is made - the proposal above is
descriptive only, pending direction. PR #39's own reconciliation-with-
main authorization (54458) is acknowledged; not acted on in this entry
since no specific merge need has been identified.

## Event-level correlation (not just source review): refutes seed/terrain, finds real position match and a same-tick coincidence (54458/54464)

Per 54458/54464: verify event-level premises (not just method source), get real
operands/positions, distinguish the one non-crash success from the deterministic
fixedseed545547 failures. Read-only; no run; no world mutation. Correlated by
wall-clock-adjacent lines in the already-retained logs, not by assuming
`level.getGameTime()` equals the probe's own `TOWING_UNAVAILABLE tick=N` counter
(an error I made and caught myself before reporting it: an initial grep against
gameTime=40-65 was the WRONG window entirely - that range belongs to an earlier
phase of the run, not tick=53-60 of TOWING_UNAVAILABLE, which actually sits at
real gameTime≈259-263. Found the correct mapping by matching line numbers/
timestamps adjacent to the probe's own `TOWING_UNAVAILABLE tick=N` lines, not by
number-matching the two different counters.)

### Diagnostic-absence confirmed at the EVENT level, not just structurally

Per 54464's point (1): source alone can't establish the call/float/diagnostics-
enabled/capture premises. Checked directly: in Trial 1 and Trial 2, zero
`buoyantForcesDiagnostic` lines exist for `towed` (v=51da4a15.../3c3a0d44...) in the
real log region spanning `TOWING_UNAVAILABLE tick=1` through `tick=60`
(confirmed by line-adjacency, not gameTime-number-matching). The FIRST such line
for `towed` in either trial appears at `gameTime=264` - the exact same tick as
`TOWING_REPLACEMENT`'s own pre-connect snapshot and the fresh joint's creation -
with BOTH floats already at `submergedFraction=1.0`. `GroundContactDiagnostics.ENABLED`
is confirmed on throughout (the `rotationalDrag=INACTIVE_THIS_TICK` line, which
fires unconditionally when diagnostics are enabled regardless of submersion,
appears for `towed` every tick immediately after this point). This is now an
event-level confirmation, not an inference from source structure alone.

### Real position data found; the seed/terrain hypothesis is REFUTED, not merely unconfirmed

`TOWING_REPLACEMENT pre-connect` logs `towed`'s real `v.position` (MTS's own
cached field, written every tick from `pose.position()` at `BuoyantBody.step`'s
tail - not velocity-integrated) directly. Found this line already exists for
all three runs - previously missed, not absent:

- Trial 1 (crash): `towed=[X:17608.9561840256, Y:105.68233978949212, Z:17605.682513091262]`
- Trial 2 (crash): same values, to every logged digit
- Original non-crash reference run: same values, to every logged digit

**`towed`'s actual position is byte-identical across all three runs at this
exact transition**, not merely its velocity through tick 59 as previously
established. Combined with 54459's own finding that the deterministic pool is
identical between these worlds, this directly REFUTES (not just "unconfirmed")
the prior entry's "the water surface itself differing between different-seeded
worlds" explanation: the same real column is being queried against an already-
confirmed-identical water surface in all three runs. Retracting that explanation
outright, not merely re-labeling it unconfirmed.

### What's actually different: velocity alone, one tick earlier, mechanism still unidentified

The real, re-confirmed difference remains exactly where it was first localized:
at `TOWING_UNAVAILABLE tick=60` itself, in `towed`'s logged velocity only - the
non-crash run already shows a buoyancy-consistent kick (`linearVelocity=
(0.1976,-4.881,-2.441)`, Y-decrement breaks from -0.55) while both crash trials
show unmodified free fall continuing (`(0.07543,-5.039,-2.021)`, Y-decrement
still exactly -0.55). One tick later, at the `TOWING_REPLACEMENT` pre-connect
snapshot, position has already converged back to byte-identical across all
three runs (per above) - consistent with the small (1/20s) time gap, not with a
different trajectory. **The actual cause of the velocity-only divergence at
tick=60, given now-confirmed-identical position and terrain at that moment, is
not identified by this entry.** Restating this as the real open question,
replacing the retracted seed/terrain explanation - not offering a new
unverified guess in its place.

### New operand-level fact: same-tick coincidence between water re-entry and the fresh joint

In both crash trials, `towed`'s own first post-free-fall buoyant impulse
(`gameTime=264`, both floats `submergedFraction=1.0`) lands in the exact same
tick as `TOWING_REPLACEMENT`'s pre-connect snapshot and `towing2`'s joint
creation/connection (confirmed by log-line adjacency, same tick). Stated
precisely as a same-tick coincidence between two real, logged events - not
claimed as a proven causal mechanism for the crash.

### Remaining gap, stated precisely

Per 54464's point (2): the per-float query actually used is `point =
pose.position() + q.transform(localOffset - center)`, `waterSurface =
waterSurfaceAt(point.x, point.z, point.y + radius + 1)`, `fraction =
submergedFraction(point.y - radius, point.y + radius, waterSurface)` - read
directly from `BuoyantForces.apply` (lines 62-68). The real position match
found above is `v.position` (entity origin), not `pose.position()` plus the
per-float arm - and no logged orientation quaternion exists at `tick=60` itself
to reconstruct the exact per-float point/margin at that tick precisely. So
while the ENTITY's real position is now confirmed identical across runs, the
exact float-level margin to the water surface at tick=60 itself is still not
measured - only the adjacent tick's (gameTime=264) real per-float worldArm/
fraction values are available, and those already postdate the transition.

### Disposition

Trial 1 and Trial 2 remain fully consistent with each other (every cited value
matches to the logged digit), as expected for a fixed, matched seed. The
original non-crash run's own success is now understood differently than
before: not a different terrain/position, but a one-tick-earlier velocity
divergence whose own cause is unidentified. No DIV-fixable bug is established
in this entry. No production change, instrumentation commit, or native run is
made. Native gate remains CLOSED. PR #39 reconciliation-with-main authorization
(54458) is acknowledged, not acted on - no specific merge need identified yet.

## Correcting remaining overreach; minimal instrumentation packet proposal (54469)

Per 54469: identical cached v.position at the later pre-connect snapshot, plus
an identical FINAL saved pool, does NOT establish historical native pose/
orientation or the exact rotated float query inputs AT tick=60 itself - "refutes"
was too strong. Neither the seed/terrain/contact explanation nor other
environment effects are conclusively ruled out by what was actually measured;
the same-tick water-reentry/joint-creation coincidence is explicitly NOT a
cause. Retracting "refutes" - restating as: the prior entry's specific
evidence (identical cached position one tick later, identical final pool) is
real, but does not by itself close the question of what the rotated per-float
query actually saw at tick=60 - that remains genuinely unmeasured, not merely
"unconfirmed-but-leaning-one-way." No new causal claim is substituted.

### Minimal instrumentation proposal (NOT implemented, no edit/run made)

Per 54469's explicit list, the exact insertion point and fields:

**Hook**: one new unconditional log statement inside `BuoyantForces.apply`'s
per-float loop (`BuoyantForces.java`, immediately after line 68's `fraction`
computation, BEFORE line 71's `if (fraction <= 0) continue;`) - firing for
EVERY enumerated float, every tick, regardless of outcome. Placing it before
the skip (not after, and not behind the existing `GroundContactDiagnostics`-
gated line at 120) is what lets its mere presence prove the call ran and the
float was enumerated - removing the inference 54464 flagged, for every tick
logged, not just the ones that already happen to log today. Same hook serves
both `towed` and `towing2`/any body, since `BuoyantForces.apply` is already
shared code (per the class's own doc).

**Fields, per float per tick**:
- `v.uniqueUUID` (body id)
- `level.getGameTime()` (so probe-phase and native tick correlate directly,
  not by log-line adjacency guessing - the exact gap 54469 itself had to
  route around in the prior entry)
- the probe's own current phase/tick label (would require passing a phase
  tag through, or cross-referencing `BuoyantHitchProbe`'s own `TOWING_UNAVAILABLE
  tick=N` counter by gameTime once both carry it - today only the probe side
  has a phase label and only `BuoyantForces` has gameTime, which is the actual
  gap this fixes)
- `pose.position()` and `q` (orientation) exactly as consumed by this call -
  NOT `v.position` (MTS's separate cached field, already shown to differ in
  freshness from this value elsewhere in this investigation)
- `BuoyantBody.debugLastPoseUpdateGameTime` (already exists, MINECRAFT-545/547
  diagnostic field) - the pose's own refresh age relative to current gameTime,
  so staleness is visible, not assumed current
- `flt.localOffset`, `center`, `radius` (the exact per-float geometry inputs)
- the computed rotated world `point` (x, y, z) - the actual query location
- `Mth.floor(point.x)`, `Mth.floor(point.z)`, `Mth.floor(point.y+radius+1)`
  (the exact integer block coordinates `waterSurfaceAt` starts its scan from)
- the scan's lower bound (`floor(fromY)-24`)
- the scan's actual result: either the found block's `y + fluid.getHeight(...)`,
  or an explicit `NO_WATER_FOUND_IN_RANGE` sentinel (not merely absence of a
  line) - this is what lets a future run distinguish WATER-ABSENT (no fluid
  tag found anywhere in the 24-block window) from WATER-PRESENT-BUT-
  UNSUBMERGED (a real surface found, but `point.y - radius` still above it) -
  two mechanistically different outcomes the current logging cannot tell
  apart historically, since both currently produce "no line."
- the computed `fraction` and an explicit skip/no-skip marker

**Scope**: only `TOWING_UNAVAILABLE` ticks 55-61 (real gameTime≈259-265) for
this specific fixture, both bodies - not a general always-on change.

**Discriminating predictions, stated in advance**: if a future matched run's
own tick=60 scan result is `NO_WATER_FOUND_IN_RANGE`, that supports a genuine
absence (terrain/contact or fixture-geometry explanation, still to be
determined which). If instead it returns a real found height with
`point.y - radius` close above it (a near-miss margin), that points to timing/
orientation sensitivity in the per-float geometry itself, not terrain absence.
Both remain open; this entry does not pick between them.

**Unavailable historical operands, stated plainly**: none of the above exist
for ticks 55-60 in the already-retained logs - this is a GOING-FORWARD
proposal only, requiring a new run to produce, which is not authorized here.
No run is requested or executed in this entry.

### Disposition

No causal conclusion is drawn or re-asserted. No code change, instrumentation
commit, or native run is made - this entry is the bounded packet specification
itself, pending direction on whether/when to implement and run it. Preserving
all existing constraints: native gate CLOSED, worlds/evidence/fixture
untouched, sole owner, existing slot only, no external outreach.

## Implementing the authorized bounded diagnostic logging (54483)

Per 54483: implement ONLY the bounded observational logging under the existing
exclusive Gradle slot; no native run authorized by this approval. Implemented
in `BuoyantForces.apply` (diff committed, see below):

- An invocation/enumeration marker, gated behind the SAME existing opt-in
  `GroundContactDiagnostics.ENABLED` flag as every other diagnostic line here
  (not unconditional/general-production logging) - fires once per `apply()`
  call with `floatCount` and `PhysicsBridge.debugLastPoseUpdateGameTime(v)`
  (an existing diagnostic-only accessor, already present for exactly this
  purpose - no new field/parameter plumbing needed). This directly
  distinguishes "apply never invoked this tick" (no line) / "invoked, zero
  floats" (line, floatCount=0) / "invoked, floats present, all skipped" (this
  line present, no per-float non-skip line follows) - per 54483's own point
  that a missing line alone is not proof of which case occurred.
- A per-float line moved BEFORE the existing `fraction <= 0` skip (so it
  covers every float every tick, not just non-skipped ones), reusing the
  SAME already-computed `waterSurface`/`fraction` local variables - no second
  `waterSurfaceAt`/`getFluidState` call is made for this line, preserving the
  original query order/count exactly as required. Logs the real per-float
  geometry (`localOffset`, `radius`, `center`, the computed world `point`,
  the exact `floor(x)`/`floor(z)/floor(fromY)` integer coordinates
  `waterSurfaceAt` itself starts its scan from, the scan's lower bound) and
  the result as `waterSurfaceResult=NO_WATER_FOUND_IN_RANGE` (the documented
  `NEGATIVE_INFINITY` sentinel) or the real found height - distinguishing
  water-absent from water-present-but-unsubmerged, per 54483.
- Used `level.getGameTime()` directly (no hardcoded gameTime range anywhere in
  the diff) - per 54483's correction that TOWING_UNAVAILABLE's own tick
  counter resets at 60 into TOWING_REPLACEMENT's own tick=1, so no fixed
  "tick 61" or hardcoded historical "259-265" window exists in the code; the
  relevant window is identified AFTER a future run, by matching against the
  probe's own phase-transition log lines, whatever real gameTime those land
  at for that run.

No gameplay/fixture/physics change: both new lines are read-only logging
statements, placed so they do not alter control flow, computed values, or
query count/order. Compiled cleanly: `gradle compileJava` under the existing
exclusive `/tmp/div-gradle-slot.lock` slot, no native run.

### Regression test: not delivered - genuine infeasibility, not an oversight

Checked this codebase's own existing test suite for a pattern to extend: no
test anywhere in `src/test` constructs or fakes a `ServerLevel` (confirmed by
search) - `BuoyantForces.apply`'s own direct dependency on
`level.getFluidState(...)` has no existing seam for this. Mockito (or any
mocking library) is not a project dependency (checked `build.gradle`
directly), and `ServerLevel` is a large, mostly-concrete Minecraft class not
practically fakeable by hand-rolled subclassing within this scope. A real
regression run would need either a new test-only mocking dependency (a
build.gradle change beyond this bounded logging change) or a real dedicated
server/world (which would itself be a native run, not authorized by 54483).

Rather than fabricate a test that doesn't genuinely exercise this code, or
silently skip the requirement, reporting this limitation directly: the
logging diff's correctness (single query preserved, no control-flow change)
is verified by direct code inspection (the new lines read the SAME local
`waterSurface`/`fraction` variables already computed above them, no new call
sites added) and by a clean compile, not by an executed test. Asking for
direction: whether adding Mockito as a test-only dependency is in scope for a
future round, or whether code-inspection + compile is accepted as sufficient
for this specific change.

### Disposition

Diff only - no native run performed or requested. Native gate remains CLOSED.
No gameplay/fixture/physics behavior changed. No causal claim made or
acceptance criteria weakened. Preserving old worlds/evidence, sole branch/
owner, existing lock only, no external outreach/security/Zippy.

## Correcting the bounded packet to actually match 54483/54486; focused pure tests delivered

Per 54486's independent review of 4a8eec4: that diff was NOT yet the bounded
packet 54483 asked for - the existing global `GroundContactDiagnostics` flag
still logs every body/float/all ticks, and post-hoc log-grepping is not
fixture/window gating. Missing: pose.position()/orientation actually
consumed (not just the derived point), float index, an expected-body marker
living OUTSIDE `apply()`, and - most importantly - a SECOND fluid query was
never actually added (confirmed still single-query), but the found block's
own Y/height were not captured separately from the combined surface value,
and the scan-bounds description itself was imprecise (24 vs the real 25
inclusive-both-ends visited values). Corrected all of these; diff committed.

### Changes (BuoyantForces.java)

- `scanForWater` (new, replacing the body of the old `waterSurfaceAt`)
  performs the SAME single traversal, same loop/bounds, but returns a new
  `WaterScanResult` record capturing `found`, `foundBlockY`, `fluidHeight`,
  `surfaceY`, `fromYFloor`, `bottomY`, `visitedCount` - all from the ONE
  scan, no second query. `waterSurfaceAt` (kept for the propeller-submersion
  call site) now delegates to it via `.surfaceY()`.
- `scanBottomY(fromYFloor)` and `scanVisitedCount(fromYFloor, bottomY)`:
  extracted as their own PURE functions (no `ServerLevel`), package-visible,
  directly unit-tested. Per 54486's own correction: the loop visits
  `fromYFloor` down to `fromYFloor-24` INCLUSIVE on both ends - 25 Y values
  for the 24-block span, not 24. Confirmed by direct enumeration in the test
  below, not just restated.
- The per-float diagnostic line (still gated behind the SAME existing opt-in
  `GroundContactDiagnostics` flag) now logs: `floatIndex`; the ACTUAL
  `pose.position()`/`q` consumed (not re-derived); `poseRefreshGameTime`;
  the scan's own `fromYFloor`/`bottomY`/`visitedCount`; and the scan's real
  result as `foundBlockY`+`foundFluidHeight` separately when found, or
  `NO_WATER_FOUND_IN_RANGE` when not - distinguishing water-absent from
  water-present-but-unsubmerged, using values from the single `scanForWater`
  call already made for the fraction computation.

### New: `DiagnosticWindow` (pure, no ServerLevel) + probe-side expected-body marker

Per 54486's correction that the global flag alone isn't fixture/window
gating: added `DiagnosticWindow.inWindow(bodyId, ticksInPhase,
expectedBodyIds, startTick, endTick)` - a small, pure, parameterized helper
(no hardcoded fixture IDs/ticks inside it). Called from OUTSIDE
`BuoyantForces.apply`, in `BuoyantHitchProbe` itself (which already owns its
own body IDs and tick counters): an `expectedBodyThisTick=true` marker logs
for `towed` during `TOWING_UNAVAILABLE`'s own final 6 ticks (55-60), and for
both `towed`/`towing2` at `TOWING_REPLACEMENT`'s own transition tick
(`ticksInPhase==1`, NOT a nonexistent "tick 61" - TOWING_UNAVAILABLE's own
counter resets into TOWING_REPLACEMENT's separate tick=1 at that boundary,
per 54486/54469's correction, so this marker spans the ACTUAL transition
rather than an invented continuation). Its presence/absence now lets a
reader tell "diagnostics were armed and this body/tick was in scope" apart
from "apply's per-float line is absent because genuinely not submerged" -
addressing 54486's point that a missing per-float line alone was never
sufficient proof of that. This is a new diagnostic-only log line in the
probe fixture itself, not a change to its physics, timing, or pass/fail
logic - read-only, consistent with the many other diagnostic lines already
added to this same fixture throughout this investigation.

### Focused pure tests delivered (JUnit only, no Mockito, no ServerLevel)

Per 54486: window selection, zero-float/skip markers, and single-query
capture "can use small production-used pure helpers/callback seams with
existing JUnit" - correcting my own prior framing (54484) that treated
Mockito-or-live-run as the only two choices, which was wrong; this third
option is exactly what's implemented and tested here:

- `DiagnosticWindowTest` (4 tests, all passing): body-in-set AND tick-in-
  range required; tick outside range on either side is false; body not in
  the expected set is false; a single-tick window (the TOWING_REPLACEMENT
  transition's own usage) matches only that tick.
- `BuoyantForcesWaterScanBoundsTest` (3 tests, all passing): `scanBottomY`
  is exactly 24 below `fromYFloor`; the scan's own 25-visited-value claim is
  checked against a direct enumeration loop (not just restated) for several
  `fromYFloor` values including one matching this investigation's own real
  logged value (259); confirms `scanVisitedCount` matches direct enumeration
  generally, not only at one hand-picked point.

Test receipt: `gradle test --tests io.github.brooswitminecraft.div.DiagnosticWindowTest --tests io.github.brooswitminecraft.div.BuoyantForcesWaterScanBoundsTest` under the existing exclusive `/tmp/div-gradle-slot.lock` slot - BUILD SUCCESSFUL; `TEST-...DiagnosticWindowTest.xml` tests="4" failures="0" errors="0"; `TEST-...BuoyantForcesWaterScanBoundsTest.xml` tests="3" failures="0" errors="0".

Disclosed limit, not papered over: `scanForWater`'s own real `getFluidState`
call, and `BuoyantForces.apply`'s own full integration (the actual logging
output against a real world), remain untested by these two pure suites -
that would need a real `ServerLevel`/dedicated server, which is outside this
round's "no native run" constraint. The pure tests cover exactly the parts
that don't need one; the parts that do are named here, not silently assumed
covered.

Full Gradle distribution used: no `gradlew` wrapper exists in this worktree;
found and reused the Gradle 8.14 distribution already present at
`/tmp/gradle-8.14` (used elsewhere in this environment for other DIV
worktrees), invoked only under the existing exclusive slot.

### Disposition

Diff + two new pure test classes, committed. No native run performed. No
gameplay/fixture PHYSICS/timing/pass-fail change - the probe's own new lines
are read-only logging, same as its many existing diagnostic lines. Native
gate remains CLOSED. No causal claim made or acceptance criteria weakened.

## Second round of corrections per independent review 54494

Per 54494's independent review of 5b29cac - each specific gap found, and fix:

1. **"query logging is NOT bounded to fixture/body/window"** - `apply`'s own
   detailed lines were still gated solely by the global `GroundContactDiagnostics`
   flag; `DiagnosticWindow` only drove the probe's SEPARATE expected-body
   marker. Added `DiagnosticContext` (new, `src/main/.../DiagnosticContext.java`):
   a small static holder - `arm(bodyIds, startGameTime, endGameTime)`/
   `disarm()`, with a PURE `matches(bodyId, gameTime)` (no static state,
   directly testable) and a diagnostic-only `isActiveFor` accessor.
   `BuoyantForces.apply`'s invocation marker and per-float line are now both
   gated by `GroundContactDiagnostics.ENABLED && contextActive` - genuinely
   bounded to the armed body/window, not merely the blanket flag. The probe
   (`BuoyantHitchProbe`) arms it ONE TICK BEFORE the intended window starts
   (`ticksInPhase == SHORT_PHASE_TICKS - 6`, i.e. 54) with an explicit,
   continuous `gameTime` range (not either phase's own relative counter) -
   so it is armed before the NEXT tick's own entity-ticking force hook runs
   (confirmed ordering: Sable's native step → entity ticking/force hook →
   `ServerTickEvent.Post`, where this probe code itself runs, always AFTER
   the SAME tick's own force hook) - then re-arms with `towing2` added at
   `TOWING_REPLACEMENT`'s own transition tick, before towing2's own first
   force hook (which can't happen before the NEXT Minecraft tick, since it
   was only just spawned in this same block). The probe's existing separate
   expected-body marker is retained unchanged, as instructed.
2. **"`visitedCount` currently reports 25 even at early water return"** -
   confirmed real: the prior `WaterScanResult` always reported the scan's
   MAXIMUM capacity, computed from bounds alone, regardless of where (or
   whether) a match actually occurred. Fixed by extracting the loop itself
   behind a pure `FluidHeightLookup` callback seam
   (`scanForWater(int fromYFloor, FluidHeightLookup)`) that counts the REAL
   number of lookups performed before returning - early return preserved
   exactly, `iterations` now reflects the true per-call count. The old
   bounds-only function is kept, renamed `maxPossibleIterations` to say
   plainly what it actually is (the capacity, not the real count) - per
   54494's own "record actual iterations or rename maximum capacity
   accurately." The production `scanForWater(ServerLevel, ...)` overload now
   just supplies the one real `getFluidState` call as that lookup - still a
   single query per visited Y, unchanged behavior.
3. **"Restore explicit floorX/floorZ and full-precision individual...
   components (JOML formatted strings lose boundary precision)"** - both
   fixed: `queryFloorX`/`queryFloorZ` restored as explicit fields (dropped
   in the prior round's rewrite); `pose.position()`/`q`/`point` are now
   logged as individual `String.valueOf(double)` components
   (`consumedPosePositionX/Y/Z`, `consumedPoseOrientationX/Y/Z/W`,
   `pointX/Y/Z`) rather than via JOML's own `toString()`, which truncates to
   limited significant digits/scientific notation - the exact lossy
   formatting this whole investigation has already had to route around
   repeatedly when reading OTHER log lines.
4. **"Existing predicate/arithmetic tests do not establish actual apply
   gating, reset-spanning window or original-query count/result
   preservation... correct 54491 coverage claims"** - retracting 54491's
   implicit claim that the existing tests covered gating/window/query-count
   preservation; they covered only the window PREDICATE and the bounds
   ARITHMETIC in isolation, not whether `apply` actually consults either, or
   whether early-return/iteration-counting behaves correctly. Added, this
   round: `DiagnosticContextTest` (5 tests - matches-in-range, outside-
   range-either-side, body-not-in-set, nothing-armed, re-arm-replaces-the-
   window exactly as the phase-reset re-arm above relies on) and extended
   `BuoyantForcesWaterScanBoundsTest` with 4 new callback-seam tests
   (early-match stops at `iterations=1`; a late match at the 10th lookup
   reports `iterations=10`, not 25; no-match visits the full
   `maxPossibleIterations` and reports `NEGATIVE_INFINITY`; the lookup is
   never called outside `[bottomY, fromYFloor]`, enforced by a fake lookup
   that throws if it is). All pure, no `ServerLevel`.

Test receipt: `gradle test --tests io.github.brooswitminecraft.div.DiagnosticWindowTest --tests io.github.brooswitminecraft.div.DiagnosticContextTest --tests io.github.brooswitminecraft.div.BuoyantForcesWaterScanBoundsTest` under the existing exclusive slot - BUILD SUCCESSFUL; XML results: `DiagnosticWindowTest` tests="4" failures="0" errors="0"; `DiagnosticContextTest` tests="5" failures="0" errors="0"; `BuoyantForcesWaterScanBoundsTest` tests="7" failures="0" errors="0" (16 total this round, 23 cumulative with the first round's 7).

Disclosed limit, unchanged from the prior round: `DiagnosticContext`'s own
arm/disarm static-state wiring into the probe, and `scanForWater`'s real
`getFluidState` call, are still not exercised by these pure suites - that
needs a real `ServerLevel`/dedicated server, outside this round's "no
native run" constraint. Named explicitly, not assumed covered.

### Disposition

Diff + 2 new/extended pure test classes, committed. No native run performed.
No gameplay/fixture physics/timing/pass-fail change - the probe's own new
lines remain read-only logging/context-arming, not a behavior change.
Native gate remains CLOSED. No causal claim made or acceptance weakened.

## Third correction round per 54497: receipt correction, clock bug found and fixed, remaining lifecycle items, packet start

Per 54497's independent review of 465d0e7:

### Correcting my own 54496 coverage/receipt claims

- **"23 cumulative" was wrong** - John independently found the same 16 tests
  (`DiagnosticWindowTest`=4, `DiagnosticContextTest`=5,
  `BuoyantForcesWaterScanBoundsTest`=7) in the retained run; my own "23
  cumulative" figure double-counted the FIRST round's own (now-replaced)
  3-test version of `BuoyantForcesWaterScanBoundsTest` as if it still existed
  alongside the rewritten 7-test version - it does not; that file was
  rewritten, not extended. Retracting "23" - 16 is the actual, current,
  distinct test count.
- **No receipt existed in `/tmp/div-gradle-slot.owner` for that run** -
  confirmed: this investigation's own established cross-ticket convention
  (visible throughout that file's own history, used by M524/M526/M531/M532/
  M537/M544/etc.) is to append `CLAIMED by <label> pid=<pid> at <ts>` /
  `RESULT=...` / `RELEASED by <label> at <ts>` lines to that shared file
  around every exclusive-slot use - my own `flock` invocations this session
  used the lock correctly but never wrote those lines, so no entry exists
  for the 06:39:36 run despite it genuinely having happened (evidenced only
  by the XML files' own timestamps and the commit they're paired with). Not
  re-run solely to manufacture a missing receipt, per 54497's own
  instruction - reported as a real gap in MY OWN process this session, now
  fixed going forward: this round's own compile/test runs ARE recorded in
  `/tmp/div-gradle-slot.owner` (`M545-compile22`, `M545-test10`, both
  `RESULT=COMPLETE rc=0`), the first such entries this session actually
  follows the established convention.

### Real clock bug found and fixed: `AWrapperWorld.getTime()` is NOT `ServerLevel.getGameTime()`

Per 54497's instruction to verify rather than assert equivalence: decompiled
the pinned MTS jar's own `mcinterface1211.WrapperWorld.class` (same jar/hash
`1d2f7512...` already resolved earlier in this investigation) via `cfr`:

```java
public long getTime() {
    return this.world.getDayTime() % 24000L;
}
```

This is Minecraft's DAY-TIME clock (wraps every 24000 ticks, day/night cycle,
independently affectable by sleep/`/time set`/gamerules) - a DIFFERENT clock
from `ServerLevel.getGameTime()` (monotonic, never wraps), the one
`BuoyantForces.apply` actually consults. My own `DiagnosticContext.arm(...)`
calls in the probe were using `towed.world.getTime()` (this wrong clock) -
for this specific short fixture run (a few hundred ticks, well under 24000,
no sleep/time-set), the two values happen to coincide numerically, so this
did not change any RESULT in practice, but the mechanism itself was wrong
and would silently break on a longer run or one that slept/changed time.
Fixed: `towingUnavailable` now takes `ServerLevel level` (plumbed from its
caller, `maybeRun`, which already has it) and every `DiagnosticContext.arm`/
marker call in both phases now uses `level.getGameTime()` directly - the
SAME clock `apply` consults. Also fixed the pre-existing `sampleGameTime`
diagnostic line (54237-era) for the same reason, now on the right clock too.

### Re-arm moved before `connectTrailer`

Per 54497: the `towing2` re-arm previously ran AFTER `connectTrailer` -
moved it to immediately after `spawnHull` returns (as soon as `towing2`'s
own UUID exists), strictly BEFORE `connectTrailer` is called, removing the
need to trace whether `connectTrailer`'s own pinned call path could trigger
an `apply()` call for `towing2` before the context covered it. Diagnostic-
only reordering - the real spawn/connect/gameplay ordering is unchanged.

### `DiagnosticContext.disarm()` now has a real production caller

Added at the SAME terminal guard point the existing `chunkGridUnforced`
one-time release already uses (`phase == DONE || phase == FAILED`, guarded
by a new `diagnosticContextDisarmed` flag so it fires exactly once,
covering the ordinary-completion AND the `catch (RuntimeException)`→FAILED
abort path identically) - diagnostic-only, no physics/world cleanup change.

### Documented window spec (54497's request to state explicitly)

- `TOWING_UNAVAILABLE` ticksInPhase==54: arms `{towed}`, window
  `[gameTime+1, gameTime+15]`.
- `TOWING_REPLACEMENT` ticksInPhase==1 (immediately after `towing2` spawns,
  before `connectTrailer`): re-arms `{towed, towing2}`, window
  `[gameTime, gameTime+15]`.
- `DONE`/`FAILED`: disarms unconditionally (once).

Verified via `gradle compileJava compileTestJava` then `gradle test` for the
three pure suites, BOTH recorded in `/tmp/div-gradle-slot.owner` this time -
`M545-compile22`/`M545-test10`, both `RESULT=COMPLETE rc=0`. XML unchanged
at 16/16 passing (`DiagnosticWindowTest` 4, `DiagnosticContextTest` 5,
`BuoyantForcesWaterScanBoundsTest` 7) after these fixes - no regression.

### Isolated packet - real artifact identity, honest scope limit

Per 54497's request for source/artifact/config hashes and a retained-
world/lock-lifecycle plan - provided what is genuinely available without
preparing to execute a run (native execution remains CLOSED; this is NOT a
run authorization request):

- **Source identity**: this branch's HEAD is `465d0e76da6c08be8351307213bb94f52e08bfc1`
  (full commit hash) at the time of this review; this round's own changes
  land in the next commit alongside this entry.
- **Pinned dependency identity** (both already resolved/verified earlier in
  this investigation, re-confirmed by direct `sha256sum` against the local
  Gradle cache, which is itself content-addressed by hash in its own path):
  MTS `minecraft-transport-simulator-286703-7926606.jar` =
  `1d2f7512604636265703b29fdf13d7f8ef2323694b6435f4392973da6e78d251`; Sable
  `sable-neoforge-1.21.1-2.0.5.jar` =
  `c8710f85bc780bbf523e6726ceaaf76dd0b8c61479db497b382c6bb34317e7ab`.
- **Lock/launch-lifecycle template**: NOT designing a new one - the owner
  file's own history already contains a proven, orderly template for this
  exact fixture (`M545-authrun-probe2`: "RESULT=COMPLETE (FAILED at
  TOWING_REPLACEMENT as expected, orderly stdin stop, clean shutdown
  confirmed: Stopping server / All dimensions are saved / BUILD
  SUCCESSFUL)"); reusing that same template, not inventing a new launch/
  shutdown mechanism, is the plan for any future authorized run.
- **Retained-world plan**: unchanged from every prior entry - this round
  touches NO world, creates NO new run, and does not read or modify any
  retained evidence directory under `/home/booswrit/div-evidence/`.
- **What's still outstanding, stated plainly rather than fabricated**: a
  genuine dry-run rehearsal (confirming the lock/launch/shutdown sequence
  end-to-end against the NEW logging/context code specifically, before any
  real diagnostic run) has not been performed and is not proposed in this
  entry - that is the natural next packet element once/if a run is
  authorized, not something to simulate on paper here.

### Disposition

Diff + receipt correction + real pinned-source finding, committed. No
native run performed or requested. No gameplay/fixture physics/timing/
pass-fail change. Native gate remains CLOSED. No causal claim made or
acceptance criteria weakened.

## Completing the isolated setup packet per 54505 (preparation only, no native run)

Correcting the prior entry's own packet draft: it named the PRE-CHANGE
commit `465d0e7` as "source HEAD" even though that same entry's own work
landed as `a0ddcfa` - stale by one commit at the moment of writing. Current
HEAD after this round's own disarm-ordering fix (below) is
`738b09132f06b41172e0fda586dcc6d7b3306a26`.

### Lifecycle fix: `disarm()` is now reliable regardless of `unforceChunkGrid`

Per 54505: `disarm()` previously ran AFTER `unforceChunkGrid`, with nothing
guarding it - an exception from that existing call (unchanged here) would
have skipped diagnostic teardown entirely. Reordered so `disarm()` runs
FIRST, unconditionally reliable at the terminal point regardless of whether
`unforceChunkGrid` later throws - `unforceChunkGrid`'s own cleanup/physics
semantics and error handling are untouched. Re-verified: 16/16 tests still
pass (`DiagnosticWindowTest` 4, `DiagnosticContextTest` 5,
`BuoyantForcesWaterScanBoundsTest` 7), recorded in `/tmp/div-gradle-slot.owner`
as `M545-compile24`/`M545-test11`, both `RESULT=COMPLETE rc=0` (an earlier
attempt, `M545-compile23`, correctly recorded `RESULT=FAILED rc=1` when
another ticket - M548 - was legitimately holding the exclusive lock for its
own `clean build`; waited for it to release rather than forcing or
fabricating a result).

### Isolated packet - prepared, not executed

Per 54505's explicit list - "preparation/build-manifest work within existing
serialized scope is authorized, not native execution." Created
`/home/booswrit/div-evidence/MINECRAFT-545-547/M545-packet-738b091/` (new,
distinctly-named - NOT called "trial"/"rundir" since no run happens) and, all
under the existing exclusive lock with proper owner-file receipts
(`M545-packet-prep`, `RESULT=COMPLETE rc=0`):

- Ran `gradle prepareServerRun createServerLaunchScript` with
  `DIV_ISOLATED_RUN_DIR=<packet dir>/rundir DIV_BUOYANT_HITCH_PROBE=true` -
  regenerating `build/moddev/runServer.sh` and its companion classpath/VM-
  arg/program-arg files AGAINST THE CURRENT COMMITTED SOURCE (738b091), not
  reusing trial1/trial2's own stale copies (which predate every diagnostic-
  logging change this session made).
- Independently re-read the regenerated `runServer.sh`'s own literal `cd`
  argument immediately after generation: `cd
  '/home/booswrit/div-evidence/MINECRAFT-545-547/M545-packet-738b091/rundir'`
  - byte-for-byte the intended path, confirmed before copying anything.
- Copied all four generated files into the packet dir and hashed them
  (`generated-launch-artifacts.sha256`). `serverRunClasspath.txt`/
  `serverRunVmArgs.txt`/`serverRunProgramArgs.txt` hash BYTE-IDENTICAL to
  trial1's own retained copies; only `runServer.sh` differs (the baked-in
  `cd` path, expected).
- **Live-readback effective classpath hashes** (`live-readback-effective-
  classpath-hashes.sha256`): parsed the real classpath line and hashed every
  one of its 100 entries' ACTUAL current bytes (individual files for jars;
  every contained `.class`/resource file, matching the SAME per-file
  convention trial1's own retained copy already uses, for the two directory
  entries) - 252 lines, all 100 classpath entries resolved to a real file/
  directory, zero `MISSING`. Diffed directly against trial1's own retained
  copy of the same file - a REAL, measured baseline comparison, not asserted:
  - `BuoyantForces.class`/`BuoyantHitchProbe.class` changed hash - EXPECTED,
    the files actually edited this investigation.
  - New classes present now but absent from trial1's own capture:
    `DiagnosticWindow.class`, `DiagnosticContext.class`,
    `BuoyantForces$FluidHeightLookup.class`, `BuoyantForces$WaterScanResult.class`
    (expected, added this session) - and also `BoxInertia.class`/
    `ShapeAwareBoxPhysicsObject(.class/$1.class)`, which this investigation's
    own earlier history says were committed BEFORE trial1/trial2 ran
    (`b1264a7`) - their absence from trial1's own retained classpath capture
    is a genuine inconsistency in that PAST evidence, not explained or
    further chased here; disclosed, not papered over.
  - `BuoyantBody.class` ALSO shows a changed hash, despite `BuoyantBody.java`
    not being edited in any of this session's three diagnostic-logging
    rounds - NOT asserted as proof of an unnoticed source change; ordinary
    javac/incremental-compile output is not always byte-reproducible across
    separate invocations even for unchanged source (debug-info/constant-
    pool-ordering artifacts). Stated as an open, unexplained difference, not
    a finding.
  - One dependency jar, `j2objc-annotations-2.8.jar`, appears in the CURRENT
    classpath but is entirely ABSENT from trial1's own retained capture -
    the dependency SET itself has drifted slightly since trial1 was
    captured (plausibly other tickets' own merged work on this shared repo,
    visible in `/tmp/div-gradle-slot.owner`'s own interleaved history) - a
    real, disclosed difference, not caused by this investigation's own
    changes and not further chased here.
- **Input manifest** (`isolated-input-manifest.sha256`): hashed the current
  seed config/eula/server.properties files from the shared `run/` directory.
  Nine of ten files hash IDENTICAL to trial1's own retained manifest;
  `server.properties` differs - expected, since trial1's own copy had
  `level-seed=545547` fixed for that specific matched comparison, while the
  currently-shared `run/server.properties` reflects whatever is presently
  active there (other tickets' own use of the same shared directory) - NOT
  yet overridden for any specific future run's own seed requirement; stated
  as an open input, not fixed by this entry.
- **Launch/shutdown template**: copied trial1's own already-proven
  `launch-wrapper.sh` verbatim, substituting only the directory name/owner
  tag (confirmed via direct `diff` - those are the ONLY two changed lines) -
  reusing the proven lock-claim/FIFO-stop/orderly-shutdown/owner-receipt
  mechanism exactly, not inventing a new one. NOT executed - `rundir/` is
  confirmed empty (`find` returns zero entries) and no process under this
  packet's own owner tag was ever launched.

### Disposition

No native run performed or requested - every step above is generation/
hashing/copying of already-existing proven artifacts and templates, done
under the existing exclusive slot with proper receipts. Native gate remains
CLOSED pending independent review of this packet. No gameplay/fixture
physics change. Preserving all original worlds/evidence (trial1/trial2/the
original non-crash run are untouched - only read from, never modified) and
lock identity.

## Correcting packet claims per 54518; actually populating the isolated rundir

Per 54518's independent review of 299764b/the packet dir - several real
corrections, plus completing what the packet's own prior entry left
incomplete:

### The packet's own rundir was actually EMPTY - now populated

Confirmed: the prior entry only hashed the SHARED `run/` directory's own
config/eula/server.properties - it never actually copied them into the
packet's own isolated `rundir/`. Fixed: copied all ten files into
`M545-packet-738b091/rundir/` (its own `config/` subdirectory plus
`eula.txt`/`server.properties` at its root) - a real, separate, isolated
copy, not a reference to the shared files. Set `level-seed=545547` in THIS
copy only (confirmed via diff: exactly one line differs from the shared
`run/server.properties`, `level-seed=` → `level-seed=545547`; `level-name=world`
confirmed present and unchanged) - the shared `run/server.properties` itself
was never touched. Regenerated `isolated-input-manifest.sha256` against
these new isolated copies: all ten files now hash IDENTICAL to trial1's own
retained manifest, including `server.properties` (previously mismatched
when comparing against the shared file) - confirms this new isolated
config set is a genuine, verified match to the proven trial baseline.

### Retracted: "BuoyantBody.class change may be javac nondeterminism"

Wrong - verified directly: `git merge-base --is-ancestor b1264a7 HEAD`
confirms `b1264a7` (the `ShapeAwareBoxPhysicsObject` substitution commit,
committed `2026-11-17T01:07:47Z`, confirmed AFTER Trial 2 ended
`00:42:28Z`) IS an ancestor of current HEAD; `git merge-base --is-ancestor
b1264a7 11f6b51` confirms it is NOT an ancestor of `11f6b51` (the commit
trial1's own classpath/baseline was built from). `git diff 11f6b51..HEAD --
.../BuoyantBody.java` shows, explicitly, `new BoxPhysicsObject(...)` →
`new ShapeAwareBoxPhysicsObject(...)` - a REAL source change, already
documented elsewhere in this file, that simply predates trial1/trial2 by
less than it predates HEAD. The changed `BuoyantBody.class` hash reflects
this real, already-known, already-labeled-inert (Java-metadata-only, per
its own commit's doc) change - NOT unexplained javac non-determinism.
Retracting that guess outright. No retained standalone `BuoyantBody.class`
exists in trial1's own packet to diff bytecode-for-bytecode against, so
this is a SOURCE-level comparison (via `git diff`), not a bytecode one -
stated precisely, not implied to be more than it is. Separating this
intended Java-metadata constructor change clearly from this investigation's
own diagnostic-logging changes (`BuoyantForces`/`BuoyantHitchProbe`/the new
`DiagnosticWindow`/`DiagnosticContext` classes) - different changes, from
different, already-dated points in this investigation's history.

### Retracted: "j2objc dependency set has drifted since trial1"

Wrong - verified directly: trial1's OWN retained `serverRunClasspath.txt`
and the current one are BYTE-IDENTICAL (same SHA-256,
`317bc204cd86ae752bf135f5e5dd8dd1544173d953c084fe33ad1f094c41e914`,
confirmed independently both by my own earlier entry and again here), and
BOTH literally contain the `com.google.j2objc/j2objc-annotations/2.8` cache
path - the classpath itself was ALWAYS the same; nothing about the
dependency set changed. What actually differs: trial1's own retained
`live-readback-effective-classpath-hashes.sha256` is simply MISSING this
one jar's hash line (244 lines captured against a 99-entry classpath,
every other entry present) - a one-off capture gap in that EARLIER
evidence-generation step, not a dependency difference now. My own current
`live-readback-effective-classpath-hashes.sha256` DOES include it
(`f02a95fa1a5e95edb3ed859fd0fb7df709d121a35290eff8b74dce2ab7f4d6ed`).
Retracting "dependency set has drifted" outright - no cause for the
original gap is evident from existing records, and none is invented here;
the dependency set itself is confirmed unchanged.

### Standing precondition restated, not newly satisfied

`build/moddev/runServer.sh` and its companion args files are SHARED,
mutable worktree files (any other ticket's own Gradle task can regenerate
them in place) - the copies already archived in this packet are evidence
of what was true AT GENERATION TIME, not a standing guarantee. Restating
trial1's own precondition exactly: before any future actual release, the
live files must be re-read and re-verified byte-for-byte under the
exclusive lock immediately before that specific launch - not assumed from
this entry alone.

### Disposition

No native run performed. Native gate remains CLOSED pending independent
review. Original worlds/evidence (trial1/trial2/the non-crash reference
run) untouched throughout - only read from. The shared `run/` directory's
own `server.properties` was read but never modified; only the NEW isolated
copy under the packet's own `rundir/` was edited.

## Original baseline-manifest located and read-only-verified per 54522

Per 54522's independent confirmation that all 10 isolated input hashes, all
252 runtime/class hashes, and the live launcher/args all verify correctly
against the packet - the one remaining item: "exact original shared config/
world baseline-manifest paths and current comparison receipt (prior
records mention 8 config/2 leveldat, not located in packet/trial top-level)."

Located: `baseline-original-run-config.sha256` (8 config file hashes) and
`baseline-original-run-world-level-dat.sha256` (2 hashes - `level.dat` and
`level.dat_old`), already retained from this investigation's own earlier
work, in the ORIGINAL non-crash run's own evidence directory
(`M545-isolated-rundir-20261116T224049Z/`) - not at this new packet's own
top level, exactly as 54522 noted. A real, pre-existing retained manifest
exists; no limit to disclose here.

Compared, read-only, no mutation of anything original:

- This new packet's own 8 config-file hashes (already in its
  `isolated-input-manifest.sha256`) against that retained
  `baseline-original-run-config.sha256`: `diff` reports ZERO differences -
  byte-identical, confirmed directly, not assumed.
- The CURRENTLY-SHARED `run/world/level.dat`/`level.dat_old` (the live
  worktree's own shared world, still present) against that SAME retained
  baseline's own 2 hashes: BYTE-IDENTICAL on both files
  (`3f08872f...`/`b1c30de5...`) - confirms the shared `run/world` has NOT
  been mutated by anything (this investigation's own work, or any other
  concurrent ticket's use of this shared worktree) since that baseline was
  captured. A genuine guard check, performed read-only via `sha256sum`
  only - no world was loaded, started, or touched.
- Copied the retained config baseline and a fresh read-only hash of the
  current shared `level.dat`/`level.dat_old` into the packet directory
  itself (`baseline-original-run-config.sha256`,
  `current-shared-run-world-level-dat.sha256`) so this comparison's own
  receipt travels with the rest of the packet.

Per 54522's own closing instruction: keeping the dependency/class-chronology
correction (the prior entry) and the Java-metadata constructor difference
(a real, separate, already-documented source change) explicit and distinct
from each other - neither is restated as, or conflated with, a single
"identical physics artifact" claim.

### Disposition

No native run performed. No build/rehearsal needed beyond what's already
done. Native gate remains CLOSED - per 54522, the parent must explicitly
release the single bounded run after this final baseline readback; that
release decision is not this entry's to make. All original worlds/evidence,
including the currently-shared `run/world`, confirmed byte-identical to
their own prior baseline and untouched by this entire packet-preparation
exercise.

## EXACTLY ONE authorized bounded diagnostic run executed per 54527

Per 54527: the coordinator released the one-run hold under the parent's
explicit conditional authority, after an independent final verification
(08:41:17-18 UTC, exclusive lock acquired read-only then released, all
checks PASS). Instructed: re-acquire the slot and revalidate everything
inside that ownership to close the check-to-launch gap, then execute
EXACTLY ONE bounded run via the reviewed `M545-packet-738b091/launch-
wrapper.sh` - no improvisation on mismatch, no rerun/substitute regardless
of outcome. Diagnostic evidence only, not causal-fix acceptance.

### Pre-launch revalidation (own lock acquisition, then released)

Owner-file receipt: `M545-prelaunch-revalidate`, `RESULT=REVALIDATE_PASS`,
claimed and released within the same second (`08:42:42Z`). All checks
passed, zero mismatch:
- HEAD = `6589b1cfea39f2c8000e7bb2de3f80f6eec06ce7` (matches expected)
- Live `build/moddev/{runServer.sh,serverRunClasspath.txt,
  serverRunVmArgs.txt,serverRunProgramArgs.txt}` byte-match the packet's
  own archived copies (all 4, individually re-hashed and compared)
- Wrapper SHA `fa70ecac6a2b65700e977364467c3a21bee3970ad7bd5dfd64049404e311d57d`
  matches expected (unchanged)
- All 10 isolated input hashes still match the packet's own manifest;
  `level-seed=545547` confirmed present in the isolated copy
- Fresh world absent from the packet's own `rundir` (0 entries) before launch
- The original 8-config/2-leveldat baseline still matches (shared `run/`
  untouched since the last check)

### Launch

Executed `M545-packet-738b091/launch-wrapper.sh` (unmodified, same wrapper
reviewed throughout this packet's preparation) targeting its own `rundir`.
Owner-file receipt: `M545-packet-738b091`, claimed `08:42:55Z`, released
`08:44:15Z`, `rc=0` (the wrapper's own exit code reflects its own orderly
completion, not the probe's pass/fail - see outcome below). Orderly
shutdown confirmed in the log: `Stopping server` /
`ThreadedAnvilChunkStorage: All dimensions are saved`.

### Measured outcome

```
[DIV buoyantHitchProbe] FAILED - left bounded area tag=towingReplacement/towing2
  phase=TOWING_REPLACEMENT tick=7 horizontal=212.31392326731333
  vertical=50.96779650747102
  position=[X:17512.840965724627, Y:54.03220349252898, Z:17798.043006134918]
```

The SAME failure CATEGORY as Trial 1/Trial 2 ("left bounded area",
`TOWING_REPLACEMENT`) reproduces again under the same `level-seed=545547`
- but NOT byte-identical to either: Trial 1 failed at `tick=8` with
`horizontal=65.44565659568023 vertical=50.060278582992964`; this run failed
one tick earlier (`tick=7`) with a much larger horizontal excursion
(`212.31...`). Stated plainly: this run and Trial 1/Trial 2 are NOT
perfectly reproducible byte-for-byte against each other, despite the same
seed - real source changes landed between Trial 1/2's own commit and this
run's HEAD (the `ShapeAwareBoxPhysicsObject` substitution, `b1264a7`,
confirmed to postdate both trials; plus this entire investigation's own
diagnostic-logging additions). This does NOT, by itself, prove either
change affected the outcome - multiple confounding changes landed in the
same window; not disentangled by this one run alone.

### Window/expected-marker coverage

8 `expectedBodyThisTick=true` lines fired (the new `DiagnosticContext`-
backed marker from this investigation's own instrumentation), confirming
the armed window was active and covering real ticks during this run;
exactly 1 `diagnosticContextDisarmed=true` line, firing at the `FAILED`
terminal state as designed. 1137 `buoyantForcesDiagnostic` lines total; 24
lines carry the new `waterSurfaceResult=`/`scanFound=` per-float detail
added in this investigation's second correction round - available for
future reading, not analyzed further in this entry (diagnostic evidence
only, not a causal claim).

### After-run original-state guard

Re-compared, read-only, after the run completed: the original 8-config/2-
leveldat baseline (shared `run/` directory) STILL matches exactly - zero
difference before vs. after this run. Trial 1/Trial 2's own retained
`rundir`s were not touched (confirmed via their own log files'
unchanged modification times). The new world this run produced is
retained, unconditionally, under
`M545-packet-738b091/rundir/world/` (~10MB) - the wrapper's own stated
policy, honored, no delete branch exists for it.

### Disposition

Exactly one bounded diagnostic run executed and completed, as authorized -
no rerun, no substitute, regardless of this outcome. This is diagnostic
evidence only: it does NOT establish a causal fix, does NOT change
acceptance criteria, and does NOT by itself explain why the magnitude/tick
differs from Trial 1/2 (multiple confounding source changes exist between
them, not isolated here). Known observational/Java-metadata constructor
differences (the `ShapeAwareBoxPhysicsObject` substitution) remain
explicit and separate from this run's own measured outcome - not
conflated. Native gate: this one authorized run is now COMPLETE; no
further native execution is authorized by this entry. No peer signals,
permission, or dependency changes made. No deployment/Zippy action.

## Read-only analysis of the authorized run per 54532; a real, earlier divergence point found

Per 54532's independent raw verification (confirmed matching: wrapper
claim/release times, Java pid, FAILED line, stop/dimensions-saved lines,
original baseline). Grant CONSUMED - no further native run in this entry.

### Honest correction: the check-to-launch gap was NOT continuous lock ownership

54532 is right: the pre-launch revalidation's own lock was claimed and
released at `08:42:42Z`; the wrapper's own lock acquisition happened at
`08:42:55Z` - an actual 13-second window where NO process held the lock.
The prior entry did not claim continuous ownership outright, but should
have stated this gap explicitly rather than let "revalidate then launch"
read as seamless. Stated plainly now: there is no proof any byte changed
in that 13-second gap, and per 54532's own instruction, no rerun is made
to try to close or "repair" this - the gap is preserved as a true
limitation of this specific run's own provenance, not papered over.

### Marker/row counts confirmed independently

24 per-float query rows, 12 `applyInvoked=true` rows, 8 `expectedBodyThisTick`
rows - matching 54532's own independent count exactly.

### Root cause of the 258-263 gap: a genuine call-path absence, not a gating artifact

Checked precisely: the `expectedBodyThisTick` markers correctly fired for
all 6 intended ticks (`gameTime=258-263`, `TOWING_UNAVAILABLE` ticks 55-60)
plus the `TOWING_REPLACEMENT` transition tick (`gameTime=264`, both
bodies) - the window/body-restriction mechanism itself worked exactly as
designed. But the `applyInvoked=true` invocation marker - which fires
whenever `BuoyantForces.apply()` is actually called for `towed`, REGARDLESS
of the per-float detail gating - ALSO only begins at `gameTime=264`, with
NONE in `258-263`. This confirms `BuoyantForces.apply()` itself is simply
never invoked for `towed` during that specific window in this run - not a
diagnostics-disabled or context-mismatch artifact (both were independently
confirmed armed and matching). Per 54532's own instruction: this does NOT
mean "55-60 was captured" and does NOT support inferring water absence
from it - it is a confirmed, different kind of gap (no call at all), stated
as such, not reinterpreted as evidence of anything about water.

### New finding: the earliest matched-hook divergence is NOT at tick ~53-60

Per 54532's own flag - `towed`'s velocity at `TOWING_UNAVAILABLE` tick=60
in THIS run is near-zero (`linearVelocity≈(-4.8E-4,-2.8E-4,3.1E-4)`),
completely unlike Trial 1/2's own established "frozen, continuing free-
fall" signature (`≈(0.075,-5.04,-2.02)`) at the identical tick. Read the
full tick-by-tick sequence (already retained, no new run) to find exactly
where this happens: ticks 1-36 show a normal, moderate, non-trivial
velocity (broadly consistent in KIND with free motion, though not
numerically identical to Trial 1/2's own values even this early - expected,
given this run's own earlier DETACH/ATTACH-phase history necessarily
differs somewhat given the source changes made since Trial 1/2). Between
tick 37 and tick 40, the velocity SMOOTHLY DECAYS toward zero and then
STAYS at noise-level (10⁻⁴-10⁻⁸) for the rest of the window through tick 60
- towed effectively comes to REST, rather than continuing in unconstrained
free fall as it did (by established signature) in both Trial 1 and Trial
2. This is the actual earliest point this run's own trajectory departs
from the prior established pattern - roughly 15-20 ticks earlier than
where this investigation had previously localized the divergence (tick 60
itself). Found entirely from already-retained velocity logs - no new
instrumentation or run was needed to see THIS much.

### The mechanism at ticks ~37-40 remains unconfirmed - a real, disclosed gap

Checked directly: zero `buoyantForcesDiagnostic` lines (per-float or
invocation marker) exist for `towed` anywhere between `gameTime≈4`
(the original ATTACH-phase submersion, expected) and `gameTime=264` - the
`DiagnosticContext` window armed by this session's own instrumentation
covers only `258-263`+`264`, nowhere near ticks 37-40 (which, by the same
tick-to-gameTime mapping established earlier this investigation, fall
around `gameTime≈241-244`). This is a direct, real consequence of this
session's own window-narrowing (added specifically per 54494's "bound to
fixture/body/window" instruction) - not a bug, but it means NO per-float
query-level detail (water-found/not-found, fraction, exact point) exists
for the actual settling event, in this run OR in Trial 1/2 (neither had
any such per-float instrumentation at all). The MECHANISM behind towed
coming to rest at tick ~37-40 - re-entering water, a native contact, or
something else - is NOT established by this entry. Not guessed at.

### Retained, not contradicted

Per 54532: the prior native-inertia-consumer conclusion (that
`getMassTracker()`/the `ShapeAwareBoxPhysicsObject` substitution is Java-
metadata-only, never consulted by the actual native body-creation path) is
RETAINED - nothing in this run's own evidence contradicts it; multiple
changed artifacts exist between Trial 1/2 and this run (that substitution,
this session's own diagnostic logging) and are confounders for THIS run's
own tick7-vs-8 crash-magnitude difference, but say nothing new about that
separate, already-established, already-verified conclusion.

### Falsifiable mechanism / justified next read-only step

No further read-only analysis of EXISTING retained logs can resolve the
tick~37-40 mechanism - confirmed, not assumed, that no per-float query
data exists for that window in any retained run. The justified next step,
NOT executed here: a future authorized run with the `DiagnosticContext`
window EXTENDED to cover `TOWING_UNAVAILABLE` ticks ~30-45 (not just
55-60), with an explicit, falsifiable prediction stated in advance - if
`scanFound=true`/`fraction>0` appears for `towed` in that window, that
would support a real re-submersion event explaining the velocity decay; if
`scanFound=false` throughout while the velocity still decays to rest, that
would point to a different (native contact/other) damping mechanism, not
buoyancy. Neither is picked here. No run proposed or executed in this
entry - diagnostic evidence and a justified next-step proposal only.

### Disposition

Read-only analysis of the one already-executed run. No new native run
performed. No speculative physics fix proposed. Native gate: the one-run
grant remains CONSUMED; this entry does not request or imply another.

## Correcting the "apply never invoked" claim; tracing the true earliest observed divergence per 54535/54536

Per 54535/54536: the tick~37-40 settling is NOT the earliest observed
difference - already differing values were found at `TOWING_UNAVAILABLE`
tick=1 (vs. Trial 1/2) and even at `ATTACH` tick=20. Traced further
backward, read-only, no run.

### Retracting "apply() is never invoked" for 258-263

Checked `BuoyantBody.step entry` lines (the `lifecycleDiagnostic` marker,
gated ONLY by the global `GroundContactDiagnostics` flag, not
`DiagnosticContext` - confirmed by direct source re-read of
`BuoyantBody.step`, lines 113-124: it refreshes pose, emits this entry
marker, then calls `BuoyantForces.apply` UNCONDITIONALLY - no sleep branch
in that method) for `towed`: 207 such lines exist, CONTINUOUSLY, across
the entire run. This confirms `BuoyantBody.step()` - and therefore
`BuoyantForces.apply()`, called unconditionally from it - ran EVERY tick,
including `gameTime` 5-263. My prior claim that "apply() itself is never
invoked" for that window is WRONG - retracted outright. What is actually
true: the `applyInvoked=true` DIAGNOSTIC LINE inside `apply()` is gated by
BOTH the global flag AND `DiagnosticContext`, and correctly did not log
outside the armed window - a logging-gate consequence, not a call-path
absence. Per 54535's own instruction, this is NOT yet full proof either -
`VehicleMovementMixin` (lines 22-37) gates the whole mixin call on
`PhysicsBridge.active()`, which has its own eligibility/defer/container/
activation/reconciliation gates; the continuous `step entry` lines are
strong evidence `active()` stayed true and `step()` ran throughout, but
this entry does not claim to have traced `active()`'s own internals for
every tick - stated as a real but incomplete confirmation, not a closed
proof.

### Backward trace: identical at construction, already different by the first logged ATTACH sample

Compared the TWO sides' `createHitchDiagnostic` lines (the earliest
retained per-tick-resolution snapshot after each body's construction,
`gameTime=3`) between this run and Trial 1: BYTE-IDENTICAL to displayed
precision on both the `towing` and `trailer` sides - same cached pose,
same `linearVelocity=(0,0.8829,0)`/`(0,0,0)`, same `hitchAnchorWorld`,
same `sampleGameTime`. The two runs start construction identically.

Per 54536's own finding: by `ATTACH tick=20` - the FIRST logged ATTACH
sample after construction - `anchorDistance` already differs
(`0.0041` this run vs. Trial 1's `0.0024`), and `towingPos` differs in the
4th-5th decimal. Checked the log's own actual granularity: `ATTACH` only
logs every 20 ticks (`tick=20,40,60,...`), confirmed via direct `grep` -
there is NO retained per-tick data between `gameTime=3` and `ATTACH
tick=20` in EITHER run. This is the genuine resolution limit of the
existing retained logs, not a gap left unsearched: the true earliest
point of divergence lies somewhere within ticks 4-20 of `ATTACH`, and
cannot be narrowed further using any already-retained log line in either
run - stated as a real limit, not guessed past.

### Disposition

This narrows "earliest OBSERVED difference" to a bounded window
(construction, `gameTime=3`: identical; first logged sample after,
`ATTACH tick=20`: already different) - not a physical origin, per 54536's
own caution; multiple real, confounding source changes exist between
Trial 1/2 and this run's own HEAD, not disentangled by this comparison
alone. No cause is asserted. No DIV-fixable bug established. No further
native run performed, proposed, or needed to reach this specific boundary
- it is a genuine limit of existing log resolution, not something a
read-only re-analysis could still resolve. If finer resolution is ever
wanted, that is a future instrumentation question (logging every ATTACH
tick, not just every 20th) - not proposed or executed here.

## Major correction per 54539: retracting "continuous step-entry" - confirmed exact-line verification

Per 54539's own exact-line count: "ZERO BuoyantBody.step-entry rows in new
log lines 2005-2071 (UNAVAILABLE 1-60), likewise none in trial1
corresponding 2069-2135." Verified this myself, directly, by line range
(not a file-wide count, which is the exact mistake 54539 caught): **TRUE**
- zero `BuoyantBody.step entry` lines for `towed` exist within lines
2005-2071. My prior entry's "207 lines, continuous across the whole run"
was a real methodological error - I counted ALL step-entry lines
anywhere in the file and wrongly treated that as proof of coverage within
this specific phase, without checking the actual line range. Retracting
"apply()/step() ran every tick including 258-263" outright - it is false
for this specific window, confirmed by exact line numbers.

### What actually happens at the boundary, confirmed by direct correlation

The LAST `BuoyantBody.step entry` (and the last `BuoyantForces.apply`
diagnostic lines, still showing real buoyancy: `submergedFraction=1.0`/
`0.715`) for `towed` is at line 1998, `gameTime=203` - immediately BEFORE
`towing.remove()` is called (`TOWING_UNAVAILABLE pre-remove()`, line 2002).
After that removal, NO step-entry, NO `lifecycleDiagnostic`, NO
`buoyantForcesDiagnostic`, and NO `debugGroundContact` line of any kind
appears for `towed` anywhere in lines 2005-2071 (confirmed by direct
`grep` against that exact range) - DIV's own Java-side code appears to
stop touching `towed` entirely, in lockstep with `towing` being removed.

### A plausible, source-grounded explanation - NOT yet confirmed by a logged decision value

Read `PhysicsBridge.active()`/`decide()`/`deferToNativeHitch()` directly:
`deferToNativeHitch(towed, alreadyActive, towingVehicleActive, unsafeLock)
= towed && !towingVehicleActive && (!alreadyActive || unsafeLock)`. Once
`towing` is removed, `towingHasHitchableBody` (hence `towingVehicleActive`)
becomes false; `towed`'s own `alreadyActive` was true going in - so this
condition is satisfied only if `unsafeLock` is true. If `active()` returns
a `DEFER_TO_NATIVE` decision here, `VehicleMovementMixin.div$move`'s own
`if (!PhysicsBridge.active(vehicle)) return;` would still skip calling
`PhysicsBridge.step`/`BuoyantBody.step` - matching exactly what's observed
- while Sable's OWN native physics step (injected at `ServerLevel.tick()`'s
own HEAD, confirmed elsewhere in this investigation to run independently
of this Java-side gating) continues to move the body regardless, matching
the documented "unjointed trailer stays an unjointed, free-rolling Sable
vehicle" contract this probe's own class doc already names.

**This explanation is plausible and consistent with the source and the
logs, but NOT confirmed by any actually-logged decision value** - searched
directly for the literal text `DEFER_TO_NATIVE` anywhere in this run's log:
zero occurrences. Either that specific enum value is never logged as text
on this path, or a different mechanism is responsible - not distinguished
here. Not asserting more than what's shown.

### What this means for the water-reentry framing

If DIV's own `BuoyantForces.apply()` genuinely never runs for `towed`
during this entire window (strongly suggested, not yet fully proven), then
EVERY prior entry's framing of "does `towed` re-enter water during
`TOWING_UNAVAILABLE`" was never the right question for this phase at all -
Java-side buoyancy is not being applied regardless of water presence,
since the code path that would check it is not reached. Any eventual
"coming to rest" (the tick~37-40 settling noted previously) would need a
PURELY NATIVE explanation (Sable's own sleep/damping, or a native
collision) - not a DIV/Java buoyancy mechanism. This reframes, but does
not resolve, the open question from the prior entry.

### Next justified read-only step, NOT executed

Trace `active()`'s own full body (not yet fully read in this entry) to
confirm whether `DEFER_TO_NATIVE` (or some other decision) is actually
reached for `towed` once `towing` is removed, and whether that decision's
own text is logged anywhere else (a different format than searched) or
simply isn't logged at all on this path - a source-reading task, not a
new run. No native execution proposed.

### Disposition

Retraction only - no new native run. No DIV-fixable bug established. No
causal mechanism confirmed. Native gate: the one-run grant remains
CONSUMED; nothing in this entry requests or implies another.

## Retracting the DEFER_TO_NATIVE hypothesis per 54542 - mechanism remains genuinely unexplained

Per 54542: the proposed `DEFER_TO_NATIVE` explanation is wrong, confirmed
by evidence I should have checked before proposing it.

### Both parts of the hypothesis are ruled out

1. **The predicate itself evaluates false for this exact case.**
   `unsafeLock` is itself computed FROM `towingHasHitchableBody` via a
   short-circuited AND (confirmed by re-reading the surrounding source,
   line ~666) - when `towingHasHitchableBody=false` (true here, since
   `towing` was removed), `unsafeLock` is ALSO `false`, not independently
   settable. So `deferToNativeHitch(towed=true, alreadyActive=true,
   towingVehicleActive=false, unsafeLock=false)` = `true && true &&
   (false||false)` = **FALSE** - NOT true as I claimed. This exact case is
   already covered by an existing, passing unit test:
   `PhysicsBridgeClassificationTest.java` line 84:
   `assertFalse(PhysicsBridge.deferToNativeHitch(true, true, false,
   false))`. I should have grepped for and read this existing test before
   proposing the hypothesis from source-reading alone - a real process
   failure, not just a reasoning error.
2. **Even if DEFER_TO_NATIVE had fired, the consequence contradicts what's
   observed anyway.** `deferToNativeHitch`'s own doc (re-read directly)
   states explicitly: "An ALREADY-active trailer... must never be
   deferred/torn down just because its towing vehicle's body has since
   gone away - it stays an unjointed, free-rolling Sable vehicle instead."
   `decide()` (lines 808-813) returns `ACTIVATE` here, not
   `DEFER_TO_NATIVE` - by explicit design, not merely by the predicate's
   own arithmetic. And had `DEFER_TO_NATIVE` somehow still occurred,
   `active()`'s own `DEFER_TO_NATIVE` branch calls `deactivate(v)`, which
   REMOVES the registry entry and the native body (`old.remove()`) - not
   "skips Java forces while the native box keeps existing independently"
   as I assumed. The retained logs show `towed`'s own native velocity
   genuinely changing tick-to-tick throughout this window (read via
   `PhysicsBridge.debugReadLinearVelocity`, which goes through the SAME
   registry lookup) - directly inconsistent with the body having been
   deactivated/removed.

### The actual mechanism remains genuinely unexplained - not guessed a third time

By the documented/tested design, `towed` should be `ACTIVATE`d throughout
this window, meaning `BuoyantBody.step()` (and its own unconditional,
globally-gated entry log line) SHOULD run every tick. It does not -
confirmed zero such lines in the exact line range, independently
re-verified twice now. This is a genuine, currently unexplained
contradiction between the documented/tested Java-side decision logic and
what the retained log shows. Not proposing a third unverified source-level
guess - per 54542's own instruction, the next step is to trace the actual
PINNED MTS CALLER upstream of `moveVehicle` itself (third-party code, not
DIV's own mixin/active logic) to check whether MTS's own update loop
might simply not be calling `moveVehicle`/`performGroundOperations` for
`towed` during this specific window, for a reason entirely outside DIV's
own gating - a decompile/source-read task, not a new run. Not executed in
this entry.

### Disposition

Two candidate explanations ruled out by direct, authoritative evidence
(an existing test and the design doc's own explicit statement) in this
entry. No new hypothesis substituted without equivalent verification. No
DIV-fixable bug established. No native run performed, proposed, or
needed for this specific next step. Native gate: one-run grant remains
CONSUMED.

## Executed the pinned MTS caller trace per 54549 - a real, cited code path found, but does not by itself resolve the gap

Per 54549: execute the trace now, not propose it again. Used the already-
resolved MTS jar (hash `1d2f7512604636265703b29fdf13d7f8ef2323694b6435f4392973da6e78d251`,
re-confirmed via `sha256sum` directly against the live Gradle cache path)
and its already-decompiled source at
`/tmp/mts_inspect/decompiled/minecrafttransportsimulator/entities/
components/AEntityG_Towable.java` (same decompile this investigation
produced earlier via `cfr`).

### A real, cited MTS-native behavior found

`AEntityG_Towable.update()` (lines 97-107 of the decompiled source):

```java
public void update() {
    ...
    if (this.towedByConnection != null && !this.towedByConnection.towingEntity.isValid) {
        this.towedByConnection.towingVehicle.disconnectTrailer(
            this.towedByConnection.towingVehicle.towingConnections.indexOf(this.towedByConnection));
    }
```

This is MTS's OWN per-tick update - entirely independent of DIV's own
`active()`/`moveVehicle` gating. It checks: if this entity is still
`towedByConnection`-linked AND the connection's own cached
`towingEntity.isValid` is false (exactly the state after `towing.remove()`
is called, since Minecraft entity removal sets `isValid=false`) - it
calls `disconnectTrailer` on the (now-invalid) towing vehicle's own side.
If this succeeds, `towed.towedByConnection` would become `null` as a
result - entirely inside MTS's own code, not DIV's.

### This does NOT, by itself, explain the zero-step-entry gap - checked directly

If `towedByConnection` becomes `null`, DIV's own `active()` computes
`towed=false` on the next check. `deferToNativeHitch`'s own first operand
is `towed` - with `towed=false`, the whole expression is `false`
regardless of the other three arguments, so `decide()` returns `ACTIVATE`
directly (the defer branch is skipped, not entered) - this path still
leads to `BuoyantBody.step()` running, not being skipped. So even if this
real MTS-native auto-disconnect fires here, it does NOT by itself produce
the observed zero-step-entry gap - checked directly against the same
predicate already re-read this session, not assumed.

### Whether this actually fires in this run is NOT confirmed - a real, disclosed gap

Searched the retained log directly: the only `towedByConnection` line at
all is from `DETACH PASS` (before `TOWING_UNAVAILABLE` even begins,
confirming it was null THERE). No log line anywhere reports
`towed.towedByConnection`'s own state during `TOWING_UNAVAILABLE`'s 60
ticks in either this run or Trial 1/2 - this specific field was simply
never logged during that window by anything retained. Whether MTS's own
auto-disconnect above actually fires in this specific run cannot be
confirmed or ruled out from existing logs - stated as a genuine
measurement gap, not inferred either way.

### Disposition

A real, cited, jar-hash-verified MTS-native code path is now on record -
a genuine behavior worth knowing regardless of this specific mystery
(trailers can be silently disconnected by MTS's own update() when their
towing vehicle becomes invalid, bypassing DIV entirely) - but checked and
confirmed NOT to explain the zero-step-entry gap by itself, and its own
actual firing in this run is unconfirmed by any retained log. The
zero-step-entry mechanism remains unexplained. Smallest next testable
discrepancy, NOT executed: log `towed.towedByConnection`'s own
null-or-not state once, at the SAME existing diagnostic point already
used for TOWING_UNAVAILABLE's per-tick velocity line - a single boolean,
answering directly whether this MTS-native path is what's firing, without
needing the water-geometry window extension proposed earlier. No native
run performed in this entry. Native gate: one-run grant remains CONSUMED.

## Continued pinned caller trace per 54554 - found the main-loop exclusion gate, driver still unlocated

Per 54554: finish the actual selection→recursion→dispatch chain, not stop
at the single auto-disconnect line already found. Decompiled (via `cfr`,
same jar hash `1d2f7512604636265703b29fdf13d7f8ef2323694b6435f4392973da6e78d251`)
`minecrafttransportsimulator.baseclasses.EntityManager.class` and
`AEntityA_Base.class`, saved under `/tmp/mts_time_check/decompiled2/`.

### Found: the main-tick-loop exclusion gate, exact condition

`EntityManager`'s own main dispatch (lines 187-190 of the decompiled
source):
```java
this.allNormalTickableEntities.forEach(entity -> {
    if (!(entity instanceof AEntityG_Towable) || !((AEntityG_Towable)entity).blockMainUpdateCall()) {
        EntityManager.doTick(entity);
    }
});
```
`AEntityG_Towable.blockMainUpdateCall()` (confirmed at line 195-196 of the
earlier decompile):
```java
public boolean blockMainUpdateCall() {
    return this.towedByConnection != null;
}
```
This is a PURE check on `towedByConnection` alone - no validity check on
the towing vehicle it points to. `EntityManager.doTick` (the main per-tick
dispatch) is skipped for `towed` whenever `towedByConnection != null` -
true continuously from the moment `towed` is first attached, through
`DETACH`'s own reconnect, through `TOWING_UNAVAILABLE`'s entire 60 ticks,
regardless of whether `towing` itself still exists.

### This alone does not explain the observed behavior - a real inconsistency, disclosed not resolved

Since `blockMainUpdateCall()`'s condition is identical BEFORE and AFTER
`towing.remove()` (true the whole time `towed` stays connected), this gate
by itself cannot explain why `towed` was clearly ticked normally during
`ATTACH`/most of `TOWING_UNAVAILABLE`'s own predecessor state (confirmed
step-entry/`buoyantForcesDiagnostic` lines right up to `gameTime=203`,
immediately before `towing.remove()`) but stops being ticked afterward.
Searched the rest of `AEntityG_Towable.update()`'s own body (lines
110-183) directly for any recursive call driving a towed vehicle's own
`update()`/`moveVehicle` from its towing vehicle's side - found NONE;
that method's own towing-side logic is reconnect/save-restore/interaction
bookkeeping only, not a tick-dispatch recursion.

**This means the actual mechanism that normally ticks `towed` while
`towing` exists has NOT yet been located** - `EntityManager`'s own main
loop appears to exclude it the whole time regardless, so some OTHER
caller (not yet found in this entry) must be responsible, and THAT
caller's own dependency on `towing`'s continued existence/validity -
whatever it turns out to be - is the real remaining question. Not
guessing at what that caller is. Per 54554's own framing, this is
reported as the open QUESTION this trace has narrowed to, not a cause.

### Disposition

Confirmed, with exact cited source/jar hash, one real necessary piece
(the main-loop's own towed-exclusion condition, independent of towing's
validity) - but NOT yet the sufficient explanation, since that gate alone
predates and survives `towing.remove()` identically. No native run
performed. No new measurement proposed without first locating the actual
driver - continuing the caller trace is the honest next step, not
executed further in this already-large entry given budget. Native gate:
one-run grant remains CONSUMED.

## Exhaustive doTick-caller search per 54561 - no alternate recursive driver found anywhere in the jar; the contradiction deepens

Per 54561: identical gate values do not rule out a COMBINED mechanism
(constant gate + a disappearing alternate driver) - locate ALL `doTick`
callers and `towingConnections` traversals across the FULL pinned jar,
not just the one class already read.

### Exhaustive search performed

Extracted the FULL `minecrafttransportsimulator/*` and `mcinterface1211/*`
packages from the same pinned jar (hash
`1d2f7512604636265703b29fdf13d7f8ef2323694b6435f4392973da6e78d251`) - 678
total `.class` files - and searched every one, as raw bytes
(`grep -rlas`), for the literal string `doTick`. Exactly THREE classes
reference it at all:
- `EntityManager.class` - the two already-examined main-loop `forEach`
  call sites (both gated by `blockMainUpdateCall()`), and `doTick`'s own
  definition (`public static void doTick(AEntityA_Base entity)`, which
  itself just calls `entity.update()` + `doPostUpdateLogic()` - confirmed
  by decompiling its body directly, lines 288-299).
- `AEntityG_Towable.class` - the method definition site only (its own
  `blockMainUpdateCall()`/class membership, not a NEW caller).
- `AEntityF_Multipart.class` - `EntityManager.doTick(part)` (line 476) -
  ticks a vehicle's own installed PARTS, not a towed VEHICLE. Not the
  driver being searched for.

**No alternate `doTick` call exists anywhere in the pinned jar that would
recursively drive a towed VEHICLE's own `update()` from its towing
vehicle's side.** This directly contradicts 54561's own suggested
"alternate recursive driver" hypothesis - there isn't one findable by this
method, in this jar.

### Checked the real Minecraft-level entity tick too - confirmed it does not call `update()` either

Decompiled `mcinterface1211.BuilderEntityExisting` (the actual Minecraft
`Entity` subclass wrapping an MTS vehicle - confirmed by its own
`baseTick()` override, lines 76-123). It syncs position
(`this.setPos(this.entity.position...)`), checks world-border/validity,
and calls `this.discard()` if `!this.entity.isValid` - but NEVER calls
`.update()` anywhere in its own body. Minecraft's own automatic per-entity
tick (which runs `baseTick()` unconditionally, independent of MTS's own
`EntityManager` dispatch entirely) does not drive MTS's `update()` either.

### The contradiction is now sharper, not resolved

Per the exhaustively-confirmed source: `update()` (hence `moveVehicle`,
hence DIV's own `BuoyantBody.step()`) is ONLY ever reachable via
`EntityManager.doTick`, itself ONLY ever called from the two
`blockMainUpdateCall()`-gated main-loop `forEach` blocks. Since
`blockMainUpdateCall() = towedByConnection != null` is confirmed `true`
continuously from the moment `towed` is first attached onward, this
reading implies `update()` should NEVER run for `towed` for the ENTIRE
remainder of the probe, including `ATTACH`/`DETACH` - directly
contradicting the extensively-confirmed step-entry/`buoyantForcesDiagnostic`
lines that DO fire throughout that period. This is now a genuine
contradiction in my own understanding of when this gate actually applies
- not resolved by anything found in this entry. Possible explanations NOT
yet checked: whether server-side vehicle entities are added to
`allNormalTickableEntities` at all (vs. some other list/path not yet
examined), or whether `blockMainUpdateCall()`'s own override is more
specific in a subclass not yet read (`EntityVehicleF_Physics` itself,
which was not decompiled in this entry). Stated as unresolved, not
guessed past.

### Disposition

Exhaustive, cited, jar-hash-verified search performed - ruling out the
specific "alternate `doTick` recursion" hypothesis outright. The actual
mechanism remains unestablished; my own prior assumption about this
gate's applicability may itself be incomplete. Smallest next step, NOT
executed: decompile `EntityVehicleF_Physics` itself (not yet read) to
check for an overridden `blockMainUpdateCall()` or a separate tick path
specific to that class, and confirm which tickable list a server-side
vehicle entity actually belongs to. No native run performed. Native gate:
one-run grant remains CONSUMED.

## Retracting "rules out outright"; continued the chain per 54564; genuine capacity limit disclosed

Per 54564: a raw-byte string search for the literal `doTick` does NOT
rule out direct `update()`/`moveVehicle()` calls, virtual overrides, or
other mixin paths that never reference that literal string. Retracting
"rules out the alternate recursive driver outright" - that search only
ruled out ONE specific shape of alternate path (another `doTick` call
site), not every possible path. Overclaimed the search's own scope.

### Continued the chain - two new concrete facts, same jar hash

Decompiled (same jar, hash `1d2f7512604636265703b29fdf13d7f8ef2323694b6435f4392973da6e78d251`)
`EntityVehicleF_Physics`, `AEntityB_Existing`, `AEntityE_Interactable`,
`AEntityD_Definable`, and `AEntityVehicleD_Moving` - saved under
`/tmp/mts_time_check/decompiled4/`.

- No class in `towed`'s own hierarchy (`EntityVehicleF_Physics` up through
  `AEntityB_Existing`/`AEntityE_Interactable`/`AEntityD_Definable`)
  overrides `blockMainUpdateCall()`, and each `update()` override in that
  chain does nothing but call `super.update()` - no bypass found there.
- `AEntityVehicleD_Moving.update()` (lines 153-201) is where
  `performGroundOperations()`/`moveVehicle()` are ACTUALLY called from -
  confirmed directly in this decompile: `moveVehicle()` has no caller
  anywhere else in this hierarchy. So the full chain
  `moveVehicle → update → doTick → EntityManager's gated forEach` is now
  confirmed with no bypass ANYWHERE within this entity's own class
  hierarchy - narrower and more solid than the previous entry's raw-byte
  search alone established.
- Confirmed exactly where `towedByConnection` becomes non-null:
  `AEntityG_Towable.connectTrailer` (line 450) -
  `connection.towedVehicle.towedByConnection = connection` - set once, at
  `ATTACH`, and not cleared by anything found in this investigation
  through the end of `TOWING_UNAVAILABLE`.

### The contradiction still stands - not resolved, and a genuine capacity limit reached

With the ENTITY'S OWN full call chain now confirmed to have no bypass,
and `blockMainUpdateCall()`'s own single condition confirmed constant
from `ATTACH` onward, the contradiction with the extensively-logged
step-entry evidence during `ATTACH`/`DETACH` remains completely
unresolved. The one location not yet examined: the actual SERVER-side
entry point that invokes `EntityManager`'s own per-tick dispatch itself
(e.g. `WrapperWorld`'s own tick handler, or a server-specific
`EntityManager` subclass) - whether IT always reaches the SAME gated
`forEach` already read, or has its own separate/different dispatch logic
for server-side ticking specifically, has not been checked.

Stating plainly, not to be mistaken for a tool blocker: continuing this
specific decompile chain indefinitely within this single already very
large entry risks exhausting this session's own remaining capacity
without ever delivering a complete, reportable state. This is a real
constraint on THIS entry's own continued depth, not a claim that the
pinned source is inaccessible - every file needed so far has been
reachable and decompiled successfully. Flagging this honestly rather than
either stopping silently or claiming a resolution that was not reached.

### Disposition

No DIV-fixable bug established. No native run performed, proposed, or
needed for anything in this entry. The exact mechanism remains unresolved;
the next unexamined location is named precisely (`WrapperWorld`'s own
server tick entry point / the server-side `EntityManager` subclass) for
whoever continues this specific trace. Native gate: one-run grant remains
CONSUMED.

## The missing recursive driver found - a complete, source-confirmed starvation mechanism (54565/54568)

John's own independent, fresh decompile of `AEntityG_Towable.class` found
exactly what this entry's earlier search missed: `doPostUpdateLogic()`
(lines 264-275 of the SAME decompiled file this investigation already
had saved at `/tmp/mts_inspect/decompiled/.../AEntityG_Towable.java` -
re-verified directly, byte-for-byte matching John's own independent read):

```java
public void doPostUpdateLogic() {
    super.doPostUpdateLogic();
    if (!this.towingConnections.isEmpty()) {
        for (TowingConnection connection : this.towingConnections) {
            ...
            EntityManager.doTick((AEntityA_Base) connection.towedVehicle);
            if (connection.towedVehicle.towedByConnection != null) continue;
            break;
        }
    }
    ...
}
```

This IS the alternate recursive driver my own earlier search missed -
not because the jar-wide raw-byte search was wrong about WHERE "doTick"
appears (it correctly found this exact class), but because I
mischaracterized that match as "the method definition site only" without
re-decompiling/re-reading this specific method, despite already having
this exact file saved from earlier in this investigation. A real search
completeness error on my own part, not a tooling limitation - corrected
now by actually reading the method body.

### The complete mechanism, now fully source-confirmed

1. `EntityManager.doTick(entity)` calls `entity.update()` then (for any
   `AEntityD_Definable`) `definable.doPostUpdateLogic()` - confirmed
   earlier in this entry's own decompile of `doTick` itself.
2. For a TOWING vehicle, `doPostUpdateLogic()` (above) directly calls
   `EntityManager.doTick(towedVehicle)` for each of its
   `towingConnections` - bypassing `towed`'s own `blockMainUpdateCall()`
   gate entirely, since this is a direct call, not a re-entry into the
   gated main-loop `forEach`. **This is how a towed vehicle is normally
   ticked at all**, despite its own `blockMainUpdateCall()` being `true`
   continuously from `ATTACH` onward (confirmed earlier this entry) -
   resolving the contradiction cleanly.
3. This recursive call only fires while `towing` itself is still ticked -
   i.e., still present in `EntityManager`'s own tickable lists.
4. `towing.remove()` → `AEntityA_Base.remove()` (confirmed by John:
   sets `isValid=false`, then calls `world.removeEntity(this)`) →
   `EntityManager.removeEntity` strips `towing` from `allEntities` AND
   every tickable/definable-tickable list.
5. Once `towing` is removed, ITS OWN `doTick`/`doPostUpdateLogic` never
   fires again - so the recursive `doTick(towed)` call-site disappears
   with it.
6. `towed`'s own main-loop path is independently blocked the whole time
   (step 2's gate, confirmed constant). With BOTH paths now gone,
   `towed.update()` - hence `moveVehicle()`, hence DIV's own
   `BuoyantBody.step()` - never runs again. This matches every log
   observation already established in this investigation exactly: ticked
   continuously through `ATTACH`/`DETACH`, zero step-entry/diagnostic
   lines for the entire `TOWING_UNAVAILABLE` window, resuming only once
   `TOWING_REPLACEMENT` connects a fresh `towing2` (restoring its own
   recursive call).
7. This also explains why MTS's own auto-disconnect safety check (inside
   `update()`: `if (towedByConnection!=null && !towedByConnection
   .towingEntity.isValid) disconnectTrailer(...)`, found earlier this
   entry) never fires either: it requires `update()` to run, which is
   PRECISELY what towing's removal cuts off - a genuine starvation/
   deadlock in MTS's own native code specific to a towing vehicle being
   removed directly (`.remove()`) rather than via an orderly
   `disconnectTrailer` call (which explicitly nulls `towedByConnection`
   and updates the connection lists, side-stepping this entirely).

### Scope of this finding, stated precisely

This is a STATIC, source-level mechanism, fully confirmed via the pinned
jar's own decompiled bytecode (hash
`1d2f7512604636265703b29fdf13d7f8ef2323694b6435f4392973da6e78d251`),
independently cross-checked between this entry's own saved decompile and
a fresh one. It is NOT yet re-asserted here as formally proven runtime
causation for this exact execution (no new runtime trace was added in
this entry beyond what's already retained) - though it is consistent with
every single retained log observation across this entire investigation
(Trial 1/2, the authorized run, and the original non-crash reference) to
a degree no prior hypothesis achieved. This is entirely MTS's OWN
(third-party) native behavior - no claim is made about Sable's own native
physics solver, and no DIV-side fix is proposed or implied by this entry.
Whether/how DIV's own code should respond to this MTS-level starvation
(e.g., calling `disconnectTrailer` itself instead of relying on
`towing.remove()` alone, if that's even within DIV's own control for a
despawn triggered by something else) is a separate, unaddressed design
question - not decided here.

### Disposition

A complete, source-confirmed mechanism is now on record, resolving the
specific "why does towed stop being ticked" question this sub-trace
pursued across multiple rounds. No native run was performed. No DIV code
change, patch, or fix is proposed in this entry - per 54565/54568's own
explicit caution against treating a source-level finding as proof of
runtime causation or a native-solver fix without further confirmation.
Native gate: one-run grant remains CONSUMED.

## Completing remaining checks per 54576; correcting confidence label to conditional

Per 54576's own independent hierarchy check (same jar hash) - no
`remove()` override anywhere in `towed`'s own class chain
(`C_Renderable`/`E_Interactable`/`G_Towable`/`VehicleC_Colliding`/
`D_Moving`/`E_Powered`/`F_Physics`) calls `disconnectTrailer` or touches
`towingConnections` directly; `WrapperWorld`/`AWrapperWorld` have no
`removeEntity` override either, confirming `EntityManager.removeEntity`
(already read) is genuinely what runs. Two remaining caveats to close:

### Installed-part removal and DIV's own mixins - checked, confirmed clear

Decompiled `APart` (same jar) - no `remove()` override, no reference to
`towedByConnection`/`towingConnections`/`disconnectTrailer` anywhere in
that class. Checked DIV's own mixins directly (`grep` across
`src/main/java/io/github/brooswitminecraft/div/mixin/*.java`) - the only
match for "remove" is an unrelated accessor name
(`PartPropellerAccessor.java`); no DIV mixin injects into any entity-
removal path. Both caveats close clear: neither installed-part cleanup
nor any DIV-side hook touches this mechanism.

### Correcting the confidence label: conditional, source-supported - not proven for this exact run

Per 54576's own caution: the retained runtime logs do not carry an
explicit connection/list snapshot (e.g., a direct log of
`towed.towedByConnection`'s own value, or confirmation `towing` was truly
absent from `allNormalTickableEntities` at the exact tick) for THIS
specific execution. The mechanism found across this sub-trace (`54565`/
`54568`/this entry) is **source-level, fully confirmed via the pinned
jar's own decompiled bytecode, and consistent with every retained log
observation** - but it is a CONDITIONAL, source-supported starvation
hypothesis, not a proven full explanation of the `TOWING_REPLACEMENT`
crash itself. Retracting any stronger framing from the immediately prior
entry - that entry's own closing language ("fully resolved", "matches
exactly") overstated the confidence level by treating consistency with
retained logs as equivalent to a direct runtime measurement, which it is
not.

### Minimal regression/compatibility seam - proposed, not implemented

Per 54576's instruction to formulate (not build) a minimal, production-
used seam: DIV already has an existing, diagnostic-only observable well-
suited to detecting exactly this starvation symptom without any new
mechanism -
`PhysicsBridge.debugLastPoseUpdateGameTime(v)` (already used throughout
this investigation). If this MTS-level starvation is real, this value
would stay FROZEN (not advance) for a towed vehicle across consecutive
ticks despite the vehicle remaining registered/`alive`, while
`level.getGameTime()` keeps advancing - a direct, already-available
signal, requiring no new code. A future, NOT-executed regression could
assert exactly this relationship for the fixture's own `TOWING_UNAVAILABLE`
window, using data this investigation already has retained (comparing
`debugLastPoseUpdateGameTime` against `level.getGameTime()` across the
window) rather than needing a new native run. Not implemented as a test
in this entry - described as the minimal seam, per the explicit "no
guessed fix" instruction.

### Disposition

All remaining indirect-cleanup checks from 54576 are now closed. The
starvation mechanism remains a well-supported, source-confirmed
CANDIDATE explanation - correctly labeled conditional, not proven, for
this exact execution. No DIV fix proposed or implemented. No native run
performed. Native gate: one-run grant remains CONSUMED.

## Direct runtime confirmation found in already-retained logs per 54590

Per 54590: extract the actual retained `lastPoseUpdateGameTime` evidence
now, with body-role IDs and sample times, rather than propose a future
measurement for data this investigation already has.

### The exact, already-logged confirmation

From the SAME retained run (`M545-packet-738b091`), `createHitchDiagnostic`
lines (role-labeled `towing=`/`trailer=`, i.e. towed):

- `sampleGameTime=3`: `trailer` (towed, `fe330803...`)
  `lastPoseUpdateGameTime=-1` (never yet stepped - expected, pre-first-tick).
- `sampleGameTime=203` (just before `towing.remove()`): `trailer`
  `lastPoseUpdateGameTime=202` - one tick behind, normal steady-state lag.
- `sampleGameTime=204` (`TOWING_UNAVAILABLE pre-remove()`, the SAME tick
  `towing.remove()` is about to be called): `towed.lastPoseUpdateGameTime=203`
  - still fresh.
- **`sampleGameTime=264`** (`TOWING_REPLACEMENT`'s own fresh `createHitch`,
  connecting `towed` to the NEW `towing2`): **`trailer`
  (`fe330803...`, the SAME `towed`) `lastPoseUpdateGameTime=203`** -
  UNCHANGED from 60 ticks earlier, while `towing2`'s own
  `lastPoseUpdateGameTime=264` matches ITS OWN fresh `sampleGameTime`
  exactly.

**This is a direct, already-retained, REAL runtime measurement - not a
mocked symptom or a future proposal**: `towed`'s own pose-update
timestamp is frozen at EXACTLY `203` across a 61-tick span (`204` through
`264` inclusive) - precisely the window between `towing.remove()` and the
new `towing2`'s own connection. `BuoyantBody.step()` genuinely did not
run for `towed` during this exact span, confirmed by body-role-labeled,
already-logged data, not source inference alone. This upgrades the prior
entry's "conditional, source-supported" framing for the SPECIFIC claim
"`towed`'s own Java-side step/pose-update did not run during this window"
to a direct runtime confirmation - while the DEEPER mechanism (precisely
WHY, i.e. the `doPostUpdateLogic`-recursion-starvation chain) remains the
source-level explanation already established, now with its predicted
symptom directly measured, not merely consistent-with.

### Production-relevant lifecycle regression seam - fuller definition

Per 54590's own fuller framing, the real invariants worth a future
regression (DESIGN ONLY, not implemented or run in this entry):

1. **Ongoing exactly-once update**: while active and untowed-by-an-
   invalid-connection, a body's own `debugLastPoseUpdateGameTime` should
   advance by exactly 1 per real game tick - never 0 (starved) and never
   >1 (duplicate-stepped).
2. **Stale-connection cleanup / native-authority preservation**: an
   already-active trailer whose own towing vehicle disappears should
   either (a) keep its own native Sable body authoritative and
   continuing to update via SOME path, or (b) have its stale connection
   cleanly released - never silently stop updating while still reporting
   itself as connected/active, which is what this investigation found.
3. **Ordinary detach/reconnect without duplicate stepping**: a normal
   `disconnectTrailer`-then-reconnect cycle (as `DETACH`/`ATTACH` already
   exercise, confirmed working throughout this investigation) must not
   produce two advances of `debugLastPoseUpdateGameTime` in the same
   real tick, and must not regress once the starvation-prone path (an
   abrupt `remove()` instead of an orderly disconnect) is eventually
   addressed.

A minimal future test (not built here) would assert invariant 1 directly
against this exact fixture's own `TOWING_UNAVAILABLE` window, using
`debugLastPoseUpdateGameTime`/`level.getGameTime()` exactly as already
demonstrated above with real retained numbers - no mock, no native run,
since the needed observable already exists and the exact expected
numbers (frozen at 203 for 61 ticks) are now a concrete, falsifiable
reference point for a REAL future run to be checked against.

### Isolated observation packet - not needed further given the direct confirmation above

54590's own fallback ("if runtime operands truly block discriminating
changes, prepare the smallest isolated observation packet...") does not
apply here - the runtime operands did NOT block a discriminating
measurement; they were already retained and have now been extracted
directly. No new packet is proposed.

### Disposition

A direct, already-retained runtime measurement now confirms the specific
starvation symptom (frozen `lastPoseUpdateGameTime` across the exact
`towing.remove()`-to-`towing2`-connect window) with body-role-labeled,
real numbers - not a mock, not an inference. The deeper source-level
mechanism from prior entries remains the explanation for WHY; this entry
confirms its predicted symptom directly. No DIV fix implemented or
proposed. No native run performed. Native gate: one-run grant remains
CONSUMED.

## Precision correction per 54596; honest infeasibility report on the authorized JVM regression per 54595

Per 54596's own independent check: correcting the prior entry's "frozen
through all of 264" framing. The `createHitch` snapshot at
`sampleGameTime=264` captures state BEFORE that tick's own step (its own
`knownEventPhase=createHitch(before-this-ticks-step)` label, already
established earlier this investigation) - and the `BuoyantForces.apply`
`poseRefresh` line also at `gameTime=264` (already found in the prior
entry's own data) shows stepping DOES resume within that same tick,
AFTER the `createHitch` snapshot. Correct framing: `towed`'s own pose-
update timestamp is frozen for 60 FULL ticks (`204` through `263`
inclusive), still showing the pre-step snapshot value at the moment of
`createHitch`'s own read within tick `264` - not "frozen through all of
264." The underlying 60-tick starvation finding is unchanged; only the
boundary's own precise wording is corrected.

Also per 54596: the source has exactly one write site per class
(`BuoyantBody.java` line 115 / `PhysicsBridge.java` line 2096, both only
inside their own `step()`, immediately after `box.updatePose()`) -
confirmed again directly via `grep`, no other assignment exists. But
repeated calls within the SAME tick would write the IDENTICAL
`level.getGameTime()` value both times - so this timestamp alone CANNOT
distinguish "stepped once" from "stepped twice in the same tick",
invalidating regression invariant 1's own ">1 detects duplicate" framing
from the prior entry. A real invocation COUNTER (not this timestamp) is
needed for that specific invariant - noted, not yet built.

### Attempting the authorized JVM lifecycle regression - genuine infrastructure blocker found

Per 54595's explicit authorization to implement (not just design) a
regression using the EXISTING Minecraft-aware `unitTest` support,
exercising the REAL pinned `EntityManager`/`AEntityG_Towable` dispatch
(not a toy duplicate): checked this project's own test suite directly
(`grep` across `src/test/java/`) for any existing precedent constructing
a live `ServerLevel`/`MinecraftServer`/`EntityManager` instance - found
NONE. The existing `unitTest` wiring (established earlier this
investigation for `ShapeAwareBoxPhysicsObjectTest`) only ever
instantiated bare Java objects (a `Pose3d`, a `BoxPhysicsObject`) - never
a live, ticking World/Level with real registered entities.

`EntityManager` is `abstract`, requiring a concrete subclass whose
`getWorld()` returns a real `AWrapperWorld` wrapping an actual NeoForge
`Level` - and exercising its own real dispatch (`addEntity`/the gated
`forEach`/`removeEntity`/`doPostUpdateLogic` recursion, all already read
this investigation) against TWO real `EntityVehicleF_Physics` instances
genuinely requires a live, ticking server-side world - not something
buildable as a bare-object JVM test the way the existing `unitTest`
precedent works. Building this would mean either (a) a full headless
NeoForge dedicated-server bootstrap, or (b) NeoForge's own GameTest
framework (a real, ticking, in-process test world) - NEITHER of which
exists in this project today, and either would itself be substantial new
test infrastructure, not a small addition to the existing pattern.

**Reporting this precisely as the exact blocker requested, not a refusal
to attempt it**: implementing a regression against the REAL pinned
dispatch seam (as explicitly required, not a toy duplicate) needs a
headless/GameTest-capable Minecraft test harness this project does not
yet have. This is a genuine missing-infrastructure gap, not a native-run
request in disguise - no such harness was built or attempted in this
entry, and nothing in this entry ran, loaded, or ticked a real Minecraft
world. Per 54595's own explicit alternative ("report concrete test/fix/
head/receipt OR exact blocker"), this is the exact blocker: the project
needs a GameTest (or equivalent headless) harness added before a true
lifecycle regression against the real dispatch chain can be built - a
decision and scope (new build-tooling/dependency, not covered by
"focused JVM validation under the existing exclusive slot" alone) this
entry does not make unilaterally.

### Disposition

Precision correction made. Genuine infrastructure blocker reported
precisely, not a stall. No toy/non-representative test was built in its
place. No DIV fix implemented (per 54595's own conditional: only
justified once a real failing-before regression exists, which itself
requires the missing harness). No native run performed. Native gate: one-
run grant remains CONSUMED; this entry does not request another.

## Corrected and wired the diagnostic implementation per 54600

Per 54600's own read of the just-begun diff: four concrete corrections,
all addressed before treating anything as a lifecycle regression.

### (1) Retracted: the "already measured" counter claim

`debugStepInvocationCount` is NEW in this change - no previously-captured
log ever contained it. The prior draft's own test comment wrongly implied
its `203, 203` sample values were drawn from this investigation's actual
retained measurement; they were synthetic/illustrative. The REAL, already-
measured number remains the separate `lastPoseUpdateGameTime` field
(frozen at `203` across the full removal-to-reconnect span, already
documented earlier this entry) - the test now says so explicitly and no
longer conflates the two.

### (2) Fixed: aggregate delta is not sufficient for "exactly-once"

`advancedExactlyBy`'s own aggregate check (`after-before == expectedTicks`)
cannot distinguish a perfect per-tick sequence from a starved tick
followed by a duplicated one (`0` then `+2` sums to the same `2` as two
`1`s). Added `exactlyOncePerTick(long[] counts)` - checks every
CONSECUTIVE pair in a real per-tick sample sequence, not just the two
endpoints. A new test (`exactlyOncePerTickFalseForStarvationThenDuplicate
EvenThoughAggregateMatches`) demonstrates the exact failure the old check
alone would miss, confirming the new check catches it while the old one
still (correctly, if insufficiently) passes.

### (3) Fixed: wired into the actual fixture, not left isolated

Added `towedStepInvocationCountAtRemoval` (captured immediately after
`towing.remove()` returns, in `TOWING_UNAVAILABLE`'s own existing post-
remove block) and a new `lifecycleInvariantCheck` log line at
`TOWING_REPLACEMENT`'s own pre-connect snapshot, calling the real
`LifecycleInvariants.starvedAcrossSpan` directly against these two real
captured counts - a genuine production-used wiring into the retained
fixture, not a standalone pure class tested only in isolation. Diagnostic-
only: a new LOG line reporting the check's own result; no change to this
fixture's existing pass/fail criteria or physics.

### (4) Fixed: counter now measures true entry, not post-updatePose success

Moved the `debugStepInvocationCount++` increment to the TOP of `step()`
in both `BuoyantBody` and `PhysicsBridge.Body`, before `box.updatePose()`
- a true invocation-entry count matching the field's own documented
intent, not a "pose successfully updated" count that would silently
mislabel what's measured if `updatePose()` ever failed.

### Verification

Compiled cleanly (`M545-lifecycle-compile3`, `RESULT=COMPLETE rc=0`).
`LifecycleInvariantsTest`: 9/9 passing (`M545-lifecycle-test2`,
`RESULT=COMPLETE rc=0`). Re-ran all four pure suites together
(`DiagnosticWindowTest`/`DiagnosticContextTest`/
`BuoyantForcesWaterScanBoundsTest`/`LifecycleInvariantsTest`,
`M545-regression-check`, `RESULT=COMPLETE rc=0`) - no regression, 23
tests total across this investigation's own pure suites.

### Disposition

Diagnostic instrumentation corrected and wired into the real, retained
fixture, per all four of 54600's own corrections. No gameplay/fixture
physics change - every addition is a counter/log line or a pure helper
call, none altering control flow or applied forces. No DIV compatibility
fix implemented (54595's own condition for that - a justified, failing-
before regression against the real dispatch chain - still requires the
native-run-adjacent harness not yet authorized). No native run performed.
Native gate: one-run grant remains CONSUMED.

## Real per-sample evaluation seam implemented per 54603; new isolated packet prepared (not run)

Per 54603's own corrections - all addressed:

### (1) `exactlyOncePerTick` is now wired, via a real per-sample evaluation seam

Added `LifecycleInvariants.Sample` (body UUID + gameTime + the real
invocation count), `Outcome` (`PASS`/`FAIL_STARVED_TICK`/
`FAIL_DUPLICATE_TICK`/`INSUFFICIENT_SAMPLES`/`BODY_IDENTITY_CHANGED` - a
real enum, not a bare boolean, so "could not be checked" is never
conflated with "checked and passed"), and `evaluate(List<Sample>)` - the
actual production-used seam, walking every consecutive pair in a REAL
per-tick sequence.

### (2) Body generation/presence distinguished from a genuine zero-count

`evaluate` checks body identity BEFORE interpreting any count delta - a
missing/replaced body (whose `debugStepInvocationCount` accessor returns
`0`, per `PhysicsBridge`'s own existing doc) now reports
`BODY_IDENTITY_CHANGED`, not a false `FAIL_STARVED_TICK`. A counter that
decreases is treated the same way (not a new/invented failure category).

### (3) Real gameTime sequence validation

`evaluate` requires each consecutive sample pair's `gameTime` to differ
by exactly `1`; a gap reports `INSUFFICIENT_SAMPLES` - distinguishing a
genuine sampling defect from an actual pass/fail finding.

### (4) Wired into the actual retained fixture across the full span

Replaced the single two-endpoint comparison with a REAL per-tick sample
list (`towedRemovalSpanSamples`), appended: once immediately after
`towing.remove()` returns; once every tick of `TOWING_UNAVAILABLE`'s own
existing per-tick loop; once more at `TOWING_REPLACEMENT`'s own pre-
connect snapshot. The real phase-boundary gameTime gap (`TOWING_UNAVAILABLE`'s
own tick counter resets into `TOWING_REPLACEMENT`'s separate tick=1,
already established earlier this investigation) is handled explicitly:
the WITHIN-`TOWING_UNAVAILABLE` portion of the sequence is evaluated on
its own (excluding the post-gap sample), reported via a new
`lifecycleInvariantCheck` log line - diagnostic-only, capturing after the
original phase sequence has already run, never altering this fixture's
own existing pass/fail criteria, timing, or physics.

### (5) Focused tests cover the real seam, not just arithmetic

16 tests total (up from 9): `evaluate`'s own `PASS`/the exact balanced-
`0`-then-`+2` case 54603 named (confirmed `FAIL_STARVED_TICK`, with the
aggregate check alone shown to still incorrectly pass it, demonstrating
precisely why the per-tick walk is needed)/a single-tick duplicate/a
sparse non-consecutive gameTime gap/fewer-than-two samples/a replaced-
body case/a backward-moving-counter case - alongside the existing pure
arithmetic tests. Compiled cleanly and ran 16/16 passing, alongside the
other three pure suites with no regression (`M545-seam-compile2`,
`RESULT=COMPLETE rc=0`).

### New isolated packet prepared against this commit - NOT executed

Per 54603's own note that preparing (not running) a packet needs no
additional permission: created
`/home/booswrit/div-evidence/MINECRAFT-545-547/M545-packet-2a03b04/`
(named for this round's own commit) under the existing exclusive slot
(`M545-packet2-prep`, `RESULT=COMPLETE rc=0`). Regenerated the real
launch artifacts against this commit; confirmed the `cd` path is byte-
correct; `serverRunClasspath.txt`/`serverRunVmArgs.txt`/
`serverRunProgramArgs.txt` hash IDENTICAL to the prior packet (expected -
only Java source changed, not dependencies/VM args); only `runServer.sh`
differs (its own baked-in directory path). Populated a real isolated
config copy with `level-seed=545547` set only there (confirmed via `diff`
- exactly one line differs from the shared file); the input manifest
matches the retained baseline on all ten files. Confirmed, read-only: the
shared `run/world/level.dat`/`level.dat_old` are still byte-identical to
the original retained baseline - nothing touched. Reused the SAME proven
`launch-wrapper.sh` verbatim (only the directory name/owner tag differ,
confirmed via `diff`). Confirmed `rundir/` is empty - NOT executed, no
process launched under this packet's own tag.

### Disposition

Diff + 16 tests + a prepared (not executed) isolated packet, all per
54603's own explicit corrections. No native run performed. No DIV
compatibility fix implemented - still conditional on a justified, failing-
before regression against the real dispatch chain, which remains
infrastructure-blocked as reported earlier this entry. Native gate: one-
run grant remains CONSUMED; nothing in this entry requests another.

## Three concrete bugs fixed per 54616; bug 4 (wrapper preflight) in progress

John's independent review of 432397f (54616) found four real, concrete
problems with the prior change - not a request to re-prove what was
already established, a bug report against the instrumentation itself.
Addressed bugs 1-3 in this entry; bug 4 (the launch-wrapper preflight)
follows in the next commit/entry. No native run performed or requested.

### Bug 1 - duplicate same-gameTime sample at tick 1 (FIXED)

`towingUnavailable()`'s own `ticksInPhase==1` block appended a sample
immediately after `towing.remove()` returns, and the UNCONDITIONAL
per-tick block (which also runs for `ticksInPhase==1`, since it carries no
guard excluding it) appended ANOTHER sample at the identical `gameTime` -
no real tick elapses between the two appends. `evaluate()`'s own logic was
never wrong: a zero `gameTime` delta between consecutive samples IS a
non-consecutive pair, correctly `INSUFFICIENT_SAMPLES` - but that correct,
immediate verdict made the real per-tick sequence that followed
unreachable in practice, since the very first pair always failed the
check before any real interval was walked.

Fix: removed the duplicate append inside the `ticksInPhase==1` block,
keeping only its `.clear()` call (defends against a hotload/repeat-call
leaving a prior span's samples behind). The unconditional per-tick block
is now the SOLE capture point, tick 1 included - exactly one sample per
real tick.

Regression tests added to `LifecycleInvariantsTest`:
`evaluateReportsInsufficientForTheExactPreFixDuplicateFirstSamplePattern`
(pins the exact pre-fix shape, confirming `evaluate` itself was always
correct given that input) and
`evaluatePassesForTheFixedSingleFirstSamplePerTickSequence` (confirms the
fixed shape is actually walkable).

### Bug 2 - entity UUID cannot see a same-entity body swap (FIXED)

`Sample.bodyId` is the ENTITY's own UUID (`v.uniqueUUID`), unchanged even
when DIV's OWN registry entry for that entity is torn down and
reconstructed as a fresh body instance under the same entity -
`debugStepInvocationCount`'s accessor returning a low/zero count in that
case is indistinguishable, from the entity UUID alone, from this SAME
still-live body genuinely failing to advance.

Fix: added `PhysicsBridge.debugRegistryBodyIdentity(EntityVehicleF_Physics)`
- returns `0` when `registry.get(v)` is currently absent, otherwise
`System.identityHashCode` of the actual `VehicleBody` instance currently
registered (necessarily different the instant a fresh instance replaces
it - construction always allocates a new object). `Sample` gained a new
`registryBodyIdentity` field (entity UUID NOT renamed or repurposed, per
John's own explicit instruction); `evaluate()` now checks it alongside
`bodyId` on every consecutive pair, before the step-count delta, so a
swapped-in fresh body is reported as `BODY_IDENTITY_CHANGED` even when its
own step count happens to advance by exactly 1 (what a per-tick walk alone
would otherwise call `PASS`). Both probe call sites updated to pass the
new observable.

Regression tests added:
`evaluateBodyIdentityChangedWhenTheSameEntityUuidGetsAFreshRegistryBody`
(same UUID, step count advances by 1, but registry identity changed - must
still report `BODY_IDENTITY_CHANGED`, not `PASS`) and
`evaluateBodyIdentityChangedWhenTheRegistryBodyDisappearsEntirely` (same
UUID, registry identity goes from present to `0`/absent).

### Bug 3 - no connection-membership observations beyond a non-null
pointer; no detach/reconnect check; outcome was LOG-only (FIXED)

Added, log-only (no established "wrong" value exists yet to assert
against, so these are new exploratory observations, not new failure
conditions): every `TOWING_UNAVAILABLE` tick now also logs whether
`towed.towedByConnection.towingVehicle == towing` (the connection object
still identifies the now-removed `towing` as its owner) and whether
`towing.towingConnections.contains(towed.towedByConnection)` (the REAL
list MTS's own `doPostUpdateLogic` would recurse over if `towing` were
ever ticked again still holds this connection) - both read-only over the
same public fields/lists MTS's own dispatch code reads, confirmed present
via decompile (`AEntityG_Towable.towingConnections`,
`TowingConnection.towingVehicle`).

Added a genuine detach/reconnect-specific check in `TOWING_REPLACEMENT`,
immediately after `towing2.connectTrailer(connection, false)`: confirms
`towed.towedByConnection == connection` (the exact new object, not merely
non-null), `towed.towedByConnection.towingVehicle == towing2`, and
`towing2.towingConnections.contains(connection)` - the real bidirectional
rewiring `connectTrailer`'s own contract promises, not just a non-null
pointer that a stale/wrong connection object could also satisfy.

Turned the previously LOG-only `withinUnavailableOutcome` into a real
terminal diagnostic ASSERTION: if it is `FAIL_STARVED_TICK` or
`FAIL_DUPLICATE_TICK`, the fixture now sets `phase = Phase.FAILED` and
stops - a genuine lifecycle defect is now a visible fixture failure, not
merely a line a human had to separately notice. Deliberately NOT asserted
on `INSUFFICIENT_SAMPLES`/`BODY_IDENTITY_CHANGED` - both mean the check
itself could not be completed (a sampling gap or an identity change),
never "checked and found broken"; asserting on either would misreport a
measurement limitation as a lifecycle defect. The assertion is placed at
the END of `towingReplacement`'s own `ticksInPhase==1` block, AFTER every
piece of that tick's evidence (the full within-TOWING_UNAVAILABLE sample
sequence, the pre-connect snapshot, the actual `connectTrailer` call, and
the new post-connect reconnect check) has already run and already been
logged - "defer outcome failure until evidence captured," per 54616's own
wording - and the original physical phase sequence/timing is completely
unchanged in the non-failing case.

### Verification performed

Compiled and ran the full test suite (`gradle compileJava test`) under
the exclusive `/tmp/div-gradle-slot.lock`, receipts logged in
`/tmp/div-gradle-slot.owner` (labels
`MINECRAFT-545-fix-bug1-20261117T143618Z`,
`MINECRAFT-545-fix-bug2-20261117T144003Z`,
`MINECRAFT-545-fix-bug3-20261117T144419Z`) - all three runs
`RESULT=PASS`. 20 `LifecycleInvariantsTest` tests (16 prior + 4 new) and
the rest of the existing suite pass. No native/Minecraft execution
performed - compile and pure-JUnit only.

### Not yet addressed in this entry

Bug 4 (54616, point 4): the launch-wrapper's own fail-closed preflight
inside the same FD9 lock lifetime, before `exec`, verifying actual
referenced classes/resources/jars/arg-file runtime BYTES (not just
arg-text hashes) plus the unique rundir/seed/fresh-world/original-baseline
conditions. Follows in the next commit.

### Disposition

Three of four concrete bugs from 54616 fixed and regression-tested; no
native run performed or requested. Native hold remains CLOSED per 54616's
own closing line.

## Bug 4 fixed: launch-wrapper fail-closed preflight, inside the same FD9 lock, before exec

Addresses the last of 54616's four concrete bugs. The prior `launch-
wrapper.sh` acquired the exclusive `/tmp/div-gradle-slot.lock` then
directly exec'd the SHARED `build/moddev/runServer.sh` with no
verification step in between - a real race, since those files are shared
across MULTIPLE tickets' own agents (the lock/owner-file convention exists
specifically because of that sharing). Another ticket's own
`prepareServerRun` running between this packet's own prep and this
specific launch could silently substitute different runtime bytes under
the same filenames, and the only thing catching that was an operator
manually re-reading one `cd` argument by hand - which covered the arg
TEXT only, never the actual jar/class bytes those args point at.

### What changed

New `preflight.sh`, run by the wrapper INSIDE the same lock acquisition,
immediately after `CLAIMED` and before anything is launched. Six
fail-closed checks, in order; any failure writes
`RESULT=PREFLIGHT_FAILED` to the owner file, releases the lock, and exits
- never execs:

1. Live `runServer.sh`/`serverRunClasspath.txt`/`serverRunVmArgs.txt`/
   `serverRunProgramArgs.txt` still match this packet's own archived
   `generated-launch-artifacts.sha256` (unchanged check, now re-run live
   instead of only at prep).
2. NEW - `runtime-byte-manifest.sha256`: every individual CLASSPATH
   ENTRY's own runtime bytes, not the arg text. A new shared
   `runtime-manifest.sh` function (`compute_runtime_byte_manifest`)
   parses the live `serverRunClasspath.txt`'s own `-classpath` line and,
   for each of its 100 entries, hashes it directly if it's a single file
   (all 98 dependency jars) or computes a sorted per-file content-hash
   aggregate if it's a directory (the 2 exploded build-output dirs,
   `build/classes/java/main` and `build/resources/main`) - the SAME
   algorithm used once at packet-prep time (producing this packet's own
   `runtime-byte-manifest.sha256`, archived) and again live, right before
   launch, with a byte-for-byte diff between the two. A real jar swap or
   recompiled class is now caught here, not just an arg-text change -
   this is the actual gap 54616 named ("checking actual referenced
   classes/resources/jars/arg files", not just arg-text hashes).
3. The isolated rundir resolves to a genuinely different path than the
   shared `run/` directory.
4. No stale `world/` directory already exists under the isolated rundir
   (fresh world for this specific trial).
5. `level-seed=545547` is live-present in the isolated
   `server.properties`, re-read right now, not merely checked once at
   prep.
6. The isolated config copy still matches this packet's own archived
   `baseline-original-run-config.sha256` (re-run live, same as check 1).

### Verification performed (no native run)

Dry-ran `preflight.sh` directly against the real, freshly-prepared
`M545-packet-f85e79f` packet (commit `f85e79f`, this ticket's current
HEAD after the bug 1-3 fixes) - all 6 checks reported `OK`/`PASS`, exit
code 0. This call is read-only (hashing/diffing only; it never execs
anything) - running it is not a native/Minecraft execution and is not
covered by the native hold.

Ran two deliberate NEGATIVE tests against disposable copies of the
packet (never the real packet), confirming the fail-closed path actually
works and not just the happy path:
- Mutated the isolated `config/mtsconfig.json` after copying - preflight
  correctly reported `PREFLIGHT_FAILED` (config baseline mismatch), exit
  code 1.
- Corrupted one jar's expected hash in the copy's own
  `runtime-byte-manifest.sha256` - preflight correctly reported
  `PREFLIGHT_FAILED` (runtime-byte-manifest mismatch) with a readable
  diff, exit code 1.
Both disposable copies were deleted after the test; the real packet was
never touched by either negative test.

### Packet M545-packet-f85e79f prepared (NOT executed)

Regenerated `build/moddev/runServer.sh`/`serverRunClasspath.txt`/
`serverRunVmArgs.txt`/`serverRunProgramArgs.txt` under the exclusive lock
(`gradle prepareServerRun createServerLaunchScript`,
`DIV_ISOLATED_RUN_DIR`/`DIV_BUOYANT_HITCH_PROBE=true`), confirmed
`runServer.sh`'s own `cd` argument points at this packet's own rundir
(not `run/`, not a prior packet's rundir). Archived copies + arg-text
hashes (`generated-launch-artifacts.sha256`) + the new
`runtime-byte-manifest.sha256` (100 entries, 0 missing). Isolated
`config`/`eula.txt`/`server.properties` copied from the shared `run/`
directory with `level-seed=545547` set ONLY in the isolated copy
(confirmed via `diff` - exactly one line differs). Isolated config
confirmed byte-identical to the original, never-mutated reference run's
own `baseline-original-run-config.sha256` - no DIV config drift since
that baseline. Confirmed `rundir/world` does not exist (fresh). The new
`launch-wrapper.sh`/`preflight.sh`/`runtime-manifest.sh` installed into
the packet with real paths substituted. `rundir/` contains only
config/eula/server.properties and the one retained
`runtime-byte-manifest-live-*.sha256` evidence file from the successful
dry-run above - NOT executed, no java process ever launched under this
packet's own tag.

### Disposition

All four concrete bugs from 54616 now fixed and verified (1-3 via
compile+test under the lock; 4 via the preflight dry-run plus two
negative tests, all read-only/non-native). No native run performed or
requested. Native hold remains CLOSED.

## Corrections per 54635 (independent review of f85e79f)

John's independent review of commit `f85e79f` (the bug 1-3 fixes) found
two more concrete correctness problems with that change itself, confirmed
the bug-3 connection observations should stay log-only (already true,
now stated explicitly in-code), and flagged a scope distinction this
entry must state plainly before any packet review. No native run
performed or requested; "inside-lock preflight explicitly still
underway, preserve same owner" - bug 4 continues under this same entry.

### registryBodyIdentity: System.identityHashCode is not collision-free (FIXED)

The prior `debugRegistryBodyIdentity`/`Sample.registryBodyIdentity`
returned/stored `System.identityHashCode(body)` as an `int`, documented as
"Java never reuses an identity hash across live distinct instances" -
that claim is simply FALSE. `identityHashCode` is a hash, not a
guaranteed-unique id; two distinct live objects CAN collide (small but
real, and the javadoc asserted otherwise).

Fix: `PhysicsBridge.debugRegistryBodyIdentity` now returns the actual
`Object` reference (`registry.get(v)` directly, `null` if absent) instead
of a hash code. `Sample.registryBodyIdentity` is now typed `Object`.
Confirmed via grep that neither `Body` nor `BuoyantBody` overrides
`equals()`/`hashCode()`, so the default reference-identity `equals()`
(used by `evaluate` via `Objects.equals`) genuinely distinguishes any two
distinct instances with zero collision risk - correct specifically
because this stays within ONE bounded diagnostic capture (a single
fixture run's own in-memory `List<Sample>`), never serialized or compared
across JVM runs/GC epochs where a raw reference could be stale or reused.
This is "reference-identity comparison within bounded diagnostic
capture," per 54635's own wording - not a generation-token scheme, and no
broad registry refactor.

### registryBodyIdentity == null on both sides misread as FAIL_STARVED_TICK (FIXED)

Two consecutive samples with no body registered on EITHER side (both
`registryBodyIdentity == null`) previously fell through to the step-count
delta check: `debugStepInvocationCount` also returns `0` when a body is
absent, so both samples' counts were `0`, a zero delta - exactly
`FAIL_STARVED_TICK`'s own signature for a body that genuinely exists but
isn't stepping. There is no body at all in this case, so there is nothing
that could have starved.

Fix: added a new `Outcome.BODY_ABSENT`, checked immediately after the
entity-UUID check and before the registryBodyIdentity-transition check -
both sides `null` now reports `BODY_ABSENT`, a distinct "nothing to
check" outcome (same spirit as `INSUFFICIENT_SAMPLES`/
`BODY_IDENTITY_CHANGED`, never conflated with a real stepping defect). A
genuine transition between present and absent (one side has a body, the
other does not) is still correctly `BODY_IDENTITY_CHANGED`, not folded
into `BODY_ABSENT` - a real disappearance event is real information, not
"nothing to check."

New test: `evaluateBodyAbsentWhenNoBodyIsRegisteredOnEitherSide`. All
`Sample` construction in `LifecycleInvariantsTest` updated for the
`Object`-typed field (two distinct plain `Object`s standing in for two
distinct live body instances, `null` standing in for absent).

### Reconnect-membership observations remain log-only (confirmed, no behavior change)

54635 confirmed the bug-3 connection/reconnect-membership booleans
(`towedConnection.towingVehicleIsRemovedTowing`,
`towing.towingConnectionsStillContainsIt`,
`towed.towedByConnectionIsNewConnection`, etc.) should stay observations,
not assertions - already true in the committed code, now stated
explicitly in-line at the reconnect-check log site: there is no
established "wrong" value for these specific booleans to assert against
yet: only `withinUnavailableOutcome` (a real per-tick lifecycle
measurement with an actual established defect signature) is wired into
the fixture's own fail path.

### Scope statement: this packet, if ever run, is a BOUNDED lifecycle
regression check, not a reconnect-crash reproduction

Stated plainly, per 54635's own explicit instruction: the new terminal
assertion added in the bug-3 fix (if `withinUnavailableOutcome` is
`FAIL_STARVED_TICK`/`FAIL_DUPLICATE_TICK`) fires at `TOWING_REPLACEMENT`'s
own `ticksInPhase==1` - i.e., at the very first tick of that phase, BEFORE
the original, previously-observed crash site (`TOWING_REPLACEMENT`
tick=7, the "left bounded area" divergence this investigation's single
authorized run actually hit). If this packet is ever authorized and run
and that assertion fires, the fixture stops at tick 1 with a
lifecycle-defect verdict - it does NOT continue on to tick 7/8 to
re-confirm the original crash still reproduces unchanged. These are two
different experiments: "does the already-established starvation
mechanism show up as a measured, per-tick-granular lifecycle defect" (what
this packet's own new instrumentation checks) versus "does the full
original reconnect-crash scenario still reproduce all the way to its own
divergence point" (the ORIGINAL, still-unrepeated authorized run from
earlier this investigation). A future reader/reviewer must not treat a
tick-1 lifecycle-assertion failure from this packet as if it were an
unchanged reproduction of the tick-7/8 crash - the evidence scope is
narrower and arrives earlier in the phase.

### Disposition

Both concrete correctness bugs from 54635 fixed and regression-tested
(compile+test under the lock, `RESULT=PASS`,
`MINECRAFT-545-fix-54635-20261117T145603Z`). Reconnect-observation
distinction confirmed and stated explicitly in-code. Scope distinction
stated above. Bug 4 (inside-lock preflight) continues under this same
owner, already implemented and dry-run verified against the prior
commit's own packet - the packet itself needs regenerating against this
new commit before it is ready for review, since the class files
underlying its own runtime-byte-manifest have now changed again. No
native run performed or requested. Native hold remains CLOSED.

## Corrections per 54638 (readback of 4b6190b, concrete preflight gaps)

John's 14:58:57Z readback confirmed the identity fixes from the previous
entry (registry reference + BODY_ABSENT) are correct, confirmed the new
wrapper invokes preflight.sh correctly (after FD9 acquisition, lock
retained through Java exit), and found four concrete remaining gaps in
`preflight.sh` itself - not new requirements, gaps in the already-
requested bug-4 preflight. No native run performed or requested.

### Fixed

1. **Check 6 only covered 8 config files, never eula.txt/server.properties.**
   Added check 7: re-verifies the FULL 10-file `isolated-input-manifest.sha256`
   (config + eula.txt + server.properties) live, inside the lock -
   previously that manifest was only ever checked once, at prep time.

2. **Never checked the LIVE SHARED source config, only the isolated copy.**
   Added check 8: re-hashes `run/config/*.*` (the shared directory the
   isolated copy was itself made FROM) against the SAME
   `baseline-original-run-config.sha256`, live, inside the lock. Catches
   drift in the shared source that check 6 (isolated copy vs. archived
   baseline) cannot see on its own.

3. **Never checked the shared world's `level.dat`/`level.dat_old`.**
   Added check 9: re-hashes `run/world/level.dat`/`level.dat_old` against
   a new `baseline-original-run-world-level-dat.sha256`, archived in this
   packet (reusing the SAME hash values an earlier packet already
   confirmed, re-verified live right now - still `OK` against the shared
   world, unmutated since that baseline was captured).

4. **Only the seed was asserted, not the canonical world target.** Check
   5 now also asserts `level-name=world` live in the isolated
   `server.properties`, not only `level-seed=545547`.

5. **`compute_runtime_byte_manifest` emitted "MISSING" and kept going -
   two incomplete manifests could coincidentally both carry the same
   "MISSING" line and still diff-match, silently passing.** A missing or
   unreadable classpath entry (file or directory) is now a HARD FAILURE
   of generation itself (prints the reason to stderr, returns 1
   immediately, emits nothing further). `preflight.sh` check 2 now checks
   generation's own exit status before comparing anything.

### Verification performed (no native run)

Regenerated the packet against commit `4b6190b` (the identity fixes from
the previous entry) as `M545-packet-4b6190b` - the prior `f85e79f`
packet's own runtime-byte-manifest predated those edits, per 54638's own
point. Dry-ran the full 9-check `preflight.sh` directly against this real
packet twice (before and after the negative tests below) - all 9 checks
`OK`/`PASS` both times, exit code 0 both times.

Four additional negative tests, all against disposable copies (the real
packet/worktree untouched except one deliberate, fully-reverted live
mutation, confirmed restored byte-for-byte against the baseline
afterward):
- Tampered the isolated `eula.txt` in a disposable copy - check 7
  correctly failed closed.
- Tampered the LIVE SHARED `run/config/mtsconfig.json` (reverted exactly
  afterward via the pristine isolated copy, confirmed byte-identical to
  the original baseline hash again before continuing) - check 8
  correctly failed closed.
- Corrupted the expected hash in a disposable copy's own
  `baseline-original-run-world-level-dat.sha256` - check 9 correctly
  failed closed.
- Pointed `compute_runtime_byte_manifest` at a classpath referencing a
  nonexistent jar - generation itself correctly failed (exit code 1,
  explicit stderr reason), not a silently-matching "MISSING" line.

### Disposition

All four concrete gaps from 54638 fixed and verified; packet
`M545-packet-4b6190b` now carries 9 fail-closed preflight checks, all
passing against the real, current commit. No native run performed or
requested. Native hold remains CLOSED. Awaiting further readback before
considering this packet ready for native release.

## Corrections per 54644 (readback of 7ca4203): pipefail bug fixed; test-count claim corrected

### runtime-manifest.sh: `set -u` alone does not propagate pipeline failure (FIXED)

John's 54644 found the prior hard-fail fix (54638's own correction) was
itself incomplete: `set -u` does not make a pipeline's exit status
reflect an early stage's failure - bash reports only the LAST command's
own exit status unless `pipefail` is explicitly on. A failing `find` (or
the first `sha256sum` in the directory-aggregate pipeline) whose output
happens to be empty can still let `sort`/`sha256sum`/`awk` downstream
exit 0 on empty input, so the `|| return 1` after the pipeline never
actually fired - the exact silent-mask failure mode this function exists
to prevent, just one stage further in than the "entry does not exist"
case already fixed.

Fix: `compute_runtime_byte_manifest` now turns `pipefail` on for its own
duration (saved/restored on every exit path, including the early-return
failure branches), so a pipeline's exit status reflects failure at ANY
stage. Confirmed via a direct negative test: a classpath entry directory
containing one `chmod 000` unreadable file now correctly produces
`RUNTIME_MANIFEST_GENERATION_FAILED` and exit code 1 - this exact case
would have silently produced a (wrong but "successful") aggregate hash
under the pre-fix version, since `sort`/`sha256sum`/`awk` after the
`sha256sum ... ; Permission denied` line all still exit 0. Dry-ran the
full 9-check preflight.sh again against the real, current packet with
the fixed script installed - still `PREFLIGHT_OK`, exit 0, no regression
to the happy path.

### Test-count claim corrected: 21 tests, not 24

My own prior comment (54641) claimed "24 LifecycleInvariantsTest tests
total." That was wrong - verified directly against both the source file
(`grep -c "@Test"` = 21) and the actual retained JUnit XML report
(`build/test-results/test/TEST-...LifecycleInvariantsTest.xml`,
`tests="21" ... failures="0" errors="0"`) - John's own figure (21) was
correct. The error: miscounting how many NEW tests each fix actually
added (2 from the bug-1/2/3 fix, not 4; 3 from the 54635 identity fix,
not folded in correctly) rather than re-deriving the running total from
the file itself each time. Also confirmed the full retained suite total
John cited: summing `tests="N"` across all 89 retained
`build/test-results/test/*.xml` files gives exactly 837, matching
54644's own figure.

### Disposition

Both concrete issues from 54644 addressed: the pipefail bug fixed and
negative-tested; the test-count claim corrected against the actual
source file and retained XML receipt, not re-derived from memory. No
native run performed or requested. Native hold remains CLOSED.

## Response to 54650: hash-mismatch investigation (not reproduced); raw receipts archived

John's 15:34 UTC independent readiness check reported his own
recalculation of the runtime-byte-manifest's two directory-aggregate
hashes (`build/classes/java/main`, `build/resources/main`) using my own
exact algorithm did NOT match the archived `runtime-byte-manifest.sha256`
in `M545-packet-4b6190b` (classes expected `78d1fb0a...`, his actual
`2f9fdf21...`; resources expected `0442a30c...`, his actual `b712cd92...`
- all 98 jar hashes matched). He explicitly instructed: investigate and
report the cause, do not merely overwrite the expected hashes.

### Investigation performed (no native run)

Re-ran the exact same algorithm against the LIVE worktree right now
(15:37 UTC): `build/classes/java/main` aggregate = `78d1fb0a...`,
`build/resources/main` aggregate = `0442a30c...` - BOTH match the
archived packet values, NOT John's reported "actual" values. A full
line-by-line diff of the entire 100-entry archived manifest against a
freshly regenerated one right now shows ZERO differences (`diff` exit
0). Checked file mtimes under both directories: the most recent file in
either directory is timestamped `2026-11-17T14:59:05Z`. Checked
`/tmp/div-gradle-slot.owner`: the last claim/release before this check is
`M545-packet-4b6190b-prep`, released at `2026-11-17T14:59:06Z` - the same
run that generated the archived manifest - and no other claim has
occurred since. Both independent signals (file mtimes, lock receipts)
agree: nothing has touched these two build-output directories since the
exact run that produced the archived manifest, and the live state right
now is still byte-identical to it.

**I cannot reproduce the mismatch John found.** I am not overwriting
anything, since there is nothing in my own repeated, receipt-backed
verification that disagrees with what's already archived. Reporting this
plainly rather than guessing at a cause I have not actually confirmed
(a transient read during some other, unlogged process; a difference in
exactly which path or moment was recalculated; a copy/paste error) - any
of these remain possible, but none are confirmed from my side. This is
reported as an unresolved discrepancy for the coordinator, not a fix.

### Raw receipts now archived (54650's own point: "currently only prose
found")

Added `receipts/` under `M545-packet-4b6190b` with real captured command
output, not prose summaries:
- `hash-mismatch-investigation-<ts>.txt` - the full diff, live
  recalculation, mtimes, and lock-receipt evidence above.
- `fresh-recheck-manifest-<ts>.sha256` - the complete freshly-regenerated
  100-entry manifest.
- `preflight-dry-run-<ts>.log` - a fresh full 9-check preflight.sh run
  against the real packet, raw stdout/stderr, `exit_code=0`.
- `negative-test-eula-tamper-<ts>.log`,
  `negative-test-world-baseline-tamper-<ts>.log`,
  `negative-test-runtime-manifest-tamper-<ts>.log`,
  `negative-test-pipefail-unreadable-file-<ts>.log` - raw output for four
  of the previously prose-only negative tests, all against disposable
  copies/synthetic fixtures (no live file touched), each ending
  `exit_code=1` with the correct `PREFLIGHT_FAILED`/
  `RUNTIME_MANIFEST_GENERATION_FAILED` reason.

The one negative test that DID require a live-shared-file mutation (the
`run/config/mtsconfig.json` drift test from the 54638 fix, already fully
reverted and reconfirmed byte-identical to baseline) is NOT repeated here
- John's 54650 explicitly said not to repeat it; future negative tests
stay on disposable fixtures only, as already done for all four tests
captured in this entry.

### Disposition

Hash-mismatch not reproduced after real investigation (mtimes + lock
receipts + full-manifest diff, all in the archived receipt file); nothing
overwritten. Raw receipts now archived for every preflight/negative-test
claim. No native run performed or requested. Native hold remains CLOSED.
Awaiting the coordinator's own account of how the discrepant hash was
obtained, or further instruction.

## Root cause of 54650's hash mismatch found and fixed per 54654: locale-dependent sort collation

John's 54654 found the actual root cause independently: `sort -k2`'s own
collation order is LOCALE-DEPENDENT. Reproducing the exact same pipeline
(`find . -type f -exec sha256sum {} + | sort -k2 | sha256sum`) against
the SAME files under `LC_ALL=C.UTF-8` gives one hash; under
`LC_ALL=en_US.UTF-8` gives a DIFFERENT hash (the one actually archived).
There was no file drift - the file mtimes I checked in the prior entry
were never proof of anything, since the function itself was
non-deterministic ACROSS ENVIRONMENTS, not across time. My own prior
investigation was itself incomplete: I confirmed internal consistency
(live recompute matched the archive, in my own shell, at that moment)
but never tested whether the ALGORITHM was deterministic independent of
ambient locale - the actual question John's finding answers.

### Fix

`compute_runtime_byte_manifest` (`runtime-manifest.sh`) now pins
`LC_ALL=en_US.UTF-8` explicitly on the `sort -k2` step itself, inside the
function - matching the already-archived manifest, confirmed installed
on this system (`en_US.utf8`). This is a stronger fix than relying on the
launch environment to remember to set `LC_ALL` (John's own suggested
mitigation in 54654): the function itself is now deterministic
regardless of the caller's ambient locale, so no future caller can
reintroduce this exact non-determinism by omitting an environment
variable. No baseline overwrite needed - the archived
`runtime-byte-manifest.sha256` values were already correct; only the
function's own determinism needed fixing.

Verified: ran the function three times, once under each of
`LC_ALL=C.UTF-8`, `LC_ALL=en_US.UTF-8`, and `LC_ALL=C` as the AMBIENT
shell locale - all three now produce output that matches the archived
manifest exactly (the function's own internal pin overrides whatever the
caller's environment happens to be). Re-ran the full 9-check
`preflight.sh` against the real packet with the fix installed and
explicit `LC_ALL=en_US.UTF-8` - still `PREFLIGHT_OK`, all 9 checks pass.

### Disposition

Root cause resolved per 54654/54655's own account - not a file-drift
bug, a locale-nondeterminism bug in my own hashing function, now fixed
at the source rather than relying on launch-environment convention.

## First real native measurement confirms the starvation mechanism; minimal DIV compatibility fix implemented per 54656

### The authorized run (14ada9b/4b6190b packet, John's 54655 release)

The coordinator's 54655 explicitly authorized exactly ONE native launch
of `M545-packet-4b6190b/launch-wrapper.sh` with `LC_ALL=en_US.UTF-8`.
Executed exactly once. Result:

- All 9 preflight checks `PASS` inside the lock (receipt:
  `receipts/launch-wrapper-run-20261117T160621Z.log`).
- Java launched (pid 792478), ran cleanly.
- Terminal: `[DIV buoyantHitchProbe] FAILED - lifecycleInvariantCheck
  withinUnavailableOutcome=FAIL_STARVED_TICK` at `TOWING_REPLACEMENT`
  tick=1 (the pre-connect snapshot, BEFORE `connectTrailer` runs) -
  exactly the bounded scope stated in the prior entry: this stops
  BEFORE the original tick=7/8 crash site, and is NOT a reproduction of
  that crash or proof of any solver-level cause.
- Orderly shutdown: "Stopping server", "All dimensions are saved", Java
  exit code 0, wrapper exit code 0, lock released
  (`RELEASED by M545-packet-4b6190b at 2026-11-17T16:07:40Z rc=0`).
- Post-run originals check (receipt:
  `receipts/post-run-originals-check-20261117T160800Z.txt`): the 8
  shared `run/config/*.*` files and the shared `run/world/level.dat`/
  `level.dat_old` are BYTE-IDENTICAL to the original baseline, confirmed
  AFTER the run, not just before. The isolated copy's own
  `server.properties` was rewritten by the server itself on startup
  (ordinary Minecraft behavior, confirmed benign: `level-seed=545547`
  and `level-name=world` both still correctly present) - not a
  protected "original," and not unexpected.

### The actual measured evidence (log lines 2005-2197, per John's own
54656 line citation)

`towed.stepInvocationCount` is logged as `201` on EVERY SINGLE tick of
the 60-tick `TOWING_UNAVAILABLE` window (ticks 1 through 60) - the exact
SAME value, never once advancing. The final `lifecycleInvariantCheck`
line makes this precise:
`firstSample=Sample[gameTime=204, stepInvocationCount=201, ...]`,
`lastWithinUnavailableSample=Sample[gameTime=263, stepInvocationCount=201, ...]`,
`postGapSample=Sample[gameTime=264, stepInvocationCount=201, ...]` - the
step counter is frozen at 201 across a full 60-gameTime span AND into
the first tick of the next phase. All three samples carry the IDENTICAL
`registryBodyIdentity` (`BuoyantBody@1bbbeb1e`) - the SAME live body
instance throughout, never swapped/removed/replaced (ruling out
`BODY_IDENTITY_CHANGED`/`BODY_ABSENT` as the explanation - this is a
confirmed, identity-verified `FAIL_STARVED_TICK` on a stable, still-
registered body). Every tick also logs
`towedConnection.towingVehicleIsRemovedTowing=true
towing.towingConnectionsStillContainsIt=true` - the stale connection to
the removed towing vehicle persists the entire window, and the removed
towing vehicle's own (dead, unreferenced) `towingConnections` list still
holds it. At `TOWING_REPLACEMENT`'s own reconnect, the post-connect
check confirms the real rewiring succeeded in both directions
(`towed.towedByConnectionIsNewConnection=true`,
`towing2.towingConnectionsContainsIt=true`).

**This is the first real, native, per-tick-granular measurement
confirming the starvation mechanism this investigation's source-level
reasoning predicted.** Prior entries called this "conditional, source-
supported" - that qualifier is no longer needed for the starvation
mechanism itself, which is now directly measured. It remains true, per
54656's own instruction, that this is NOT proof of the tick=7/8 crash's
own solver-level cause - that remains a separate, still-open question.

### Minimal DIV compatibility fix (John's 54656: "identify minimal
supported DIV compatibility seam... preserving native authority,
ordinary detach/reconnect and already-active fallback")

**Root mechanism**: `AEntityG_Towable.update()`'s own auto-disconnect
check (`if (towedByConnection!=null && !towedByConnection.towingVehicle.isValid)
disconnectTrailer(...)`) is the ONLY place MTS itself would ever clear a
stale connection - but `blockMainUpdateCall()` (`return
this.towedByConnection != null;`) prevents `update()` from EVER running
again once `towedByConnection` is non-null and the towing vehicle is
gone. This is circular: the one check that would fix the connection
lives on the exact method the stale connection itself blocks. A towed
vehicle can never break out of this on its own.

**Fix**: `PhysicsBridge.cleanup()` already runs every tick via
`NeoForge.EVENT_BUS... ServerTickEvent.Post` - independent of MTS's own
per-vehicle `update()`/`moveVehicle()` dispatch (confirmed by the run
itself: `registryBodyIdentity` stayed live and registered the entire
time, proving DIV's OWN registry/tick hooks were never the problem,
only MTS's dispatch reaching the entity). Added a new sweep at the start
of `cleanup()`, over `registry.forEachActive`, which on now-passed
`shouldReleaseOrphanedTowingConnection(towedByConnectionNonNull,
towingVehicleIsValid)` (a new pure boolean seam, same style as the
existing `deferToNativeHitch`/`unsafeBuoyantAngularLock`/
`compensableConnection`) sets `v.towedByConnection = null` directly -
the exact same single-field effect `disconnectTrailer` itself produces
on the TOWED side (the only side that matters, since the towing vehicle
is already invalid/removed and nothing ever reads its own dead
`towingConnections` list again).

The instant that field goes `null`, `blockMainUpdateCall()` returns
`false` and MTS's OWN ordinary main-loop dispatch resumes ticking the
vehicle normally on its very next tick - `VehicleMovementMixin.div$move`
-> `PhysicsBridge.step()` then runs through the SAME native path every
other vehicle uses, every tick, with NO new DIV-side physics-driving
mechanism and no change to MTS's own normal per-vehicle update/
moveVehicle flow for any vehicle whose towing relationship is intact.

**Constraints preserved** (all per 54656's own explicit list):
- *Native authority*: MTS's own `update()`/`moveVehicle()` dispatch is
  unmodified and untouched; this only clears a field MTS's own code
  would have cleared itself, from a call site MTS's own blocked dispatch
  can never reach.
- *Ordinary detach/reconnect*: untouched - the new check only fires when
  `towingVehicle.isValid` is `false`; a live, still-towing vehicle's own
  `isValid` stays `true`, so `shouldReleaseOrphanedTowingConnection`
  returns `false` and nothing changes for the ordinary case.
- *Already-active fallback*: untouched - `deferToNativeHitch`/
  `unsafeBuoyantAngularLock` never read `towingVehicle.isValid` at all;
  this new check is a completely independent condition.
- *No arbitrary damping/physics masking*: this clears a stale reference,
  it does not touch velocity/force/any physics quantity.

**Removal-vs-tick-hook assessment** (54656's own "assess applicable
removal vs tick hook and ordering/reentrancy"): considered adding a NEW
mixin injecting into `AEntityA_Base.remove()` (or
`EntityManager.removeEntity`) to proactively disconnect BEFORE removal
completes. Rejected as a larger, riskier change: DIV has no existing
removal-event hook anywhere today (confirmed via grep - DIV's own
cleanup idiom is exclusively POLLING sweeps against `!v.isValid`, e.g.
the existing `handbrakeMagnitude.keySet().removeIf(v -> !v.isValid)`
lines already in `cleanup()`), so a removal-time mixin would be a NEW
mechanism/pattern, not the smallest supported seam, and would need its
own reentrancy analysis against MTS's own `remove()` internals (unknown,
undecompiled risk). The tick-hook fix instead reuses an EXISTING,
already-reliable, already-running sweep and matches DIV's own
established idiom exactly - genuinely the smaller, lower-risk patch.
Re-entrancy: `cleanup()`'s new sweep only ever WRITES
`v.towedByConnection`, reads `v.towedByConnection.towingVehicle.isValid`
(a plain boolean field read, no native call), and never touches Sable/
native state - no reentrancy into `BuoyantBody.step()`/`BuoyantForces`/
the hitch joint at all.

### Verification performed (no additional native run)

Compiled and ran the full test suite under the exclusive
`/tmp/div-gradle-slot.lock`
(`MINECRAFT-545-fix-orphan-connection-20261117T161331Z`,
`RESULT=PASS`). Two new focused tests added to
`PhysicsBridgeClassificationTest`:
`releasesAnOrphanedConnectionOnlyWhenTowingVehicleIsInvalid` and
`neverReleasesWhenThereIsNoConnectionAtAll`, following the exact style
of the existing `deferToNativeHitch`/`unsafeBuoyantAngularLock` pure-
boolean tests - no live MTS object construction needed.

### Disposition

Starvation mechanism now directly measured by a real native run, not
merely source-level reasoned. Minimal DIV-side compatibility fix
implemented, compiled, and pure-JUnit-tested per 54656's own explicit
constraints (native authority, ordinary detach/reconnect, already-active
fallback, no damping/masking). NOT yet validated against a real native
run - the native grant from 54655/54656 is consumed and 54656 explicitly
holds further native execution. Whether this fix actually resolves the
starvation (and whether that, in turn, is sufficient to prevent the
original tick=7/8 crash - still a separate, unconfirmed question) remain
open until a future authorized run. "Erosion hold" referenced in 54655/
54656 is unrelated to this ticket's own scope and is left untouched.

## Corrections per 54659: real disconnectTrailer semantics, correct condition, stale-index safety

John's independent readback of commit `55f8bb5` found, via a FRESH decompile of the exact
pinned jar (hash `1d2f7512604636265703b29fdf13d7f8ef2323694b6435f4392973da6e78d251`,
re-verified against the live `.gradle` cache copy before trusting it), two real errors in that
commit's own claims - not merely style, actual incorrect behavior and an unverified
equivalence claim. Re-decompiled `AEntityG_Towable.class`/`TowingConnection.class` directly
myself to confirm every point below byte-for-byte, rather than trust the prior paraphrase.

### Error 1 - wrong condition field

The prior fix checked `towedByConnection.towingVehicle.isValid`. The REAL
`AEntityG_Towable.update()` auto-disconnect check is:
```java
if (this.towedByConnection != null && !this.towedByConnection.towingEntity.isValid) {
    this.towedByConnection.towingVehicle.disconnectTrailer(
      this.towedByConnection.towingVehicle.towingConnections.indexOf(this.towedByConnection));
}
```
- checks `towingEntity`, NOT `towingVehicle`. `TowingConnection.towingEntity` is the REAL anchor
(`AEntityE_Interactable<?>` - either the vehicle itself or an installed PART on it);
`towingVehicle` is always resolved to the containing vehicle regardless (confirmed via
`TowingConnection`'s own constructor/`initConnection`: `towingVehicle = hitchEntity instanceof
APart ? ((APart)hitchEntity).vehicleOn : (EntityVehicleF_Physics)hitchEntity`). These are the
SAME object only when the connection is anchored directly to the vehicle (exactly the probe's
own scenario, which is why the bug was invisible there) - for an installed-part anchor
(MINECRAFT-530's own case), a part's own validity can differ from its vehicle's, and checking
the vehicle instead of the real anchor would be wrong in that case.

### Error 2 - unverified equivalence claim; disconnectTrailer does far more than a field null

The prior fix directly set `v.towedByConnection = null`, claiming this was "the exact same
single-field effect disconnectTrailer itself produces." That claim was never actually verified
against the real method body, and was wrong. The real `disconnectTrailer(int)`:
```java
public void disconnectTrailer(int connectionIndex) {
    TowingConnection connection = this.towingConnections.remove(connectionIndex);
    connection.towedVehicle.towedByConnection = null;
    if (connection.hitchConnectionGroup.isSnap || connection.hookupConnectionGroup.isSnap) {
        this.disconnectedTowingConnections.add(connection);
    } else {
        connection.towingEntity.connectionGroupsIndexesInUse.remove(connection.hitchGroupIndex);
        connection.towedEntity.connectionGroupsIndexesInUse.remove(connection.hookupGroupIndex);
    }
    this.resetAllVariables();
    connection.towedVehicle.resetAllVariables();
    connection.towedVehicle.parkingBrakeVar.setTo(1.0, false);
    if (!this.world.isClient()) {
        InterfaceManager.packetInterface.sendToAllClients(new PacketEntityTowingChange(this, connectionIndex));
    } ...
}
```
Removes the PARENT's own list entry; branches into EITHER the snap-disconnect bookkeeping list
OR freeing BOTH sides' `connectionGroupsIndexesInUse`; resets BOTH vehicles' variables; sets the
CHILD's `parkingBrakeVar` to `1.0` (engages parking brake on detach); sends a
`PacketEntityTowingChange` to all clients. A direct field null skips every one of these.

### Fix (corrected)

Rewrote `PhysicsBridge.cleanup()`'s new sweep to call the REAL `disconnectTrailer`, exactly
mirroring `update()`'s own call - same target (`towing = connection.towingVehicle`), same index
computation (`towing.towingConnections.indexOf(connection)`), same method - only the CALL SITE
moves (to `cleanup()`'s own already-reliable independent sweep, since `update()`'s own call site
is permanently blocked for an orphaned towed vehicle). Calling `disconnectTrailer` on an
already-invalid/removed towing entity is not a new risk - MTS's own `update()` already does
exactly that in the ordinary (ungated) case, since the entity that just went invalid is the one
`update()` calls `disconnectTrailer` ON.

**Stale index** (54659's own flagged hazard): `List.remove(int)` throws
`IndexOutOfBoundsException` for a negative index; `indexOf` returns `-1` if the connection is
not actually present in `towingVehicle`'s own list. New pure boundary check
`canSafelyDisconnectAtIndex(int)` guards this - on a negative index, falls back to a direct
`towedByConnection = null` (restores exactly-once dispatch even though the fuller bookkeeping
cannot run without a valid index; explicitly documented as a REDUCED, not equivalent, recovery
for this one edge case, not claimed otherwise this time).

**Reentrancy** (54659's own explicit ask): `BodyRegistry.forEachActive` is a direct
`Map.forEach`, which throws `ConcurrentModificationException` on structural modification during
iteration. Confirmed via grep that no DIV mixin hooks fire synchronously from
`disconnectTrailer`/`resetAllVariables`/the client packet's own call chain (DIV's only mixins
are on `moveVehicle()`/sound) - but rather than rely on that alone, the sweep now collects every
orphaned vehicle FIRST (read-only, during `forEachActive`), then performs every actual
`disconnectTrailer`/fallback call AFTER `forEachActive` has fully returned - the registry is
never touched while its own iteration is live, regardless of what `disconnectTrailer`'s call
chain does.

### Test coverage added (54659: "not predicate-only truth table")

Renamed the existing boolean-condition test for accuracy
(`releasesAnOrphanedConnectionOnlyWhenTowingEntityIsInvalid`, was "...TowingVehicle..."). Added
`canOnlySafelyDisconnectAtANonNegativeIndex` covering the exact `-1`/`0`/positive boundary the
production code now branches on. A full production-orchestration test (constructing real
`TowingConnection`/`EntityVehicleF_Physics` objects and exercising the actual `cleanup()` call
path) remains impractical without a live `ServerLevel` - confirmed earlier this investigation
(`neoForge.unitTest` gives real MTS classes on the test classpath for bare-object construction,
but not a live ticking dispatch chain) - so coverage stays at the pure-boundary level, same
style as `deferToNativeHitch`/`unsafeBuoyantAngularLock`, not a reimplementation of the full
call path. The retained native lifecycle fixture (`BuoyantHitchProbe`) remains the path to an
eventual independently-reviewed, real-orchestration validation.

### Verification performed (no native run)

Full compile+test under the exclusive `/tmp/div-gradle-slot.lock`
(`MINECRAFT-545-fix-real-disconnect-20261117T164549Z`, `RESULT=PASS`) - confirms
`connection.towingEntity.uniqueUUID`/`.connectionGroupsIndexesInUse` etc. are all real,
accessible fields on the actual decompiled types, not assumed. Fresh decompile of
`TowingConnection.class`/`AEntityG_Towable.class` performed directly this entry (not reused from
an earlier session's paraphrase) to confirm every claim above byte-for-byte against the pinned
jar hash.

### Disposition

Both concrete errors from 54659 fixed: correct condition field, real `disconnectTrailer` call
(full bookkeeping preserved) instead of an unverified partial reimplementation, explicit
stale-index guard, and a two-phase collect-then-disconnect pattern removing any reentrancy risk
against `forEachActive`'s own iteration. No native run performed or requested - this corrected
fix remains native-unvalidated, same as before.

## Real orchestration coverage and a race guard added per 54662

John's 54662 confirmed the 54659 correction (ed6c184) as a meaningful fix, and asked for three
more things, all addressed in this entry: real orchestration test coverage (not just predicate
truth tables), a re-check guard against a real race between collection and dispatch, and trimmed
documentation focused on the actual invariant rather than this investigation's own history.

### Race guard: re-check before dispatch, not just before collection

54662 found a real gap: the prior two-phase sweep (collect orphaned candidates, then dispatch)
trusted its own phase-1 snapshot unconditionally in phase 2. If some effect between collection
and dispatch changed a candidate's own connection - a replacement, a reconnect, or (for two
vehicles sharing the same towing parent) an earlier candidate's own `disconnectTrailer` call
shifting the parent's own list - the stale snapshot could cause a now-valid or already-changed
connection to be disconnected anyway. Fixed by re-checking `hasConnection`/`anchorIsValid` FRESH,
immediately before acting on each candidate in the dispatch phase, skipping it entirely if either
no longer holds.

### Real orchestration seam extracted, not duplicated

Extracted `PhysicsBridge.releaseOrphanedConnections(Iterable<V>, OrphanedConnectionOps<V>)` - the
REAL collect-then-dispatch-with-re-check orchestration `cleanup()`'s own sweep now calls, generic
over `V` so a test can supply lightweight fakes instead of live MTS objects, per 54662's own
explicit instruction ("no duplicate test-only algorithm"). `cleanup()` itself is now a thin
adapter binding this generic orchestration to the real `BodyRegistry`/`EntityVehicleF_Physics`/
`TowingConnection` types.

Five new tests added to `PhysicsBridgeClassificationTest`, using a minimal fake candidate type
(not duplicating the real algorithm, only standing in for its three mutable facts):
- `eligibleInvalidAnchorConnectionInvokesDisconnectExactlyOnceAtItsRealIndex`
- `validOrMissingConnectionIsNeverTouched`
- `repeatedSweepIsIdempotent`
- `missingIndexNeverInvokesDisconnectAndUsesTheHonestlyReducedFallback`
- `aCandidateWhoseAnchorBecomesValidBeforeDispatchIsNeverDisconnected` (the new race-guard case
  - directly exercises the fix above)

A full test constructing REAL `TowingConnection`/`EntityVehicleF_Physics` objects remains
impractical without a live `ServerLevel`, confirmed earlier this investigation; the generic
orchestration above is the closest practical substitute, exercising the actual control flow
rather than only its boolean preconditions.

### Documentation trimmed to the actual invariant

Per 54662's own explicit point ("focus comments on actual invariant, not conversational
history"), trimmed the large narrative comment blocks in `PhysicsBridge.java` (repeated
"CORRECTED (54659)"/"CORRECTED (54662)" framing, session-specific phrasing like "confirmed this
session") down to the current invariant and behavior only - the corrections' own history stays in
VERIFICATION.md, not repeated in-code. Also removed an unhedged claim ("the instant this field
goes null... resumes ticking this vehicle normally on its very next tick") that stated an
expected effect as an already-proven fact; this fix's actual effect on dispatch remains
unconfirmed until a future native run, not asserted as settled.

### Verification performed (no native run)

Full compile+test under the exclusive lock twice - once for the orchestration extraction/race
guard (`MINECRAFT-545-fix-orchestration2-20261117T170553Z`, `RESULT=PASS`, 29 classification
tests/0 failures) and once after the comment trim
(`MINECRAFT-545-trim-comments-20261117T170925Z`, `RESULT=PASS`) - confirms the trim introduced no
behavior change.

### Disposition

All three asks from 54662 addressed: race guard implemented and tested, real orchestration
coverage added (lightweight fakes, not a duplicate algorithm), documentation trimmed to the
actual invariant. No native run performed or requested. Per 54662's own closing instruction, an
updated isolated lifecycle packet follows in the next entry - prepared only, no launch until the
parent explicitly releases a further run.

## Updated isolated lifecycle packet prepared (NOT executed) per 54662

Packet `M545-packet-435937b` prepared against the current commit (`435937b`, the
orchestration/race-guard/comment-trim fix), using the SAME proven `launch-wrapper.sh`/
`preflight.sh`/`runtime-manifest.sh` (the locale-fixed, 9-check version), same fixed seed
(`level-seed=545547`), same `level-name=world`. Regenerated `build/moddev/runServer.sh`/
`serverRunClasspath.txt`/`serverRunVmArgs.txt`/`serverRunProgramArgs.txt` under the exclusive
lock; confirmed `runServer.sh`'s own `cd` argument points at this packet's own rundir.
`runtime-byte-manifest.sha256` (100 entries) computed with the locale-pinned function. Isolated
`config`/`eula.txt`/`server.properties` copied with `level-seed=545547` set only there (exactly
one line differs via `diff`); isolated config confirmed byte-identical to the original, never-
mutated reference run's own baseline - no DIV config drift. Shared `run/world/level.dat`/
`level.dat_old` confirmed unchanged against the same retained baseline.

Dry-ran the full 9-check `preflight.sh` directly against this real packet - all 9 checks
`OK`/`PASS`, exit 0. Confirmed `rundir/world` does not exist - NOT executed, no java process
launched under this packet's own tag. Retained worlds (the original reference run, both prior
authorized-run packets) are untouched - this packet's own preparation never reads or writes
anything under another packet's directory.

### Disposition

Updated packet ready, per 54662's own instruction - prepared only. NO launch until the
coordinator/parent explicitly releases a further reviewed run. Native hold remains CLOSED.

## Compiled-byte drift explained per 54665/54668/54669: comment-only source change, evidence captured

John's 54665 asked for a precise terminology correction; implementing it required editing
`PhysicsBridge.java` again AFTER `M545-packet-435937b` had already been prepared and hashed -
changing the live compiled bytes out from under that already-archived packet. 54668 caught this
exact drift independently (reviewing the packet mid-flight) and correctly blocked any launch;
54669 asked for the exact evidence explaining the change, a fresh packet, and no further launch
until that new packet is itself reviewed and released.

### Exact change and evidence

Production diff (`PhysicsBridge.java`): ONE six-line javadoc paragraph added to
`releaseOrphanedConnections`'s own doc comment (the "current-eligibility check, not an identity-
snapshot guard" clarification John's 54665 asked for) - no other production line touched. Test
diff (`PhysicsBridgeClassificationTest.java`): functional - captures the actual received index
in `disconnectAtIndex` and asserts it (54665's own "does not assert received index" finding);
corrects the race-guard test's own description to match 54665's current-eligibility
clarification; adds `aCandidateWhoseConnectionChangedButIsStillInvalidRemainsEligible` (54665's
own "no test currently mutates candidate collection during disconnect" finding).

Captured concrete before/after evidence rather than asserting equivalence without it (54669's
own explicit instruction: "do not claim binary equivalence without evidence"): stashed the
current changes, recompiled to get the OLD `PhysicsBridge.class`, saved a copy, restored the
changes, recompiled again for the NEW `PhysicsBridge.class`. `javap -p -c` (bytecode
instructions only, no debug info) on both is BYTE-IDENTICAL (`diff` empty). `javap -p -c -l`
(bytecode + line-number table) differs in exactly one place: every `LineNumberTable` entry at or
after the edited method shifts by exactly 6 - precisely the length of the added javadoc
paragraph. This is `javac`'s own default debug-info behavior (source line numbers embedded for
the debugger), not a behavioral change - the actual bytecode instructions are unchanged, which is
why the aggregate SHA256 of `build/classes/java/main` changed despite no logic change: the hash
is over raw file bytes, and `LineNumberTable` entries are real bytes in the `.class` file even
though they affect no runtime behavior.

### Packet 435937b preserved unchanged

Per 54669's own explicit instruction, `M545-packet-435937b` and its own archived
`runtime-byte-manifest.sha256` were NOT touched, refreshed, or used for any launch - the drift
this entry explains is real, and the old packet now correctly reflects a stale, pre-edit build.
A separate, freshly-named packet follows in the next entry, prepared against the new commit
these changes are part of.

### Disposition

Comment-only production change, functional test change, both explained with direct before/after
bytecode evidence rather than an unverified equivalence claim. Old packet preserved untouched.
No further terminology/refactor churn after this commit, per 54669's own explicit instruction.
No native run performed. A new packet follows, not yet reviewed or released.

## New packet prepared per 54669 (M545-packet-df4b0c2) - not reviewed, not released

Fresh, separately-named packet prepared against commit `df4b0c2` (the terminology/test-coverage
fix with class-diff evidence, previous entry). Same proven `launch-wrapper.sh`/`preflight.sh`/
locale-fixed `runtime-manifest.sh`, same fixed seed (`level-seed=545547`), same
`level-name=world`. Regenerated `build/moddev/runServer.sh`/`serverRunClasspath.txt`/
`serverRunVmArgs.txt`/`serverRunProgramArgs.txt` under the exclusive lock; confirmed
`runServer.sh`'s own `cd` argument points at this packet's own rundir.
`runtime-byte-manifest.sha256` (100 entries) computed fresh against the new compiled bytes.
Isolated `config`/`eula.txt`/`server.properties` copied with `level-seed=545547` set only there
(exactly one line differs); isolated config confirmed byte-identical to the original, never-
mutated reference run's own baseline. Shared `run/world/level.dat`/`level.dat_old` confirmed
unchanged against the same retained baseline.

Dry-ran the full 9-check `preflight.sh` directly against this real packet - all 9 checks
`OK`/`PASS`, exit 0. Confirmed `rundir/world` does not exist - NOT executed.

Confirmed `M545-packet-435937b` (the now-stale prior packet) was NOT touched during this
preparation: its own `runtime-byte-manifest.sha256` file mtime is unchanged from its own
original preparation; no new files were added to it beyond the one legitimate dry-run receipt
already captured in the prior entry, before the drift occurred.

### Disposition

Separate, uniquely-named packet `M545-packet-df4b0c2` ready, reflecting the current reviewed
source/test state. NOT reviewed or released - no launch until the coordinator explicitly
reviews and releases this specific packet, per 54669's own instruction. The prior packet
(`M545-packet-435937b`) remains preserved, unmodified, and not used for any launch. No further
terminology/refactor churn pending that review, per 54669's own closing instruction. No native
run performed. Native hold remains CLOSED.

## Second authorized run (M545-packet-df4b0c2): dispatch confirmed restored, a NEW divergence
observed at TOWING_UNAVAILABLE tick=10 - read-only accounting, per John's 54672/54674

### The run

Executed exactly once, per John's 54672 explicit single-use release of
`M545-packet-df4b0c2/launch-wrapper.sh` (`LC_ALL=en_US.UTF-8`). All 9 preflight checks `PASS`
inside the lock. Java launched (pid 983812), ran, exited cleanly (code 0), "All dimensions are
saved", lock released (`RELEASED by M545-packet-df4b0c2 at 2026-11-17T17:45:53Z rc=0`). Post-run
check (receipt `receipts/post-run-originals-and-evidence-20261117T174600Z.txt`): the 8 shared
config files and 2 shared world files remain byte-identical to baseline.

Per John's own 54674 explicit correction: **`rc=0` is the wrapper's own clean-shutdown exit
code, not a probe PASS verdict** - the fixture's own terminal line is
`FAILED - left bounded area tag=towingUnavailable/towed phase=TOWING_UNAVAILABLE tick=10
horizontal=59.09 vertical=20.15`. This is NOT the lifecycle assertion from the first authorized
run, and NOT the original `TOWING_REPLACEMENT` tick=7/8 crash - the fixture never reached
`TOWING_REPLACEMENT` at all this time. A new, earlier failure point, via the pre-existing
`boundedOrFail` check.

### What is actually confirmed: the orphan-connection fix restores exactly-once dispatch

Log evidence, in exact tick order (gameTime in parens where logged):
- tick=1: `towed.stepInvocationCount=201` (unchanged - same frozen value every prior run showed).
- Immediately after tick=1's own lines: `cleanup releasing orphaned towedByConnection
  v=fe486389... removedTowingEntity=a5294719... indexInParentList=0` - the real
  `disconnectTrailer(0)` call fired, at a real, valid index (not the missing-index fallback).
- tick=2: `towedByConnectionNonNull=false`, `towingConnectionsStillContainsIt=false` - the real
  disconnect's own effect on BOTH sides, confirmed directly (child pointer null, parent list
  entry actually removed) - not inferred. `stepInvocationCount` still 201 (cleanup runs on
  `ServerTickEvent.Post`, after this tick's own entity-ticking pass already completed, so the
  very next step happens on the FOLLOWING tick).
- tick=3: `stepInvocationCount=202` (advanced by exactly 1 - dispatch resumed).
- ticks 3 through 9: `stepInvocationCount` advances by EXACTLY 1 every tick (202→203→...→208,
  `lastPoseUpdateGameTime` 205→...→211, matching one-for-one) - genuinely exactly-once dispatch,
  not starved, not duplicated, for as many ticks as the fixture got to observe before its own
  unrelated bounded-area check fired.

This is the first direct confirmation that the fix's own stated goal - restoring MTS's ordinary
per-tick dispatch to an orphaned towed vehicle - actually happens, for a real native body, under
the real native dispatch chain. It is NOT, per John's own correction, proof of "complete
lifecycle/authority acceptance" - only of dispatch resuming over this specific bracket.

### What is also observed, NOT yet explained: a severe velocity/torque divergence at the
SECOND resumed step specifically

Velocity magnitudes (computed from the logged linear/angular velocity vectors):

| tick | stepInvocationCount | \|linearVelocity\| | \|angularVelocity\| |
|------|---------------------|---------------------|----------------------|
| 1    | 201 (frozen)        | 7.45                | 34.16                |
| 2    | 201 (frozen)        | 2.83                | 23.63                |
| 3    | 202 (1st resumed step) | 15.61            | 82.83                |
| 4    | 203 (2nd resumed step) | 199.43           | 1244.13              |
| 5-9  | 204-208             | ~199 (slow decay)   | ~1244 (near-constant) |

The jump from tick=3 to tick=4 (step 202→203) is a ~13x increase in linear velocity magnitude
and a ~15x increase in angular velocity magnitude, in a SINGLE step - not a gradual divergence.
The FIRST resumed step (201→202, tick 2→3) only moderately changes velocity (7.45→15.61 linear,
23.63→82.83 angular - itself already a real increase, but nowhere near the magnitude of the
second step's own jump). From tick=4 onward, velocity stays near-constant at this extreme
magnitude (consistent with the body now being essentially in unconstrained free-flight,
gravity/drag only, no longer meaningfully coupled to buoyancy); the resulting ~199 blocks/s
linear speed over the ~6 remaining ticks (at 20 ticks/s) accounts for the ~59m horizontal
displacement the bounded-area check measured at tick=10, consistent kinematically with a single
instability event at step 203 rather than a compounding multi-step drift.

### Explicitly NOT claimed

- NOT claimed: that this is the same mechanism as the original `TOWING_REPLACEMENT` tick=7/8
  crash. The kinematic SIGNATURE (explosive divergence within a handful of resumed steps,
  following a period of accumulated-but-uncorrected angular velocity) is qualitatively similar
  to this investigation's own earlier description of that crash ("settled anchor, then 24-35
  block divergence within 5-10 ticks") - but similarity in signature is not identity of cause,
  and this occurred in a different phase (`TOWING_UNAVAILABLE`, not `TOWING_REPLACEMENT`), via a
  different precipitating event (a cleanup-driven disconnect, not a `createHitch` reconnect).
- NOT claimed: a specific root cause for WHY step 203 specifically produces this jump (vs. step
  202, which did not). The leading, UNPROVEN hypothesis is that `BuoyantForces.apply()`'s own
  restoring-torque computation is numerically unstable once angular velocity is already large
  (33.71 rad/s backward-starvation-accumulated magnitude at disconnect, growing further at the
  first resumed step to 82.83 before exploding at the second) - plausible given the ~15x
  per-step growth ratio (consistent with positive feedback / insufficient damping relative to
  the fixed physics timestep), but NOT verified against the actual force/torque values
  `BuoyantForces.apply()` computed at that step (not captured at that granularity in this run's
  own log output).
- NOT claimed: that `disconnectTrailer`'s own `parkingBrakeVar.setTo(1.0, ...)` and
  `resetAllVariables()` side effects had any bearing here. Per-tick velocity readings are
  unaffected across the tick where the real disconnect ran (tick 1→2 velocity values are
  unchanged), consistent with (but not direct proof of) a float-only hull's `BuoyantForces`
  having no brake-force model that would react to `parkingBrakeVar` at all - not independently
  instrumented/confirmed in this run's own log output.

### Next falsifiable step (no native run required)

Source-level review of `BuoyantForces.apply()`'s own restoring-torque/damping formula, checking
specifically for a term that could grow super-linearly with angular velocity magnitude or that
lacks a stability margin for a multi-tick-accumulated angular velocity this large - a concrete,
checkable claim distinct from "exactly-once dispatch is the whole fix." If a future native run
is ever authorized, bracketing ticks 2-4 of this exact scenario with a gated, per-float
force/torque diagnostic (same style as the existing `GroundContactDiagnostics`-gated per-float
logging already in `BuoyantForces.java`) would directly capture the computed force/torque at the
step that actually produces the jump, rather than only its resulting velocity.

### Disposition

Dispatch-restoration goal of the orphan-connection fix CONFIRMED by direct native measurement.
A real, severe, and currently unexplained velocity/torque divergence observed at the second
resumed step - reported as a measured, open finding, not a proven root cause and not evidence
against the fix itself (which did exactly what it was designed to do: restore per-tick
dispatch). One grant consumed; native hold CLOSED again. No retry/substitute performed or
requested.

### Supplementary source-level check (no native run): rotational damping is OFF for this scenario

Checked whether the leading hypothesis above (a numerically unstable restoring torque with no
damping) is even POSSIBLE for this specific probe configuration, before treating it as worth
further pursuit. `BuoyantForces.java`'s own rotational water-drag term (`tuning.angularWaterDrag`)
is explicitly gated off when zero (`angularSpeed > 1e-6 && tuning.angularWaterDrag > 0`), and its
global fallback default is `0.0` (`Overrides.java`, `FALLBACK.angularWaterDrag=0.0`). Confirmed
via grep that `BuoyantHitchProbe.buildHullDefinition` (used for every hull in this fixture, towed
and towing alike) never sets `angularWaterDrag` anywhere. So for this exact run, rotational water
drag was OFF - the per-float righting torque was the ONLY angular force/torque source active,
with zero dedicated damping term to counteract it. This does not prove the restoring torque
itself is unstable, but it does confirm the specific damping mechanism that WOULD counteract a
large angular velocity was never active for this scenario - a concrete, checkable precondition
for the hypothesis above, not yet a conclusion about the torque formula's own stability.

## Corrections per 54677: two wrong claims retracted; numerically confirmed drag-impulse overshoot

John's 54677 found two real errors in the prior entry's own claims, and asked for a concrete
numerical investigation using the actual retained per-float data rather than hand-waving.

### Retraction 1 - the per-float diagnostic data WAS captured

The prior entry claimed the force/torque operands "were not captured at that granularity in
this run's own log output." That was WRONG - re-read the actual retained log directly (lines
2017/18 at gameTime=205, 2024/25 at gameTime=206, confirmed by line number): both floats'
`worldArm`, `buoyancyOnlyForce`, `dragOnlyForce`, and `appliedImpulse` are logged for every tick,
via the EXISTING `GroundContactDiagnostics`-gated per-float diagnostic in `BuoyantForces.java`
(lines 168-177) - which was active for this run. I did not check the log thoroughly enough
before claiming the data was missing.

### Retraction 2 - directional drag DOES contribute torque; the "only angular contribution"
claim and the tick1→2 "unchanged velocities" claim were both wrong

The prior entry claimed the per-float righting (buoyancy) torque was "the ONLY angular force/
torque source active" since dedicated rotational water drag (`tuning.angularWaterDrag`) was
confirmed off. WRONG - `BuoyantForces.java`'s own class/method doc already states this directly
("today a floating hull gets a righting torque only as an emergent side effect of `drag`
applied at each float device's own off-center position" - `Buoyancy.java`'s own
`angularDragRetention` doc). The per-float DIRECTIONAL DRAG force (lines 139-144) is applied at
the SAME off-center `arm` as buoyancy (`handle.applyImpulseAtPoint(arm, force*DT)`, line 178) -
any off-center force contributes torque via the moment arm, regardless of whether a DEDICATED
rotational damping term exists. The logged magnitudes confirm drag is actually the DOMINANT
angular contributor here, not a negligible one: at gameTime=206, float 2's own
`buoyancyOnlyForce=(0,3532,0)` vs `dragOnlyForce=(-4.996E5,1.219E6,-7.895E5)` - drag outweighs
buoyancy by roughly 2-3 orders of magnitude at this velocity.

Also retracted: the claim that tick1→2 velocity was "unchanged" (cited as evidence
`parkingBrakeVar`/`resetAllVariables` had no effect). Re-checked the actual logged values:
tick1 `linearVelocity=(5.462,4.410,-2.499)` vs tick2 `linearVelocity=(-0.2356,2.791,0.4222)` -
these ARE different. The value that stayed unchanged between tick1 and tick2 was
`stepInvocationCount` (201 both times, since `cleanup()`'s own disconnect runs AFTER that
tick's entity-ticking pass, so the next real step happens the FOLLOWING tick) - I conflated the
two observations. The `parkingBrakeVar`/`resetAllVariables` question remains genuinely
unconfirmed either way (no direct log evidence either way), not supported by the velocity
values as previously (wrongly) claimed.

### Numerical confirmation: the per-float drag impulse overshoots at the exploding step

Using ONLY the real retained values (this run's own logged entry velocity/angular velocity/
worldArm/dragOnlyForce, and this fixture's own real configured `massKg=400`,
`waterDrag=400` fallback, `PhysicsBridge.DT=0.05`):

Reconstructed each float's own LOCAL point-velocity (`angular × arm + linearVelocity`, the
exact quantity `BuoyantForces.apply` itself computes at line 122) from the logged entry
velocity/angular-velocity vectors and `worldArm`:

| gameTime | float | \|localVelocity\| (point speed) | \|dragOnlyForce\| (logged) | predicted \|dragOnlyForce\| (`fraction*waterDrag*speed²`) |
|----------|-------|----------------------------------|------------------------------|----------------------------------------------------------|
| 205      | 1     | 16.66                            | (not independently re-verified here) | -                                                   |
| 206      | 1     | 43.90                            | 771,300 (`sqrt(sum of squares)`) | 770,880 (`1.0 * 400 * 43.90²`)                        |
| 206      | 2     | 61.98                            | (large; see log)             | -                                                          |

The predicted magnitude (`fraction * waterDrag * speed²`, derived purely from this fixture's own
real configured coefficient and the independently-reconstructed point speed) matches the
logged `dragOnlyForce` magnitude at gameTime=206/float1 to within 0.05% - confirming the
reconstruction (entry velocity + worldArm → local point velocity → predicted drag magnitude) is
numerically correct, not merely plausible.

Using that same local point velocity and the logged `dragOnlyForce`/`DT`/`mass`, the single-step
impulse-induced velocity CHANGE at that float's own point:
- gameTime=206, float 1: `|localVelocity|≈43.90`; impulse-induced `|ΔV|≈96.40` - **the impulse
  overshoots the local velocity it was meant to cancel by ~2.2x**, reversing its direction with
  more than double the original magnitude in a single discrete step.
- gameTime=206, float 2: `|localVelocity|≈61.98`; impulse-induced `|ΔV|≈191.97` - **overshoots
  by ~3.1x**, an even larger single-step reversal.
- gameTime=205, float 1 (the immediately PRECEDING, non-diverging step): `|localVelocity|≈16.66`;
  impulse-induced `|ΔV|≈10.26` - does NOT overshoot (stays below the local velocity it opposes) -
  a direct contrast confirming overshoot is a property of the velocity magnitude already
  reached, not an unconditional defect in the formula.

This matches `BuoyantForces.java`'s own ALREADY-DOCUMENTED review finding (comment 53679, about
the SEPARATE dedicated rotational-drag term) applied here to the per-float LINEAR drag term
instead: "applying this as an inertia-converted impulse could overshoot past zero and reverse/
amplify the hull's spin for a light hull and a coefficient within this field's own valid range."
The per-float drag force is quadratic in speed (`Buoyancy.directionalDragForce`,
`-fraction*coefficient*multiplier*speed*axisVelocityComponent`); once speed is already large
(from the preceding resumed step), the FULL-DT impulse this produces can exceed what is needed
to simply cancel the local velocity, overshooting past zero and - because it is applied off-
center via `arm` - feeding directly into angular velocity, which then increases `localVelocity`
again next step (`angular × arm` grows), compounding the effect. This is a coherent, numerically
grounded explanation for the observed explosive divergence, though NOT yet a proof that this
specific mechanism is identical to the original `TOWING_REPLACEMENT` tick=7/8 crash (per the
prior entry's own, still-standing caveat).

### Regression test added (54677: "a small production-used numerical regression demonstrating
any discrete drag overshoot")

`BuoyancyTest.directionalDragOvershootsAtTheExactReconnectDivergenceIncident` - uses the REAL
production `Buoyancy.directionalDragForce` function directly (no reimplementation), the real
`massKg=400`/`waterDrag=400`/`DT=0.05` values, and the gameTime=205/206 point-velocity
magnitudes reconstructed above, asserting the exploding step's own impulse-induced velocity
change exceeds its own local velocity (overshoot) while the immediately preceding, non-
diverging step's does not - a direct contrast pinned to this exact incident, not a synthetic
worst case. Compiled and ran under the exclusive lock
(`MINECRAFT-545-overshoot-test-20261117T181046Z`, `RESULT=PASS`).

### What remains open

- Whether this exact mechanism (per-float linear drag impulse overshoot) is the same cause as
  the original `TOWING_REPLACEMENT` tick=7/8 crash - not established, only a plausible,
  numerically-supported candidate for THIS run's own divergence.
- Whether the coupled rotational-inertia response (this analysis treated each float's point-
  velocity change as if purely translational, not accounting for how the body's own moment of
  inertia redistributes the applied torque back into angular velocity) changes the overshoot
  ratio materially - the point-level check above is a valid, direct measure of whether the FORCE
  ITSELF is dissipative-at-that-point or not, but is not a full rigid-body-dynamics simulation.
- No fix proposed yet. Per John's own explicit instruction: no arbitrary damping/caps/resets, no
  weakening of the bounded-area check, and no native run - this entry is read-only numerical
  accounting of already-retained evidence.

### Disposition

Two wrong claims retracted with their correct replacements. A concrete, numerically-verified
(not hand-waved) overshoot demonstrated using only real captured/configured values and the real
production drag formula, with a passing regression test pinned to this exact incident. No
native run performed or requested. Native hold remains CLOSED.

## Corrections per 54680: proper scope for the overshoot claim, a rigorous dissipativity
criterion, and a bounded-stable alternative proposal (not yet wired)

John's 54680 found the prior entry's own overshoot test overstated what it actually measures,
and asked for a more production-relevant criterion plus an assessed (not necessarily wired)
stable-integration alternative, reusing existing supported APIs rather than ad hoc damping.

### Scope correction: COM-only, not the full point-velocity response

The prior test's own assertion compared `force*DT/mass` (a COM-frame translational velocity
increment) directly against the float's own local POINT velocity, implicitly treating the two
as the same quantity. They are not: `handle.applyImpulseAtPoint` applies an impulse that
produces BOTH a COM translational response (`J/mass`) AND a rotational response
(`I⁻¹·(arm×J)`, which needs the body's own moment of inertia - unavailable via
`RigidBodyHandle`, the same missing-inertia limitation `angularDragRetention`'s own doc already
documents and works around) - the actual velocity change AT the float's own point is the SUM of
both, further coupled with the OTHER float's own simultaneous impulse. The test (renamed
`directionalDragComOnlyVelocityIncrementExceedsLocalVelocityAtTheDivergingStep`) now states this
scope explicitly: it demonstrates that the COM-frame increment ALONE already exceeds the local
velocity it was computed from - a real, verified scalar fact about the explicit drag formula
under these real numbers - not a proven complete rigid-body event, and not identified as the
first onset of divergence.

### Correction: the earlier step was not "stable," only below one particular threshold

The prior entry called gameTime=205 "stable"/"non-diverging." Per John's own correction: angular
velocity was ALREADY growing at that step (confirmed in the earlier per-tick table: `|angularVelocity|`
23.63 at tick2's entry rising to 82.83 by tick3's entry, before gameTime=206's own explosion) -
so "stable" mischaracterized the trajectory. Corrected to the narrower, accurate claim: this
earlier step's own COM-only velocity increment does not exceed its own local velocity AT THAT
ONE INSTANT - a fact about that one threshold at that one step, not a claim the trajectory was
safe.

### A production-relevant dissipativity criterion

Added `Buoyancy.explicitDragStabilityMargin(speed, submergedFraction, dragCoefficient,
axisMultiplier, dt, mass)` - the classical explicit-Euler linear-stability bound, linearizing the
quadratic drag `F=-fraction*coefficient*axisMultiplier*speed*v` around the current `speed`
(the same locally-fixed-speed approximation `angularDragRetention`'s own closed-form solution
already makes for the rotational term): effective per-step damping rate
`k_eff=fraction*coefficient*axisMultiplier*speed`, stable exactly when `k_eff*dt/mass < 2` (the
standard bound for explicit integration of `dv/dt=-k*v`). Returns the margin itself, not just a
boolean, so a caller can see how far past (or under) the threshold a step actually is.
`isExplicitDragStepStable` wraps the `< 2.0` check. Same explicit COM-only, single-float scope
disclosed as above - this is NOT a full rigid-body stability criterion.

Applied to the real incident: at gameTime=206 (the diverging step), margin ≈ `1.0*400*43.90*0.05/400
= 2.195` - at/past the threshold. At gameTime=205, margin ≈ `0.739*400*16.66*0.05/400 ≈ 0.616` -
under the threshold. Both figures are consistent with (not independent proof beyond) the COM-only
velocity-increment comparison already reported.

### A bounded-stable alternative, assessed but NOT wired into production

Per John's own explicit instruction ("assess a bounded stable integration option compatible with
existing APIs... Any proposed production change must be mechanism/diff reviewed before native
validation"), added `Buoyancy.directionalDragRetention(speed, submergedFraction, dragCoefficient,
axisMultiplier, dt)` as a PROPOSAL only - not called from `BuoyantForces.apply`, no behavior
change. Mirrors `angularDragRetention`'s own already-reviewed closed-form pattern exactly,
applied to the per-axis LINEAR drag term instead of the rotational one: the exact analytic
solution of `dv/dt = -fraction*coefficient*axisMultiplier*speed*v` is
`v(t+dt) = v(t) / (1 + fraction*coefficient*axisMultiplier*speed*dt)` - a multiplicative velocity
SCALE, never an impulse, mathematically guaranteed to lie in `(0,1]` for any nonnegative inputs
(denominator always `>= 1`), so applying it can never overshoot past zero, reverse velocity, or
add energy, for ANY configured coefficient/speed/dt - not just within some additionally-clamped
range, and with no moment of inertia needed anywhere (sidestepping the exact missing-inertia
problem the COM-only approximation above has). Verified in the new test suite that this
proposed function stays in `(0,1]` even at the exact diverging step's own extreme speed (43.90)
and across a wide input sweep including this incident's own real coefficient/fraction values.

This remains a PROPOSAL, not a fix: wiring it into `BuoyantForces.apply` (replacing the current
`applyImpulseAtPoint`-based impulse with a direct velocity-scale call, analogous to how
`angularDragRetention` is already consumed) would be a real behavior change to buoyancy/drag for
every float-only hull, not only this scenario - needs its own explicit mechanism/diff review
before any native validation, per John's own standing instruction. Not implemented this entry.

### Tests added (all compiled/passing under the exclusive lock,
`MINECRAFT-545-dissipativity-20261117T182420Z`, `RESULT=PASS`)

- `directionalDragComOnlyVelocityIncrementExceedsLocalVelocityAtTheDivergingStep` (renamed,
  rescoped from the prior `directionalDragOvershootsAtTheExactReconnectDivergenceIncident`).
- `explicitDragStabilityMarginExceedsTwoAtTheDivergingStepButNotTheEarlierOne`,
  `explicitDragStabilityMarginIsZeroWhenDragIsConfiguredOff`.
- `directionalDragRetentionIsOneWhenNotSubmergedOrNotMovingOrZeroCoefficient`,
  `directionalDragRetentionIsAlwaysInZeroToOneRangeAcrossWideInputsIncludingThisIncidentsOwnExtremeSpeed`,
  `directionalDragRetentionNeverOvershootsEvenAtTheExactDivergingStepsOwnExtremeSpeed`.

### Disposition

Prior overclaim corrected to its actual (COM-only, single scalar fact) scope. A more rigorous,
production-relevant dissipativity criterion added and verified against the real incident. A
bounded-stable alternative proposed, implemented as a pure function, and tested - explicitly NOT
wired into production pending its own mechanism/diff review. No arbitrary damping/caps/fixture
weakening. No native run performed or requested.

## Corrections per 54684: mass-normalization bug, exact-vs-backward-Euler mislabeling, and the
mechanism-preservation overclaim retracted

John's 54684 found two concrete math errors in the proposed `directionalDragRetention` and one
overclaim in its own tests, all before any wiring into production - exactly the review the
proposal was submitted for.

### Error 1 - missing mass normalization (confirmed numerically)

`directionalDragForce` returns a FORCE (N); translational `dv/dt = F/mass` requires dividing by
mass. The first version of `directionalDragRetention` omitted this entirely, reusing the force
coefficient as a rate constant directly. `angularDragRetention` avoids this by design - its own
`angularDragCoefficient` is explicitly documented as already inertia-normalized, so no separate
division is needed there; copying its formula SHAPE without also carrying that normalization was
not valid. Confirmed independently: at this incident's own real values (mass=400,
coefficient=400, speed=43.90, dt=0.05), the first version returned `1/879 ≈ 0.00114`; re-derived
by hand and confirmed exactly matches John's own cited figure. Fixed: added a `mass` parameter,
dividing by it in the formula - now returns `1/3.195 ≈ 0.313` at the same values, also confirmed
to match John's own cited "mass-consistent scalar rational model" figure exactly.

### Error 2 - mislabeled "exact"

For a FIXED rate `k` (this method's own locally-fixed-speed linearization), the TRUE exact
solution of `dv/dt=-k*v` is the EXPONENTIAL `v(t+dt)=v(t)*exp(-k*dt)` - not `v(t)/(1+k*dt)`, which
is instead the BACKWARD (implicit) EULER discretization of that same linear ODE.
`angularDragRetention`'s own `1/(1+...)` form IS genuinely exact for ITS OWN ODE, because that
ODE (`domega/dt=-k*omega*|omega|`) is quadratic in the evolving variable itself - this method's
own ODE is linear in the evolving variable (speed is a separate, frozen parameter), so the same
algebraic shape here is backward Euler, not an exact solution. Corrected the doc to state this
precisely, without reusing "exact" for a form that is not exact for this method's own stated ODE.

### Error 3 - mechanism-preservation overclaim retracted

The prior tests implied this scalar retention was close to a complete, inertia-free safety
proof. Retracted: a pure COM-velocity SCALE is not a mechanism-preserving replacement for the
current per-float off-center IMPULSE, which produces both translational AND rotational (torque,
via each float's own `arm`) response together - this method does not define how to reproduce
that coupling in a bounded way, and reproducing it correctly would need the body's own moment of
inertia, unavailable via `RigidBodyHandle` (the same missing-inertia limitation
`angularDragRetention`'s own doc already names). Stated explicitly in both the method's own doc
and the test comments: this is a genuinely unresolved gap in the currently supported pinned API,
not solved by this proposal - the smallest scoped claim this proposal can honestly make is that
the SCALAR VALUE itself stays bounded in `(0,1]`, nothing about the full rigid-body response.

### Verification performed (no native run)

Added `directionalDragRetentionMatchesTheMassConsistentValueAtTheExactIncidentNumbers`, pinning
the corrected `1/3.195` value exactly (asserted to 1e-3 of John's own cited figure). Extended the
existing range test to also sweep `mass` across several orders of magnitude, confirming the
corrected formula still stays in `(0,1]` for all nonnegative inputs including mass. Full
compile+test under the exclusive lock (`MINECRAFT-545-fix-mass-exact-20261117T183315Z`,
`RESULT=PASS`).

### Disposition

Both confirmed math errors fixed; the mechanism-preservation claim retracted to its honest,
narrower scope. This remains a PROPOSAL only, still not wired into `BuoyantForces.apply`, and
its own open gap (no bounded way to reproduce the off-center torque coupling without moment-of-
inertia access) is now stated plainly rather than implied solved. No native run performed or
requested.

## Source audit of the pinned Sable API per 54688: a genuinely supported inertia/effective-mass
path EXISTS, correcting this investigation's own "missing inertia" assumption

John's 54688 confirmed the mass/exactness corrections and asked for a direct audit of the
pinned `RigidBodyHandle`/native wrapper source and body-creation path - not just existing
comments - distinguishing ABSENT public API from merely UNUSED public API. Extracted and
decompiled the exact pinned jar (`sable-neoforge-1.21.1-2.0.5.jar`, hash
`c8710f85bc780bbf523e6726ceaaf76dd0b8c61479db497b382c6bb34317e7ab`, confirmed matching the real
build classpath) directly - `RigidBodyHandle`, `BoxPhysicsObject`, `MassData`, `ForceTotal`,
`PhysicsPipelineBody`.

### RigidBodyHandle itself: no mass/inertia access at all

`dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle` (package `handle`) exposes:
`applyImpulseAtPoint(position, force)`, `applyLinearAndAngularImpulse(impulse, torque[,
wakeUp])`, `applyLinearImpulse`/`applyAngularImpulse`/`applyTorqueImpulse` (all delegating to
`applyLinearAndAngularImpulse`), `getLinearVelocity`/`getAngularVelocity`,
`addLinearAndAngularVelocity`, `teleport`, `isValid`, `applyForcesAndReset(ForceTotal)`. NONE of
these read mass or inertia. This confirms `RigidBodyHandle` itself genuinely has no such API -
the ABSENCE is real for this specific type.

### `PhysicsPipelineBody.getMassTracker()`: a REAL, PUBLIC, REQUIRED interface method - already
used by DIV, just not for this purpose

`dev.ryanhcode.sable.api.physics.PhysicsPipelineBody` (the interface `BoxPhysicsObject`
implements, and the one `BuoyantBody`'s own `box` field is typed as holding) REQUIRES
`MassData getMassTracker()` as part of its own contract - not an obscure side method.
`dev.ryanhcode.sable.api.physics.mass.MassData` is a public interface:
```java
public interface MassData {
    double getMass();
    double getInverseMass();
    Matrix3dc getInertiaTensor();
    Matrix3dc getInverseInertiaTensor();
    @Nullable Vector3dc getCenterOfMass();
    default boolean isInvalid();
    default double getInverseNormalMass(Vector3dc position, Vector3dc direction);
}
```
DIV's OWN `ShapeAwareBoxPhysicsObject` (this ticket's own earlier work, `src/main/java/.../
ShapeAwareBoxPhysicsObject.java`) ALREADY overrides `getMassTracker()` to return a real solid-box
inertia tensor (`BoxInertia.solidBoxTensor(mass, halfExtents)`) - confirmed by direct re-read of
that file. `box.getMassTracker()` is callable RIGHT NOW on the SAME `box` field `BuoyantBody`
already holds. This directly corrects this investigation's own repeated "no moment of inertia is
available" framing (echoed from the ALREADY-DOCUMENTED, pre-existing `angularDragRetention`
review finding, comment 53679, and repeated by me in this entry's own earlier corrections) - the
API is not ABSENT, it was UNUSED for this purpose. `getMassTracker()`'s own JAVA-SIDE return
value is still confirmed (per this ticket's own earlier finding, comment 54413) NOT consumed by
the native body-creation path (`createBox` gives Rapier only raw mass/half-extents; Rapier
computes its own internal mass properties independently) - so this tensor is DIV's own accurate,
independently-computed physical property of the real box shape, not necessarily byte-for-byte
identical to whatever Rapier's own internal representation is, but a real, correct, FREELY
AVAILABLE solid-box inertia tensor for the real configured shape/mass nonetheless.

### `MassData.getInverseNormalMass` - the exact rigid-body effective-mass-at-a-point formula,
already implemented, already callable

```java
default double getInverseNormalMass(Vector3dc position, Vector3dc direction) {
    Vector3d comLocalPos = position.sub(this.getCenterOfMass(), new Vector3d());
    Vector3d normalizedDirection = direction.normalize(new Vector3d());
    Vector3d cross = comLocalPos.cross(normalizedDirection, new Vector3d());
    return cross.dot(this.getInverseInertiaTensor().transform(cross, new Vector3d())) + this.getInverseMass();
}
```
This is the STANDARD rigid-body "effective inverse mass along a direction at a point" formula
(`1/m_eff = (r×n̂)·I⁻¹·(r×n̂) + 1/m`) used throughout real-time physics engines to compute exactly
how much velocity CHANGE along a given direction results from a unit impulse applied at a given
point - accounting for BOTH the translational AND rotational response TOGETHER, correctly
coupled, with no separate inertia-tensor math needed by the caller. This is a DEFAULT method on
the SAME `MassData` interface `box.getMassTracker()` already returns - pure JOML matrix
arithmetic, synchronous, no native call, callable from ordinary Java right now with the
EXISTING `box`/`center` fields `BuoyantBody` already has. This is the exact missing piece this
investigation's own prior entries (including my own, before this audit) assumed did not exist.

### Smallest sound, mechanism-preserving bound using this real capability (assessed, NOT wired)

Per John's own request ("assess the smallest sound way to preserve the existing force/torque
model, including whether an existing native solver path can integrate it safely"): the per-float
drag impulse could be bounded using the EXACT effective mass at that float's own point, computed
via `box.getMassTracker().getInverseNormalMass(pointPosition, dragDirection)`, rather than the
earlier COM-only approximation (`force*DT/mass`) or the earlier retraction-pending scalar
retention proposal. A bound of the form "cap the applied impulse magnitude so the resulting
velocity change ALONG the drag direction, at this exact point, computed via the real effective
mass, never exceeds the local velocity component being opposed" is PHYSICALLY DERIVED from the
real rigid-body coupling (not an arbitrary clamp), uses only EXISTING, SUPPORTED, PUBLIC API
(`MassData`/`getInverseNormalMass`, already present in the pinned jar, no dependency change, no
reflection), and preserves the SAME off-center impulse mechanism currently in use (still calls
`applyImpulseAtPoint`, still produces real torque via the real arm) - it bounds the MAGNITUDE,
not the mechanism. This is assessed as sound and feasible; NOT yet implemented or wired - needs
its own design/diff (exact direction convention, multi-float accumulation order, whether to use
each float's own instantaneous `getInverseNormalMass` fresh each tick) before any production
change, consistent with the standing "mechanism/diff reviewed before native validation"
instruction.

### Zero-mass domain note (54688's own correction)

`directionalDragRetention`'s `(0,1]` range proof requires finite positive `mass` (division by
`mass` in the corrected formula); `mass=0` is undefined (division by zero), not covered by the
existing range-sweep test, which only exercises positive mass values. Noted explicitly here;
`BoxPhysicsObject`'s own constructor already requires a real (non-zero, in practice always
positive per `BuoyantBody`'s own `Math.max(50, v.getMass())` floor) mass, so this is a
documented domain restriction, not a live bug - no test or doc change made beyond this note,
since the sweep already only used realistic positive masses.

### Disposition

Audit complete: `RigidBodyHandle` genuinely has no mass/inertia API (confirmed absent); `MassData`
(via `box.getMassTracker()`, already overridden by DIV's own `ShapeAwareBoxPhysicsObject`) DOES
expose a real inertia tensor and the exact effective-mass-at-a-point formula
(`getInverseNormalMass`) - previously treated as an unavailable capability, now confirmed
available and unused for this purpose. A smallest-sound, mechanism-preserving bound using this
real capability is assessed as feasible, not yet designed/wired - the next step, if pursued,
needs its own explicit design before any production diff. No reflection, no dependency upgrade,
no native-private bypass. No native run performed or requested.

## Energy-based dissipativity bound implemented per 54691, with native synchronicity flagged
as unverified

John's 54691 confirmed the audit was useful but not yet validated against native response or a
complete multi-float design, and asked for: the energy-based dissipativity formula specifically
(`dKE = J*s + 0.5*k*J^2`), establishing whether native impulse calls synchronously update
velocity getters, and finite code/test evidence covering off-center impulses, multiple floats,
invalid/nonfinite inputs, and incident-scale velocities - still entirely unwired from production.

### Native synchronicity: a suggestive naming finding, explicitly NOT proof

Extracted and decompiled the REAL pipeline implementation (not just the abstract `PhysicsPipeline`
interface) - `dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline`, bundled inside the
pinned jar's own nested `META-INF/jarjar/...sable_rapier-1.21.1-2.0.5.jar`. Its own
`applyImpulse(body, position, force)` and `applyLinearAndAngularImpulse(body, force, torque,
wakeUp)` call the native methods `Rapier3D.applyForce(...)` and
`Rapier3D.applyForceAndTorque(...)` respectively - named "force," not "impulse," on the native
side. `getLinearVelocity`/`getAngularVelocity` call `Rapier3D.getLinearVelocity`/
`getAngularVelocity` directly (a real native call each time, not a cached Java-side value).

This naming is SUGGESTIVE that Rapier's own native side may accumulate an applied force to be
integrated at the NEXT `step()` call (the conventional distinction real physics engines make
between an immediate-impulse and an accumulated-force API) rather than applying it synchronously
before the next velocity read - which would mean multiple floats' own impulses, computed from
the same stale velocity snapshot and applied sequentially within one `BuoyantForces.apply()`
call, are effectively summed and integrated TOGETHER at the next step, not applied one-after-
another against progressively-changing state. **This is NOT proven** - it is inferred from a
Java-side method NAME calling a native function, with no access to Rapier's own source, and is
stated here explicitly as an UNVERIFIED native assumption, not resolved by this entry. Java-side
metadata (including this naming observation) does not prove native behavior.

### Energy-based dissipativity formula implemented

Added to `Buoyancy.java`: `impulseEnergyChange(impulseMagnitude, preImpulsePointVelocityAlong
Direction, inverseEffectiveMass)` - exactly John's own formula, `J*s + 0.5*k*J^2`;
`isImpulseDissipative` (the `<= 0` check); `maxDissipativeImpulseMagnitude(s, k)` - the non-
trivial root of that quadratic for an impulse opposing `s`, `2*|s|/k`, returning `0` when `k` is
not a positive finite value (covers zero, negative, NaN, and infinite `k` explicitly, not just
the zero/negative case). Designed to be used WITH the real per-point effective mass from
`MassData.getInverseNormalMass` (the audit's own finding, previous entry) rather than a COM-only
approximation - `k` is documented as the caller's responsibility to supply correctly per point/
direction, with explicit scope notes on local-vs-world frame consistency (the caller must ensure
the inertia tensor's own frame matches whatever frame `arm`/`direction` are expressed in) and on
the multi-float/synchronicity assumption above.

### Tests added (all compiled/passing under the exclusive lock,
`MINECRAFT-545-energy-bound-20261117T185804Z`, `RESULT=PASS`)

- `anImpulseAtExactlyTheMaxDissipativeBoundIsEnergyNeutralNotPositive` - the boundary itself
  stays `<=0` (energy-neutral, not a safety margin) across a sweep of `s`/`k` including this
  incident's own real numbers.
- `anImpulseWellBelowTheMaxDissipativeBoundIsStrictlyDissipative` /
  `anImpulsePastTheMaxDissipativeBoundAddsEnergy` - the direct contrast on either side of the
  bound, at incident-scale velocity (43.90).
- `multipleFloatsImpulsesComputedFromTheSameSnapshotAndSummedStayJointlyDissipativeWhenEachIsIndividuallyBounded` -
  the specific multi-float case the "accumulate, not apply-immediately" assumption makes sound:
  two floats with DIFFERENT effective mass at their own points, each independently bounded
  against the SAME snapshot, summed and confirmed jointly non-positive.
- `maxDissipativeImpulseMagnitudeIsZeroForNonPositiveOrNonFiniteEffectiveMass` - zero, negative,
  NaN, and negative-infinite `k` all explicitly covered, not just the zero/negative case.
- `zeroPreImpulseVelocityHasNoDissipativeImpulseBudget` - at rest along a direction, the bound
  correctly collapses to zero rather than some positive "free" allowance.

### What remains explicitly open

- Native synchronicity (accumulate-vs-immediate) is a naming-based inference, not verified
  against Rapier's own source or a native run.
- Coordinate-frame consistency (local tensor vs. local arm/direction, or a correctly-rotated
  tensor) is documented as the CALLER's own responsibility, not yet exercised by a test using a
  rotated/asymmetric box specifically - the existing tests use scalar `s`/`k` values directly,
  not a full vector/matrix worked example.
- Whether the Java-side `BoxInertia.solidBoxTensor` numerically matches Rapier's own internal
  mass-property representation remains unverified (per the prior entry's own point) - this
  entry's formula is agnostic to that question; it takes `inverseEffectiveMass` as a parameter,
  whatever its own provenance.
- Not wired into `BuoyantForces.apply`; still a reviewable design, not a production diff.

### Disposition

Energy-based formula implemented exactly as specified, tested against incident-scale values,
multiple floats, and invalid/nonfinite inputs. The native-synchronicity question is reported as
a real, named, but UNVERIFIED finding (method naming only) rather than assumed resolved. No
reflection, no dependency upgrade, no native-private bypass, no production wiring. No native run
performed or requested.

## Corrections per 54694: confirmed multi-float math error retracted, bound-reversal
## mislabeling fixed, vector energy test added, finite-input guards added

John's comment 54694 found a confirmed, concrete mathematical error in the just-committed
(`b711516`) multi-float energy test. The test claimed that summing `impulseEnergyChange`
evaluated SEPARATELY for two impulses against the same velocity snapshot predicts their actual
COMBINED energy effect, reasoning that each impulse individually stayed below its own
`maxDissipativeImpulseMagnitude`. That reasoning drops a real quadratic cross-term:
`0.5*k*(J1+J2)^2 != 0.5*k*J1^2 + 0.5*k*J2^2` whenever both impulses are nonzero. John's own exact
counter-example (independently re-verified numerically via `python3` before any fix was made):
mass=1 (k=1), s=1, two equal collinear impulses J1=J2=-1.5. Individually, each gives
`impulseEnergyChange(-1.5, 1, 1) = -0.375` (sum of the two = -0.75, "safely" below the individual
bound of 2 each). The TRUE combined effect - applying both impulses to the same body changes
velocity ONCE, by their sum, not twice independently - is `v' = s + (J1+J2)*k = 1 - 3 = -2`,
`KE_before = 0.5`, `KE_after = 2.0`, `actual change = +1.5`: energy ADDED, not removed, the
opposite sign from the naive per-impulse sum. This exactly matches
`impulseEnergyChange(J1+J2=-3, s=1, k=1) = +1.5` - confirming the correct approach is calling the
SAME formula ONCE with the SUM of the impulses, never by summing separate per-impulse calls
against the same snapshot. John stated, and this is confirmed by the algebra itself, that this
error is INDEPENDENT of whether the native impulse call applies immediately or accumulates until
the next step - it is a pure additivity mistake, not resolved by any native-synchronicity
assumption.

John also separately noted the "energy-neutral bound" `2*|s|/k` was mislabeled in prior doc
comments as a "non-overshoot" bound. It is not: at exactly that bound, the point-velocity
component along the impulse direction REVERSES SIGN with the SAME magnitude
(`v' = s + J*k = s - 2s = -s`, confirmed algebraically by substituting the opposing-sign impulse
magnitude into `v' = s + J*k`). The bound guarantees zero net kinetic-energy change, not that
velocity stays put or stays smaller in magnitude.

Fixes applied, this cycle:
- `Buoyancy.java`: rewrote `impulseEnergyChange`'s own javadoc SCOPE paragraph to state the
  dropped-cross-term finding precisely and remove the old, wrong "accumulate-then-integrate"
  multi-impulse safety claim - there is no multi-impulse safety claim in this file beyond
  single-impulse-at-a-time scope.
- `Buoyancy.java`: rewrote `maxDissipativeImpulseMagnitude`'s own javadoc to state the bound
  causes a velocity REVERSAL (not "non-overshoot"), and to state explicitly that this bound does
  not by itself bound multiple impulses' combined effect.
- `Buoyancy.java`: `maxDissipativeImpulseMagnitude` now also guards non-finite
  `preImpulsePointVelocityAlongDirection` (previously only `inverseEffectiveMass <= 0` was
  guarded - a non-finite `s` with an otherwise-valid `k` previously propagated NaN/Infinity
  straight through `Math.abs(...)` instead of returning the conservative `0`).
- `BuoyancyTest.java`: REMOVED the confirmed-wrong test
  (`multipleFloatsImpulsesComputedFromTheSameSnapshotAndSummedStayJointlyDissipativeWhenEachIsIndividuallyBounded`)
  and replaced it with
  `summingImpulseEnergyChangePerImpulseAgainstTheSameSnapshotDoesNotPredictTheirActualCombinedEffect`,
  which pins John's exact counter-example as a correctness regression: it asserts the naive
  per-impulse sum (-0.75) and the true combined effect (+1.5) disagree even in SIGN, and that the
  correct single-call-with-the-sum approach (`impulseEnergyChange(J1+J2, s, k)`) matches the true
  combined effect exactly.
- `BuoyancyTest.java`: added a genuine VECTOR rigid-body energy test,
  `impulseEnergyChangeOfTheSummedImpulseMatchesIndependentVectorRigidBodyEnergyForAnOffCenterImpulseOnAnAsymmetricBox`,
  which independently computes kinetic energy from real linear/angular velocity and a real
  non-uniform (asymmetric, non-cube) solid-box inertia tensor (`0.5*m*|v|^2 + 0.5*omega.(I*omega)`
  via actual JOML `Vector3d`/`Matrix3d` arithmetic), for an impulse applied off-center at an
  arbitrary point/direction - with NO reuse of `impulseEnergyChange`'s own scalar formula to
  compute the independent side of the comparison - confirming the scalar formula's prediction
  matches the true vector effect for this off-center/asymmetric/rotation-coupled case.

Further native-source resolution (per 54694's "a method name is no evidence of semantics"):
decompiled `Rapier3D.class` directly (extracted from the nested jarjar bundle inside the pinned
Sable jar, `sable-neoforge-1.21.1-2.0.5.jar`). Confirmed `applyForce`, `applyForceAndTorque`,
`getLinearVelocity`, `getAngularVelocity`, and the other native-facing methods are ALL declared
`static native` with ZERO Java-side implementation body - pure JNI stubs backed entirely by a
compiled native binary resource (`sable_rapier_binaries.zip.l4z`). This confirms there is NO
further Java-side evidence available beyond the method naming already found in prior comments -
this is the genuine ceiling of what is knowable without either an actual instrumented native run
or access to Rapier's own native (Rust/C) source, neither available or authorized in this
investigation. The native-synchronicity question (immediate vs. deferred/accumulated impulse
application) remains explicitly UNRESOLVED and is reported as such, not assumed.

Compiled and tested under the exclusive Gradle lock
(`/tmp/div-gradle-slot.lock`/`/tmp/div-gradle-slot.owner`, label
`M545-54694-fix-<timestamp>`): `BuoyancyTest`, `PhysicsBridgeClassificationTest`,
`LifecycleInvariantsTest` all green. `BuoyancyTest` itself: 46 tests, 0 failures, 0 errors, 0
skipped (per the retained JUnit XML `tests="46" skipped="0" failures="0" errors="0"`).

### Disposition

Confirmed multi-float additivity error retracted with a pinned regression reproducing John's
exact counter-example; bound mislabeling corrected; finite-input guard completed for both
formula inputs; a genuine independent vector rigid-body energy test added, covering an
off-center impulse on an asymmetric box; native-evidence resolution taken to its real ceiling
(direct `Rapier3D` JNI-stub decompilation). No arbitrary damping/caps/fixture-bound-weakening, no
reflection, no dependency upgrade, no native-private bypass, no production wiring. No native run
performed or requested - native hold remains CLOSED pending a new explicit coordinator release.

## Corrections per 54697: test bugs fixed, native source recovered (NOT exhausted), real
## sequential-impulse vector candidate built and tested

John's comment 54697 reviewed the exact diff of `a3e48ee` and confirmed the cross-term
retraction and finite-input guard were correct, but found several concrete bugs in the new
vector test from that same commit, and rejected the "native evidence exhausted at the JNI stub"
conclusion as premature. All addressed this cycle; still no production wiring, no native run.

### Test bugs found and fixed

- **Wrong inertia coefficient.** The test built its box inertia tensor using `mass/6.0 *
  (sum of squared half-extents)`. The correct solid-box formula - the SAME one this ticket's own
  `BoxInertia.solidBoxTensor` already implements correctly - is `mass/3.0 * (...)` (confirmed by
  re-deriving from the standard full-side-length formula `I=m/12*(h^2+d^2)` with
  `h=2*halfExtent`). The test had silently reused Sable's own shape-ignorant `mass/6` fallback
  (the exact thing `BoxInertia`'s own doc comment calls out as having "no shape term in it at
  all") instead of the real model this fix is scoped to validate.
- **Impulse aiding, not opposing, velocity.** The test picked `s=-1.75`-ish and `J=-27.3` by
  hand - same sign, so the chosen impulse AIDED the existing motion instead of opposing it, while
  the surrounding comment claimed the opposite. Fixed: the impulse magnitude in every corrected/
  new test is now derived from `-Math.signum(s) * bound * <fraction>`, which is dissipative-by-
  construction, with an explicit assertion that the sign is actually opposing.
- **Hand-derived effective mass instead of the real API.** The test manually recomputed the
  cross/dot formula that `MassData.getInverseNormalMass` already implements as a default method,
  rather than calling that method. Fixed: added `TestMassData.solidBox(mass, halfExtents)` (a
  small test-support factory building a real `MassData` instance) and switched both the fixed
  test and the new one to call `massData.getInverseNormalMass(arm, direction)` directly.
- **Only an unrotated diagonal tensor, only axis-aligned directions.** Added a second test,
  `impulseEnergyChangeMatchesIndependentVectorEnergyWhenTheImpulseDirectionIsNotAlignedWithAnyPrincipalAxis`,
  using an impulse direction with nonzero components on all three axes against the same kind of
  diagonal box tensor - this genuinely exercises `getInverseNormalMass`'s matrix-vector
  transform's cross-coupling terms, which an axis-aligned direction mostly hides. (A fully
  rotated, i.e. off-diagonal-in-world-frame, tensor is still open - noted below, not claimed
  done.)
- **Bound guarded against invalid inputs, not against overflow of its own output.**
  `maxDissipativeImpulseMagnitude` only guarded its two inputs; `2*|s|/k` can still overflow
  `double` range for finite, individually-valid inputs (e.g. `s=Double.MAX_VALUE, k=1`). Fixed in
  `Buoyancy.java`: the computed bound itself is now checked with `Double.isFinite`, returning the
  same conservative `0` as every other invalid case. New test
  `maxDissipativeImpulseMagnitudeIsZeroWhenTheFiniteInputsWouldOverflowTheResultItself` covers
  both the overflow case and a merely-large-but-non-overflowing case (`s=1e200`), confirming the
  guard doesn't over-trigger.

### Native source: NOT exhausted - recovered the actual Rust implementation, resolving the synchronicity question

54697 correctly rejected "the JNI stub has no body" as proof the native source was unavailable -
54413/54418 already demonstrated the real pinned `sable_rapier` Rust source is readable via
`gh api repos/ryanhcode/sable/contents/...?ref=<pinned tag's commit>`. Recovered the exact same
way, same pinned tag/commit as those entries (`mc1.21.1-2.0.5-neoforge`, `6966d2928340de7631abcecf8549904b877df0a8`,
re-confirmed live via `gh api repos/ryanhcode/sable/git/refs/tags/mc1.21.1-2.0.5-neoforge`),
file `sable_rapier/src/main/rust/rapier/src/lib.rs`:

```rust
// Java_..._Rapier3D_applyForce
let force: Vec3 = rb.rotation().mul_vec3(Vec3::new(fx, fy, fz));
let force_pos = rb.position().transform_point(Vec3::new(x, y, z));
rb.apply_impulse(force, wake_up > 0);
let torque_impulse = (force_pos - rb.position().translation).cross(force);
rb.apply_torque_impulse(torque_impulse, wake_up > 0);

// Java_..._Rapier3D_applyForceAndTorque
let force: Vec3 = rb.rotation().mul_vec3(Vec3::new(fx, fy, fz));
rb.apply_impulse(force, wake_up > 0);
let torque: Vec3 = rb.rotation().mul_vec3(Vec3::new(tx, ty, tz));
rb.apply_torque_impulse(torque, wake_up > 0);
```

Both native functions call Rapier's own `RigidBody::apply_impulse`/`apply_torque_impulse`
directly and unconditionally, on every single invocation - there is no accumulation buffer and
no deferral to the next `step()` call anywhere in this function. Combined with the already-
decompiled Java call chain (`RigidBodyHandle.applyImpulseAtPoint` ->
`RapierPhysicsPipeline.applyImpulse` -> `Rapier3D.applyForce`, confirmed one native call per Java
call via `javap`), this **definitively resolves** the previously-UNVERIFIED "native
synchronicity" question (which earlier entries in this investigation stated as unresolved,
method-naming inference only): every call `BuoyantForces.apply`'s own per-float loop makes to
`handle.applyImpulseAtPoint` mutates the body's REAL rigid-body velocity IMMEDIATELY. Each
float's impulse is applied against whatever velocity every PRIOR float's impulse (in the same
tick) already produced - never against one shared, frozen pre-tick snapshot.

This also rules out, definitively rather than just mathematically (as 54694/54697 already did
independently), BOTH prior multi-float models: summing `impulseEnergyChange` per impulse against
one shared snapshot (54694), and calling it once with every float's impulse combined into a
single scalar `J` against a single `k` (54697 - different floats act at different points/
directions, each with its own effective mass; there is no one `k` that represents them jointly).
The physically correct model, now confirmed to match what the native engine actually does, is
SEQUENTIAL: apply each float's impulse as a real linear+angular velocity update, in dispatch
order, bounding each one against the CURRENT (already-updated) state at the moment it is
applied.

### New UNWIRED candidate: `SequentialImpulseEnergyLedger`

Added `SequentialImpulseEnergyLedger` (main) and `SequentialImpulseEnergyLedgerTest` (4 tests) -
not called from `BuoyantForces.apply` or any other production path. It holds real mutable
linear/angular velocity, and for each float, in dispatch order: computes the dissipative-impulse
bound at that float's own arm/direction against the CURRENT state (`currentMaxDissipativeImpulseMagnitude`,
using the real `MassData.getInverseNormalMass`), then applies a real linear+angular impulse
update (`applyImpulseAndMeasureEnergyChange`, matching the native engine's own
`v += impulse/mass`, `omega += inverseInertia*(arm x impulse)`), returning the actual measured
kinetic-energy change for that single application. Tests cover: three nonparallel off-center
impulses on an asymmetric box at incident-scale velocity, each bounded against the live
sequential state, confirmed to keep the TRUE combined energy change non-positive; confirmation
that the bound at a fixed point/direction genuinely changes after an earlier impulse updates the
real state (the mechanism the shared-snapshot approach got wrong); a single application kept at
its own current bound not increasing energy; a single application deliberately past its own
current bound adding energy. Drag/physical force DIRECTION is unchanged from the existing
`BuoyantForces` model - this candidate only proposes HOW an energy-dissipativity bound could be
evaluated sequentially per float, not a different force model. Integration/scaling tradeoff: this
is a per-tick, per-float bound evaluated with real (not frozen) state - using it in production
would mean computing it once per float, in the SAME order `BuoyantForces.apply`'s own loop
already uses, immediately before (not after) that float's impulse is actually applied.

### Still open, not claimed done

- A genuinely ROTATED (off-diagonal-in-world-frame, not merely non-axis-aligned-direction) box
  tensor test is not yet added - the current tests use a diagonal body-local tensor with varying
  impulse directions, which exercises `getInverseNormalMass`'s cross-coupling but not an actual
  rotated frame.
- `SequentialImpulseEnergyLedger` is a candidate/diagnostic utility only - not wired into
  `BuoyantForces.apply`, and wiring it in would require its own separate review/diff, explicitly
  out of scope here.
- No native run performed or requested this cycle either - native hold remains CLOSED pending a
  new explicit coordinator release.

Compiled and tested under the exclusive Gradle lock (`/tmp/div-gradle-slot.lock`/
`/tmp/div-gradle-slot.owner`, labels `M545-54697-fix-<timestamp>`/`M545-54697-fix2-<timestamp>`):
`BuoyancyTest` (48 tests), `SequentialImpulseEnergyLedgerTest` (4 tests),
`PhysicsBridgeClassificationTest`, `LifecycleInvariantsTest` all green, 0 failures/errors.

### Disposition

Test bugs (wrong inertia coefficient, impulse aiding instead of opposing velocity, hand-derived
effective mass instead of the real API, overflow-unguarded bound output) fixed and verified;
native source recovered directly (not declared exhausted) at the same pinned tag/commit used in
54413/54418, definitively resolving the native-synchronicity question that had stood unresolved
through this entire investigation; a real, tested, UNWIRED sequential-impulse vector candidate
implemented per the model that finding confirms is correct. No production wiring, no native run
performed or requested - native hold remains CLOSED pending a new explicit coordinator release.

## Corrections per 54700: two real ledger bugs fixed, covariance/independent-energy/incident
## tests added, BuoyantForces's actual velocity-read timing confirmed, overclaim softened, and
## an UNWIRED production-integration design delivered for review

John's comment 54700 confirmed the native Rust trace from `8262f8c` was independently re-read
and verified correct, called the ledger candidate "real progress," and asked for the review
gaps below closed under the same standing authorization (no native run).

### Two real bugs in `SequentialImpulseEnergyLedger` found and fixed

1. **Direction-normalization inconsistency.** `currentMaxDissipativeImpulseMagnitude` used the
   caller's RAW `direction` for the velocity-component side (`s`), while
   `MassData.getInverseNormalMass` silently normalizes its own copy for the effective-mass side
   (`k`) - for any non-unit-length `direction`, `s` and `k` would be computed against different
   effective bases, silently corrupting the result. Fixed: both methods now normalize `direction`
   once, internally, consistently for both sides - and reject a zero-length or non-finite
   direction with `IllegalArgumentException` rather than silently producing NaN/Infinity.
2. **`arm`-vs-`position`/COM contract bug.** `MassData.getInverseNormalMass(position, direction)`
   takes a POSITION and subtracts `getCenterOfMass()` internally. The ledger's own `arm`
   parameter is COM-relative (the standard `torque = arm x force` convention, matching
   `BuoyantForces.apply`'s own existing `arm` values) - calling `getInverseNormalMass(arm, ...)`
   directly was WRONG whenever the real COM is non-zero, since that method would subtract the
   COM a second time. This was latent in every earlier test because every test's own `MassData`
   happened to return COM=(0,0,0). Fixed: the ledger now passes `position = arm + getCenterOfMass()`
   to `getInverseNormalMass`, so `position - COM` correctly reduces back to the given `arm`
   regardless of where the real COM is.

### New tests (`SequentialImpulseEnergyLedgerTest`, now 10 total)

- `aZeroOrNonFiniteDirectionIsRejected` - the new `IllegalArgumentException` guard, for both
  methods, for zero-length and non-finite direction inputs.
- `aNonUnitLengthDirectionProducesTheSamePhysicalResultAsItsNormalizedEquivalent` - confirms the
  normalization fix: the same physical direction gives the identical bound/energy-change/
  resulting-velocity regardless of the caller's vector's own magnitude.
- `aNonZeroCenterOfMassIsHandledConsistentlyBetweenTheArmContractAndGetInverseNormalMass` -
  confirms the COM fix directly, using a deliberately non-origin COM (`TestMassData.solidBoxWithCenterOfMass`,
  new factory) and independently recomputing the expected bound using the documented
  `position = arm + COM` contract.
- `aRotatedWorldFrameSystemProducesTheSameEnergyChangeAsTheEquivalentLocalFrameSystem` - the
  genuinely rotated covariance test 54700 asked for: rotates the ENTIRE system (tensor via new
  `TestMassData.rotatedSolidBox`, arm, direction, both velocities) by the same arbitrary
  rotation, confirms the scalar energy change agrees, AND confirms the rotated run's resulting
  velocities, transformed back by the inverse rotation, match the local-frame run's own
  resulting velocities exactly - full covariance, not merely scalar agreement. (A prior test
  only varied the impulse DIRECTION against a still-diagonal tensor - this is a genuinely
  different, stronger check.)
- `theSumOfReturnedEnergyChangesMatchesIndependentlyRecomputedTotalKineticEnergyChangeOutsideTheLedger` -
  recomputes total kinetic energy before/after using ONLY the ledger's exposed
  `linearVelocity()`/`angularVelocity()` plus the test's own separately-written expression - not
  by calling the class under test's own private `kineticEnergy()` method - confirming it matches
  the sum of per-impulse returned deltas.
- `multipleFloatsWithMixedDirectionsStayJointlyNonPositiveAtIncidentScaleLinearAndAngularVelocity` -
  four genuinely mixed-direction floats (including a negative-component direction) at
  incident-scale LINEAR AND ANGULAR velocity (angular magnitude ~34 rad/s, matching the retained
  native evidence from the second authorized run), bounded sequentially, confirmed jointly
  non-positive.

### `BuoyantForces.apply`'s actual velocity-read timing - confirmed, not assumed

54700: "immediate native application does NOT prove production calculates each drag from fresh
velocity; review the actual `BuoyantForces` snapshot/getter placement separately." Read
`BuoyantForces.apply` directly (not inferred): its per-float loop computes every float's
`localVelocity` via `new Vector3d(angular).cross(arm).add(velocity)`, where `velocity`/`angular`
are the METHOD'S OWN PARAMETERS - a single snapshot the caller took once, before this whole
method (and its per-float loop) runs. There is no fresh per-float read of
`handle.getLinearVelocity()`/`getAngularVelocity()` inside that loop - only the SEPARATE
rotational-drag term, after the loop, already reads `handle.getAngularVelocity()` fresh (the
53689 fix). **Confirmed fact, not an evaluation**: `BuoyantForces.apply` today computes every
float's drag FORCE from one stale, once-per-tick snapshot, even though (per `8262f8c`'s native
trace) each float's resulting IMPULSE is APPLIED immediately/sequentially. Native application
synchronicity and Java-side force-calculation freshness are two independent facts; neither one
proves the other. No behavior change made - this is a factual finding, not a fix.

### Overclaim softened

54700: "Combined VECTOR impulse analysis is not ruled out merely by immediate semantics (the
incorrect scalar sum was). Avoid overclaiming that point." `SequentialImpulseEnergyLedger`'s own
class doc previously implied sequential application was established as THE correct model.
Corrected: immediate native application rules out the two SCALAR approaches this investigation
actually tried and disproved (shared-snapshot sum, 54694; single-combined-scalar-J-against-one-k,
54697) - it does not, by itself, rule out every possible correctly-formed VECTOR combined-impulse
analysis (e.g. one full vector energy expression using accumulated total linear/angular
impulse). The sequential ledger is stated as A valid model, confirmed consistent with native
semantics - not claimed to be the only one.

### New UNWIRED production-integration design, for review: `ProposedBoundedDragIntegration`

Added `ProposedBoundedDragIntegration.boundedDragForce(...)` (+ 5 tests,
`ProposedBoundedDragIntegrationTest`) - NOT called from `BuoyantForces.apply` or any other
production path; production activation/native execution remain gated exactly as before. Scales
a drag force's magnitude toward zero (never up, never sign-flipped) when its resulting impulse
would exceed the sequential dissipative bound at that float's own arm/direction, evaluated
against CALLER-SUPPLIED current linear/angular velocity (a real caller would need to read these
FRESH per float, addressing the staleness finding above - the method takes them as inputs rather
than reading a handle itself, so this stays pure/testable without a live Sable runtime). Buoyancy
is never passed in, touched, or capped - only the drag contribution is bounded, mirroring how
`BuoyantForces.apply` already keeps `buoyancyOnlyForce` separate from the combined force.
Correctly handles the sign case 54700's own review would have caught: a forward (same-direction)
impulse is only dissipative when it OPPOSES the point-velocity component along itself; when the
drag direction AIDS that component instead (possible because full point velocity includes a
rotational contribution the axis-local drag force doesn't itself account for), the bound
collapses to exactly 0, not `maxDissipativeImpulseMagnitude`'s own "always opposing" value
applied blindly - tested explicitly (`aDragForceThatAidsRatherThanOpposesThePointVelocityIsScaledEntirelyToZero`).
Numerical tradeoff documented in the class's own doc: scaling toward zero preserves direction
exactly but forfeits landing exactly at the energy-neutral boundary (which reverses velocity, not
"stops" it). Frame handling: performs no transform of its own - expects already-local-frame
vectors, exactly matching `BuoyantForces.apply`'s own existing convention for its
`handle.applyImpulseAtPoint` calls.

### Still open, not claimed done

- The solid-box mass/inertia model this ticket's tests use is not confirmed to equal what Rapier
  itself computes internally for a cuboid collider at `createBox` time (54413) - every energy
  prediction here is only as good as that unconfirmed equivalence. Explicitly documented as an
  open caveat in both `SequentialImpulseEnergyLedger`'s and `TestMassData`'s own class docs.
  Resolving it would require tracing Rapier's own (external, vendored) mass-properties
  computation, not yet attempted.
- `BuoyantForces.apply`'s stale-per-tick-velocity-snapshot behavior for its per-float drag
  calculation is reported as a confirmed fact only - no fix proposed or made; whether it should
  change is a separate design decision, out of scope here.
- `ProposedBoundedDragIntegration` is a reviewable design, not an activated path - wiring
  anything from this investigation into `BuoyantForces.apply` remains a separate, explicitly
  out-of-scope step requiring its own review.
- No native run performed or requested this cycle either - native hold remains CLOSED pending a
  new explicit coordinator release.

Compiled and tested under the exclusive Gradle lock (`/tmp/div-gradle-slot.lock`/
`/tmp/div-gradle-slot.owner`, labels `M545-54700-fix-<timestamp>`/`M545-54700-fix2-<timestamp>`):
`BuoyancyTest` (48 tests), `SequentialImpulseEnergyLedgerTest` (10 tests),
`ProposedBoundedDragIntegrationTest` (5 tests), `PhysicsBridgeClassificationTest`,
`LifecycleInvariantsTest` all green, 0 failures/errors.

### Disposition

Two real ledger bugs (direction-normalization inconsistency, arm-vs-COM contract) fixed and
regression-tested; genuine rotated-frame covariance, independent-external-energy, nonzero-COM,
non-unit-direction, and incident-scale-linear-and-angular multi-float tests added; confirmed as
fact (not fixed) that `BuoyantForces.apply`'s per-float drag calculation uses one stale per-tick
velocity snapshot, independent of native application synchronicity; softened an overclaim that
sequential application was the ONLY valid multi-float vector model; delivered a real, tested,
UNWIRED production-integration design (`ProposedBoundedDragIntegration`) addressing fresh-
velocity timing, drag-direction preservation, and buoyancy separation, for review only. No
production wiring, no native run performed or requested - native hold remains CLOSED pending a
new explicit coordinator release.
