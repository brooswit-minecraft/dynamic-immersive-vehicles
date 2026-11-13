# Dynamic Immersive Vehicles

Independent experimental companion for Immersive Vehicles 24.0.0, Sable 2.0.5,
NeoForge 21.1.265, and Minecraft 1.21.1. No upstream source, assets, or dependency
jars are modified, copied, bundled, or published here.

> **Unverified prototype:** hosted compilation and focused tire-math/
> classification tests are separate from in-game acceptance. Do not deploy
> this build to an existing world until the runtime checklist in
> [VERIFICATION.md](VERIFICATION.md) passes.

## Scope

Companion physics now applies to every eligible IV vehicle **by default**,
across content packs, with no per-car registration or opt-in. It classifies
each vehicle from IV's own definition at the moment it is needed:

- **Ground vehicles (wheeled or tracked), any wheel/track count.** Not just
  four-wheel cars: one wheel, three wheels, six wheels, and tracked vehicles
  all map their installed ground-contact devices, vehicle mass, and chassis
  bounds to one server-owned Sable rigid body with raycast spring/damper
  suspension and combined tire/track grip. A track-steered vehicle
  (`hasSkidSteer`) turns by differential track force instead of a steerable
  wheel. IV remains responsible for models, seats, controls, persistence, and
  movement packets.
- **Fixed-wing aircraft and blimps** (`motorized.isAircraft` / `isBlimp`,
  with no installed rotor propeller): a server-owned Sable rigid body using a
  separate flight force model instead of the ground solver - wing lift
  (thin-airfoil lift slope with a hard stall cutoff), parasitic+induced drag,
  aileron/elevator/rudder control torques, and throttle-linear thrust from
  any running engine. A blimp with a configured, nonzero `ballastVolume`
  additionally gets mass/gravity-compatible neutral-buoyancy lift plus
  ballast trim - gated and scaled the way IV's own ballast system is, not a
  literal displaced-air calculation on that field's raw number (which is
  normally `emptyMass / 1000`, a small control-authority number, not an SI
  volume sized to displace the vehicle's mass) and not tied to
  `gravityFactor`, whose own IV runtime default actually resolves to full
  gravity when a pack leaves it unset. A blimp with no configured
  `ballastVolume` gets ordinary gravity, matching IV. See
  [`FlightForces`](src/main/java/io/github/brooswitminecraft/div/FlightForces.java)
  for the exact model and its stated simplifications (no post-stall/spin
  behavior, no advance-ratio propeller falloff, no altitude/weather air
  density, no natural weathercock stability beyond rudder input). No wheel or
  track device is required for this category to be eligible.
- **Rotorcraft** (any vehicle with an installed `isRotor` propeller, e.g. a
  helicopter - checked directly against the installed part, independent of
  the `motorized.isAircraft`/`isBlimp` flag, so a rotor-carrying vehicle with
  neither flag set is still eligible as a rotorcraft rather than being
  deactivated or stuck on the ground solver): a server-owned Sable rigid body
  using a separate rotor force model instead of the fixed-wing model above,
  which has no wing for a rotorcraft to generate lift from - collective-pitch
  vertical thrust from throttle, cyclic tilt from elevator/aileron input that
  steers that thrust vector forward/sideways for the hover-to-forward-flight
  transition and separately drives attitude (roll/pitch) torque, and
  rudder-driven yaw/anti-torque authority. Unlike a fixed-wing control
  surface, this attitude and yaw authority scales with available thrust, not
  airspeed, so a helicopter keeps full control hovering at zero airspeed.
  See
  [`RotorForces`](src/main/java/io/github/brooswitminecraft/div/RotorForces.java)
  for the exact model and its stated simplifications (no translational-lift
  efficiency gain in forward flight, no retreating-blade stall, no
  autorotation/engine-out descent, no ground effect, and every installed
  rotor propeller treated as one lumped main lift rotor rather than modeling
  an individual tail rotor's own thrust/mount orientation). No wheel or track
  device is required for this category to be eligible, and an installed
  rotor always takes priority over wheels/tracks for dispatch (a wheeled
  rotor-equipped vehicle still gets rotor physics, not the ground solver).
- **Boats and other float-only hulls** (at least one installed ground device
  with `canFloat`, and no wheel or track, and no installed rotor propeller).
  Each installed float device gets its own Archimedes buoyant force and
  quadratic water drag, sampled at that device's own position against the
  local Minecraft water surface; a hull resting unevenly in the water is
  righted by that per-device force distribution rather than a separate
  stability calculation. Installed non-rotor `PartPropeller`s add forward/
  reverse thrust, steered by the rudder input, allocated per actual
  engine-to-propeller linkage rather than one vehicle-wide signal applied to
  every installed propeller. IV exposes neither that linkage nor a
  propeller's live RPM through its public API, so this bridge reads them the
  same way it already reads `EntityVehicleF_Physics`'s own protected
  `getRecursiveTowingThrust()` for towing (see
  [`PartEngineAccessor`](src/main/java/io/github/brooswitminecraft/div/mixin/PartEngineAccessor.java)/
  [`PartPropellerAccessor`](src/main/java/io/github/brooswitminecraft/div/mixin/PartPropellerAccessor.java),
  version-pinned `@Accessor` Mixins, not an edit to IV's own source). Thrust
  direction comes from the propeller's own public `desiredLinearVelocity`
  (driven by `currentPitch` - a fixed sign for a static-pitch propeller, or
  one swung between forward and reverse by `vehicle.reverseThrustVar` for a
  dynamic-pitch one), never from engine transmission gear state: a propeller
  has no gearbox of its own, so an engine sitting in neutral still drives a
  turning, correctly-pitched propeller, and a dynamic-pitch propeller
  reversed via `reverseThrustVar` thrusts backward even while the
  transmission itself stays in a forward gear (see
  [`Buoyancy.signedPropellerDrive`](src/main/java/io/github/brooswitminecraft/div/Buoyancy.java)).
  A propeller thrusts only while it is underwater, not destroyed/removed
  (`isActiveVar`), not a spare, actually turning (`currentRPM` away from
  zero), and linked to a currently-running engine - an unpowered,
  disconnected, or spare propeller produces no thrust regardless of
  throttle, submersion, or any other engine's signal. The shared per-car
  `propellerForce` budget is split only across the non-rotor propellers
  actually operational this tick (linked, active, turning) - a vehicle with
  one powered propeller and one unpowered one gives the powered propeller
  the full configured thrust, not half of it just because a second,
  unpowered propeller also happens to be installed.
- **Amphibious and float-equipped flight/rotor vehicles** (a drivable wheel/
  track device, a fixed-wing/blimp flag, or a rotor propeller, AND at least
  one `canFloat` device on the same chassis - an amphibious car, a seaplane,
  or a floatplane helicopter). These get *both* domains' forces in the same
  tick, not one at the expense of the other: the ground/flight/rotor forces
  above, plus the same per-float buoyancy/water-drag/underwater-propeller-
  thrust forces applied to their float devices (a rotor propeller's own
  thrust is never double-counted here, since it is excluded from this
  underwater-propeller-thrust pass - see
  [`BuoyantForces`](src/main/java/io/github/brooswitminecraft/div/BuoyantForces.java)).
  There is no separate "amphibious" classification - this is the same ground/
  flight/rotor domain from the bullets above, with buoyancy layered on top
  whenever a float device is present on the chassis.

Known, deliberate exclusions - not silently skipped, each tracked as a
follow-up task rather than faked with an unrelated force model:

- **A towed boat or other float-only hull** (an actively-hitched trailer
  whose own category is the float-only one, not ground/flight/rotorcraft):
  it keeps following IV's own native hitch code instead of getting a hitch
  joint, because `BuoyantBody` (see `VehicleBody`, deliberately minimal)
  exposes no pose/anchor API a `HitchJoint` could connect to - a concrete,
  citable API gap, not an invented restriction. A towed ground, flight, or
  rotorcraft vehicle has no such gap and is jointed normally; see "Trailer/
  hitch coupling" below.
- A vehicle whose JSON definition is missing or has a chassis with no finite
  collision volume is left on IV's native physics rather than crashing its
  tick; this is logged once per definition, not per vehicle instance.

## Trailer/hitch coupling

A vehicle actively hitched behind another (a trailer) is eligible for its
own Sable body on the same terms as any other vehicle **in its own
category** - ground, flight, or rotorcraft, all three sharing one
`PhysicsBridge.Body` implementation - jointed to its towing vehicle's body by
a Sable `GenericConstraintConfiguration` hitch (see `HitchJoint` for the
anchor-position/locked-axis math, `HitchRegistry` for the testable joint-
lifecycle bookkeeping - create/replace/release, by key - and
`PhysicsBridge.createHitch`/`reconcileHitch` for how those two are wired to
a live entity/Sable body), rather than IV's native hitch position-snap. The
joint math itself is domain-agnostic - it only connects two already-
classified bodies by their own `JSONConnection.pos` anchors - so a towed
aircraft/blimp or rotorcraft is jointed exactly the same way a towed ground
vehicle is; a towed boat is excluded instead because its `BuoyantBody` has
no pose/anchor API for the joint to connect to (see the exclusions list
above), not because of anything specific to towing. The joint anchors each
body at its own
`JSONConnection.pos` (the hitch/hookup points IV's own connection group
already defines), always locks the three linear axes (the two anchor points
stay coincident - a hitch is not a sliding coupling), and locks rotation
according to the connection's own flags: a `mounted` connection (e.g. a
flatbed) is fully rigid, a `restricted` one allows only pitch (its own
documented contract), and an ordinary connection leaves all rotation free -
a real ball/pintle hitch, with the towed vehicle's own suspension/
aerodynamics (the same ground/flight solver every other vehicle in that
category uses) keeping it stable rather than the joint.

A towing vehicle's own replicated towing-thrust compensation impulse
(`TowingForces`, its own math unchanged) is still applied on top of this,
so towing is still not lighter than it should be on that axis - but what
gets summed into it changed: `PhysicsBridge.unjointedTowingThrust`
replicates IV's own `getRecursiveTowingThrust` recursion (and its
`mounted`-connection skip) only for a towed vehicle that does **not**
already have its own active hitch joint. A jointed trailer's own engine
thrust is applied directly to its own Sable body by its own step and
already reaches the towing vehicle mechanically through the joint Sable
itself solves; compensating for it again at the towing vehicle would propel
a powered trailer twice (`PhysicsBridge.compensableConnection` is the exact,
unit-tested per-connection decision).

**Ordering dependency, not a classification exclusion:** a towed vehicle
only gets its own body once its towing vehicle already has one active - the
joint has to connect to a live body, not to IV's raw position. This is
normally a same-tick (at most one-tick) wait, not a permanent exclusion;
until it resolves, the towed vehicle stays on IV's native hitch code exactly
as before, so there is never a window with both a snap and a Sable body
moving it (`VehicleForcesMixin` only cancels IV's native forces once
`PhysicsBridge.active()` actually returns true for that entity). Detaching a
towed vehicle (or its towing vehicle losing its own body - opt-out, despawn,
chunk unload) releases the joint without leaking it or touching a body Sable
has already destroyed; the towed vehicle itself keeps its own body and
simply becomes an unjointed, free vehicle in its own category, the same as
any other.

## Configuration

JSON is now **optional per-car tuning**, never an activation prerequisite. A
car with no entry at all still gets physics if its category is eligible. On
first start the mod writes `config/dynamic-immersive-vehicles.json` with an
example entry; the key is the IV definition's `packID:systemName`. The one
activation-relevant field is `enabled`, and it is an opt-**out** only: omit it
(or the whole entry) to use the default behavior above, or set it to `false`
to exclude one specific car even if its category would otherwise qualify.
Setting it to `true` is accepted for backwards compatibility with earlier
opt-in configs but changes nothing beyond what omitting it already does.
Clients and the server must use identical configuration and must restart
after changes.

```json
{
  "vehicles": {
    "examplepack:ordinary_car": {
      "massKg": 1450.0,
      "suspensionTravel": 0.3,
      "friction": 1.0,
      "driveForce": 8000.0,
      "brakeForce": 12000.0,
      "maxSteerDegrees": 30.0
    },
    "examplepack:known_bad_car": {
      "enabled": false
    },
    "examplepack:ordinary_boat": {
      "massKg": 900.0,
      "hullFillFactor": 0.5,
      "waterDrag": 400.0,
      "propellerForce": 6000.0,
      "maxSteerDegrees": 30.0
    }
  }
}
```

`massKg` is optional; omit it to use IV's calculated vehicle mass. The three
fields above a boat/pontoon hull specifically uses - `hullFillFactor` (the
fraction of the chassis bounding-box volume the hull actually displaces when
fully submerged, since a real hull is not a solid box), `waterDrag`, and
`propellerForce` (split evenly across the non-rotor propellers actually
operational this tick - linked to a running engine, active, and turning -
not every installed propeller) - are ignored by a ground/flight vehicle
with no float device, and `suspensionTravel`/`friction`/`driveForce`/
`brakeForce` are ignored by a float-only hull. A copyable example is also
available at
[`docs/dynamic-immersive-vehicles.example.json`](docs/dynamic-immersive-vehicles.example.json).

### Part overrides, templates, and `extends`

Alongside `vehicles`, the config accepts two more top-level maps:

- `parts`, keyed `partPackID:partSystemName` (the part definition's own
  identity, same shape as a vehicle's `packID:systemName`) - for tuning a
  part independently of any one vehicle it happens to be installed on.
  Only `driveForce` is currently supported: an installed, running engine
  part's own `driveForce`, if its part entry sets one, completely replaces
  (not multiplies) the vehicle's own resolved `driveForce` for that
  engine. Setting any other field on a `parts` entry - directly, or
  inherited through `extends` - fails config load/reload loudly (the same
  as an out-of-range value) rather than being silently accepted and
  ignored; every other field has no part-level consumer yet.
- `templates`, an arbitrary-named map of reusable tuning fragments. Any
  `vehicles` or `parts` entry (or another template) can set `"extends":
  "<template name>"` to inherit every field it leaves unset from that
  template (chained through multiple templates if needed); a field the
  entry itself sets always wins over its template. An unknown `extends`
  target or an extends cycle fails config load/reload loudly, the same as
  an out-of-range field.

```json
{
  "templates": {
    "sporty-base": { "friction": 1.2, "driveForce": 10000.0 }
  },
  "vehicles": {
    "examplepack:sporty_car": { "extends": "sporty-base", "massKg": 1250.0 }
  },
  "parts": {
    "examplepack:high_output_engine": { "driveForce": 9500.0 }
  }
}
```

### Hot reload

The server polls the config file's own last-modified time (every few
seconds, not every tick) and, on a real change, re-reads and fully
validates it. A clean reload reports how many vehicle/part overrides it
picked up and is logged; a malformed file (bad JSON, an out-of-range
field, an unknown `extends` target, a cycle) is also logged, as a failure,
and changes **nothing** - the previously-loaded config stays in effect
(last-known-good), so a bad edit while the server is live cannot itself
break anything already running.

A successful reload only live-updates fields every active vehicle already
re-reads every tick: `suspensionTravel`, `friction`, `driveForce`,
`brakeForce`, `maxSteerDegrees`, `lateralGripMultiplier`, `waterDrag`,
`propellerForce`. Fields baked in at vehicle construction - `massKg`,
`hullFillFactor`, `upperMassFraction`, and every `selfRight*` field - are
**not** applied to an already-spawned vehicle; re-enter (or otherwise
respawn) the vehicle to pick up a changed value for one of those. The
reload log names exactly which changed fields, if any, fell into this
"needs respawn" set, rather than claiming everything applied.

## Build

CI uses Java 21 and Gradle 8.8. It resolves the exact official dependency
artifacts at build time and uploads only this project's jar, checksums, and test
reports. A second, bounded `runtime-smoke` job boots a headless dedicated
server with those same pinned dependencies to check NeoForge/Mixin startup
only; see [VERIFICATION.md](VERIFICATION.md) for what it does and does not
prove. To run the same tasks locally with Gradle 8.8:

```text
gradle --no-daemon clean build
```

Never commit jars under `libs/`. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
and [PROVENANCE.md](PROVENANCE.md) before changing dependency handling or making
the repository public.
