# Verification, 2026-11-08

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
(`isFlightVehicle`), still excluded by an explicit opt-out or an active tow.
A rotorcraft (any installed `isRotor` propeller, see `isRotorcraft` below)
is eligible the same way, checked directly against the installed part
before the `isAircraft`/`isBlimp` flag or ground devices are even
considered - a rotor-carrying vehicle with neither flag set, or one with
wheels, is still eligible as a rotorcraft rather than deactivated or stuck
on the ground solver (a gap the first actual Wroosbit review found; see the
production-seam section below for the full fix). `PhysicsBridge.Body`
routes it to the rotor force model instead of the fixed-wing model, which
has no wing for a rotorcraft to act on.

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
(`Body.floatCapable`, computed once at construction from the vehicle's
installed parts at that time - this one field is not re-derived per tick
the way the rotor/float-propeller-radius decision inside `stepRotor` is,
since a float device being physically bolted on or off mid-flight is not a
case this bridge treats as a realistic "missing/changed parts" scenario the
way an installed propeller is). This is not a new classification: it is the
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
- A trailer actively hitched behind a car continues to follow via IV's own
  hitch code, with no double movement, whether or not the towing car has
  Sable physics.
- A car towing a trailer (empty, then loaded/with its own engine where the
  pack allows it) accelerates and holds speed noticeably closer to IV's own
  unmodified behavior than before this change, since `getRecursiveTowingThrust`
  is now replicated as a center-of-mass impulse on the towing vehicle's Sable
  body (`TowingForces`/`VehicleTowingAccessor`) - see README.md "Scope" for
  what remains unaddressed (the trailer's own coupled body/constraint).

No compatible vehicle pack or in-game run is currently recorded, so there is no
playable-MVP claim for any category above - ground, flight, or boat. The
flight force model in particular (`FlightForces`/`PhysicsBridge.stepFlight`)
is proven only at the pure-function level above; nothing here has confirmed an
aircraft/blimp is flyable, stable, or even stays airborne in an actual game.
Heavy build/decompile/game work must not run on TayneStation.
