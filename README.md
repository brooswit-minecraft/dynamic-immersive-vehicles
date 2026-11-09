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
  with `canFloat`, and no wheel or track - a hull with both stays on the
  ground solver above instead). Each installed float device gets its own
  Archimedes buoyant force and quadratic water drag, sampled at that
  device's own position against the local Minecraft water surface; a hull
  resting unevenly in the water is righted by that per-device force
  distribution rather than a separate stability calculation. Installed
  `PartPropeller`s add forward/reverse thrust, steered by the rudder input,
  but only while the propeller itself is underwater - one above the surface
  produces no thrust, same as a real propeller spinning free in air.

Known, deliberate exclusions - not silently skipped, each tracked as a
follow-up task rather than faked with an unrelated force model:

- **Aircraft and blimps** (`motorized.isAircraft` / `isBlimp`): flight
  aerodynamics (lift, drag, control surfaces) is a different physics domain
  this bridge does not yet model.
- **A vehicle actively hitched behind another** (trailers): it keeps
  following IV's own native hitch code instead of getting a second,
  competing body, so towed trailers are unaffected either way. The towing
  vehicle itself is still eligible on its own merits, but the extra thrust
  IV would normally add to compensate for a trailer's drag is not modeled,
  so towing currently feels lighter than it should.
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
