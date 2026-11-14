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
