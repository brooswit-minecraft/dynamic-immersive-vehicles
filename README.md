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

Known, deliberate exclusions - not silently skipped, each tracked as a
follow-up task rather than faked with the ground solver:

- **Aircraft and blimps** (`motorized.isAircraft` / `isBlimp`): flight
  aerodynamics (lift, drag, control surfaces) is a different physics domain
  this bridge does not yet model.
- **Boats and other float-only hulls** (pontoon/skid devices with no wheel or
  track): buoyancy is a different physics domain this bridge does not yet
  model.
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
