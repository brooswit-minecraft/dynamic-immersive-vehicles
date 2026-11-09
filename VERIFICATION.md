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
case, explicit per-car opt-out, pontoon/skid-only (boat) exclusion, a missing
`motorized` section, and a null ground-device entry among installed parts.
Aircraft and blimps are now covered as an eligible category requiring no
ground-contact device at all (`isFlightVehicle`), still excluded by an
explicit opt-out or an active tow. A rotorcraft (any installed `isRotor`
propeller, see `isRotorcraft` below) is eligible the same way - it is still
an aircraft, just one `PhysicsBridge.Body` routes to the rotor force model
(see below) instead of the fixed-wing model, which has no wing for a
rotorcraft to act on.

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
`isRotor`) is the pure predicate `Body`'s constructor uses to pick
`stepRotor` over `stepFlight`. `ClassificationTest` covers: a fixed-wing
aircraft with an ordinary (non-rotor) propeller is not a rotorcraft, a
helicopter (`isRotor` propeller) is both eligible and a rotorcraft, a rotor
among multiple installed propellers still marks rotorcraft, no propellers
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
and that all 92 tests in the repository - `RotorForcesTest` (19 new),
`FlightForcesTest`, the updated `ClassificationTest`, `TireForcesTest`,
`TowingForcesTest`, `OverridesTest`, `BodyRegistryTest`, and
`PhysicsBridgeClassificationTest` - pass under a manually assembled JUnit
Platform launcher (JUnit 5.11.4/platform 1.11.4). This is evidence the new
code compiles and its pure functions
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
- A pontoon/skid-only boat hull continues to use ordinary IV physics
  (documented exclusion, not a defect).
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
- An aircraft or blimp with an installed rotor propeller (a helicopter)
  spawns, can be boarded, and gets Sable rotor physics with no config
  entry: throttle produces collective vertical thrust and can hold a
  hover, elevator/aileron cyclic input tilts that thrust to move forward/
  sideways/backward and banks the fuselage accordingly, and rudder input
  yaws it, all without IV's native rotor-vectoring solver also moving it -
  distinct from a fixed-wing aircraft's wing-based lift and airspeed-
  dependent control authority.
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
