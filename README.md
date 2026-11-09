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

Known, deliberate exclusions - not silently skipped, each tracked as a
follow-up task rather than faked with the ground solver:

- **Boats and other float-only hulls** (pontoon/skid devices with no wheel or
  track): buoyancy is a different physics domain this bridge does not yet
  model.
- **Rotorcraft** (any installed propeller with `isRotor` set, e.g. a
  helicopter): IV gives rotor-lift propellers their own thrust-vectoring
  solver, a different physics domain this bridge does not model; a rotorcraft
  stays on IV's native physics rather than getting zero wing lift and
  forward-only thrust from the fixed-wing model above.
- **A trailer (a ground vehicle actively hitched behind another)** now gets
  its own server-owned Sable body, jointed to its towing vehicle's body by a
  Sable `GenericConstraintConfiguration` hitch (see `HitchJoint` for the
  anchor-position/locked-axis math and `PhysicsBridge.createHitch`/
  `reconcileHitch` for the lifecycle) - not IV's native hitch position-snap.
  The joint anchors each body at its own `JSONConnection.pos` (the hitch/
  hookup points IV's own connection group already defines), always locks the
  three linear axes (the two anchor points stay coincident - a hitch is not
  a sliding coupling), and locks rotation according to the connection's own
  flags: a `mounted` connection (e.g. a flatbed) is fully rigid, a
  `restricted` one allows only pitch (its own documented contract), and an
  ordinary connection leaves all rotation free - a real ball/pintle hitch,
  with the trailer's own wheels/suspension (the same raycast ground solver
  every other ground vehicle uses) keeping it upright rather than the joint.
  A towing vehicle's own replicated `getRecursiveTowingThrust` impulse
  (`TowingForces`, unchanged) is applied on top of this, so towing is still
  not lighter than it should be on that axis.

  **Ordering dependency, not a classification exclusion:** a trailer only
  gets its own body once its towing vehicle already has one active - the
  joint has to connect to a live body, not to IV's raw position. This is
  normally a same-tick (at most one-tick) wait, not a permanent exclusion;
  until it resolves, the trailer stays on IV's native hitch code exactly as
  before, so there is never a window with both a snap and a Sable body
  moving it (`VehicleForcesMixin` only cancels IV's native forces once
  `PhysicsBridge.active()` actually returns true for that entity). Detaching
  a trailer (or its towing vehicle losing its own body - opt-out, despawn,
  chunk unload) releases the joint without leaking it or touching a body
  Sable has already destroyed; the trailer itself keeps its own body and
  simply becomes an unjointed, free-rolling ground vehicle, the same as any
  other.
  A towed flight vehicle (aircraft/blimp) stays excluded, same as before -
  a hitch joint for the flight domain is not attempted.
- A vehicle whose JSON definition is missing or has a chassis with no finite
  collision volume is left on IV's native physics rather than crashing its
  tick; this is logged once per definition, not per vehicle instance.

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
    }
  }
}
```

`massKg` is optional; omit it to use IV's calculated vehicle mass. A copyable
example is also available at
[`docs/dynamic-immersive-vehicles.example.json`](docs/dynamic-immersive-vehicles.example.json).

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
