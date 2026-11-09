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
- **Boats and other float-only hulls** (at least one installed ground device
  with `canFloat`, and no wheel or track). Each installed float device gets
  its own Archimedes buoyant force and quadratic water drag, sampled at that
  device's own position against the local Minecraft water surface; a hull
  resting unevenly in the water is righted by that per-device force
  distribution rather than a separate stability calculation. Installed
  `PartPropeller`s add forward/reverse thrust, steered by the rudder input,
  allocated per actual engine-to-propeller linkage rather than one vehicle-
  wide signal applied to every installed propeller. IV exposes neither that
  linkage nor a propeller's live RPM through its public API, so this bridge
  reads them the same way it already reads `EntityVehicleF_Physics`'s own
  protected `getRecursiveTowingThrust()` for towing (see
  [`PartEngineAccessor`](src/main/java/io/github/brooswitminecraft/div/mixin/PartEngineAccessor.java)/
  [`PartPropellerAccessor`](src/main/java/io/github/brooswitminecraft/div/mixin/PartPropellerAccessor.java),
  version-pinned `@Accessor` Mixins, not an edit to IV's own source). A
  propeller thrusts only while it is underwater, not destroyed/removed
  (`isActiveVar`), not a spare, actually turning (`currentRPM` away from
  zero), and linked to a currently-running engine - an unpowered,
  disconnected, or spare propeller produces no thrust regardless of
  throttle, submersion, or any other engine's signal. The shared
  per-car `propellerForce` budget is split only across the propellers
  actually operational this tick (linked, active, turning) - a vehicle
  with one powered propeller and one unpowered one gives the powered
  propeller the full configured thrust, not half of it just because a
  second, unpowered propeller also happens to be installed.
- **Amphibious vehicles** (a drivable wheel/track device AND at least one
  `canFloat` device on the same chassis). These get *both* domains' forces
  in the same tick, not one at the expense of the other: the ground solver's
  wheel/track suspension and grip, plus the same per-float buoyancy/water-
  drag/propeller-thrust forces above applied to their float devices. There
  is no separate "amphibious" classification - this is the ground domain
  from the bullet above, with buoyancy layered on top whenever a float
  device is present on the chassis.
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
follow-up task rather than faked with an unrelated force model:

- **Rotorcraft** (any installed propeller with `isRotor` set, e.g. a
  helicopter): IV gives rotor-lift propellers their own thrust-vectoring
  solver, a different physics domain this bridge does not model; a rotorcraft
  stays on IV's native physics rather than getting zero wing lift and
  forward-only thrust from the fixed-wing model above.
- **A vehicle actively hitched behind another** (trailers): it keeps
  following IV's own native hitch code instead of getting a second,
  competing body, so towed trailers are unaffected either way. The towing
  vehicle itself is still eligible on its own merits, and now replicates the
  one force IV's own code would otherwise add for it - the summed engine
  thrust of everything it is towing (`getRecursiveTowingThrust`), applied as
  a center-of-mass impulse each tick (see `TowingForces`) - so towing no
  longer feels lighter than it should on that specific axis.
  **Still out of scope:** the towed vehicle itself still has no Sable body,
  joint, or constraint of its own; it is carried purely by IV's own hitch
  position-snap, not by any coupled rigid-body physics. A real two-body
  coupling (the trailer as its own Sable body, linked to the towing
  vehicle's body by a hitch constraint, with its own mass/suspension/drag)
  is a materially larger follow-up - it needs a constraint/joint on top of
  Sable's existing rigid-body API - and is not attempted here.
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
`propellerForce` (split evenly across installed propellers) - are ignored by
a ground vehicle, and `suspensionTravel`/`friction`/`driveForce`/`brakeForce`
are ignored by a boat. A copyable example is also available at
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
