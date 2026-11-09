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
case, explicit per-car opt-out, pontoon/skid-only (boat) exclusion, a missing
`motorized` section, and a null ground-device entry among installed parts.
Aircraft and blimps are now covered as an eligible category requiring no
ground-contact device at all (`isFlightVehicle`), still excluded by an
explicit opt-out or an active tow, and now also excluded when any installed
propeller is a rotor (`isRotor`, see below) - a helicopter must stay on IV's
native physics, not be given wing lift it has no wings for.

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

**Rotorcraft exclusion, added after review.** Review also found
`Classification.eligible` activated every `isAircraft` definition,
including helicopters, while `PhysicsBridge.stepFlight` supplies only wing
lift and forward thrust - a zero-wing-area helicopter got zero lift and
forward-only thrust after IV's native solver was cancelled for it.
`Classification.isRotor(JSONPart.JSONPartPropeller)` now checks IV's own
`isRotor` flag, and `eligible` excludes any flight vehicle with an installed
rotor propeller, leaving it on IV's native physics. `ClassificationTest`
covers: a fixed-wing aircraft with an ordinary (non-rotor) propeller stays
eligible, a rotorcraft is excluded even though `isAircraft` is set, a rotor
among multiple installed propellers still excludes, and a null propeller
list entry is ignored rather than throwing.

A local, non-CI sanity check (`javac --release 21 -proc:none` against the
real `mts.jar`/Sable 2.0.5/joml jars pulled from this host's Gradle cache,
the same API-check approach as the recovered-prototype evidence above, not
a standard Gradle/mod-loader build) confirmed `Classification.java`,
`FlightForces.java`, and the updated `PhysicsBridge.java` compile cleanly
against IV's real `JSONVehicle.motorized` fields (`wingArea`, `wingSpan`,
`aileronArea`, `elevatorArea`, `rudderArea`, `tailDistance`,
`crossSectionalArea`, `ballastVolume`, `dragCoefficient`),
`JSONPart.JSONPartPropeller.isRotor`, and `EntityVehicleF_Physics`'s real
control-input vars, `ballastControlVar`, and `MAX_*_ANGLE` constants, and
that all 50 tests in `FlightForcesTest`, the updated `ClassificationTest`,
`TireForcesTest`, and `TowingForcesTest` pass under a manually assembled JUnit Platform
launcher. This is evidence the new code compiles and its pure functions
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
`.mounted`, `.restricted`), and that all 96 tests across the full suite
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
- A helicopter (an `isAircraft` vehicle with an installed `isRotor`
  propeller) continues to use ordinary IV physics (documented exclusion,
  not a defect) - no wing lift is applied to it.
- A trailer hitched behind a car whose own body is not yet active (opted
  out, or not yet server-side) continues to follow via IV's own hitch code,
  with no double movement, for as long as that ordering dependency holds.
- A trailer hitched behind a car with an active Sable body gets its own
  Sable body jointed to the car's, with no double movement (IV's native
  hitch-following code does not also move it once `PhysicsBridge.active()`
  returns true for the trailer) and no position-snap jitter; the trailer's
  own wheels/suspension respond to terrain independently of the towing car,
  while the hitch keeps the two bodies coupled.
- The joint's locked rotation matches the connection's own flags: an
  ordinary hitch lets the trailer swing/pitch/roll independently (settling
  upright on its own wheels), a `restricted` connection allows only pitch,
  and a `mounted` connection (e.g. a flatbed) stays fully rigid relative to
  the towing vehicle.
- Detaching a trailer at runtime releases the joint; the trailer continues
  as an ordinary free-rolling Sable ground vehicle (coasting, steerable if
  pushed, responsive to terrain) with no stale joint pulling it toward the
  car it was released from.
- The towing car despawning, unloading its chunk, or losing its own Sable
  body for any other reason releases the joint without an error or a
  leaked body; the trailer's own body survives as an unjointed vehicle.
- A car towing an UNPOWERED trailer (no engine) accelerates and holds speed
  noticeably closer to IV's own unmodified behavior than before MINECRAFT-496,
  since that trailer stays unjointed-equivalent for compensation purposes
  (`thrustForceValue` is zero either way) while now also getting real
  suspension/drag from its own coupled body.
- A car towing a POWERED trailer (its own engine, where the pack allows it) is
  propelled by that trailer's thrust exactly once - through the trailer's own
  Sable body and the hitch joint - not twice: the towing vehicle's `TowingForces`
  compensation must not also add the trailer's thrust once the trailer is
  jointed (`PhysicsBridge.unjointedTowingThrust`/`compensableConnection` -
  the MINECRAFT-498 self-review regression this task's own change introduced
  and then fixed). During the brief ordering wait before the trailer has its
  own body, the towing vehicle's compensation still applies (matching
  MINECRAFT-496, unchanged for that window).
- A towed flight vehicle (aircraft/blimp) continues to use ordinary IV
  physics (documented exclusion, not a defect) - this task is ground-domain
  only.
- A towed flight vehicle (aircraft/blimp) continues to use ordinary IV
  physics (documented exclusion, not a defect) - this task is ground-domain
  only.

No compatible vehicle pack or in-game run is currently recorded, so there is no
playable-MVP claim for any category above - ground, flight, or boat. The
flight force model in particular (`FlightForces`/`PhysicsBridge.stepFlight`)
is proven only at the pure-function level above; nothing here has confirmed an
aircraft/blimp is flyable, stable, or even stays airborne in an actual game.
Heavy build/decompile/game work must not run on TayneStation.
