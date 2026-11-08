# Dynamic Immersive Vehicles

Independent experimental companion for Immersive Vehicles 24.0.0, Sable 2.0.5,
NeoForge 21.1.265, and Minecraft 1.21.1. No upstream source, assets, or dependency
jars are modified, copied, bundled, or published here.

> **Unverified prototype:** hosted compilation and focused tire-math tests are
> separate from in-game acceptance. Do not deploy this build to an existing
> world until the runtime checklist in [VERIFICATION.md](VERIFICATION.md) passes.

## Scope

The prototype supports ordinary four-wheel cars that are explicitly opted in by
`packID:systemName`. It maps installed wheel geometry, vehicle mass, and chassis
bounds, then replaces IV movement for that car with one server-owned Sable rigid
body, raycast spring/damper suspension, and combined tire grip. IV remains
responsible for models, seats, controls, persistence, and movement packets.

Towing, aircraft, boats, tracks, and universal content-pack support are outside
the MVP. This is not the complete Dynamic Vehicles drivetrain, terrain, or fluid
implementation.

## Configuration

On first start the mod writes `config/dynamic-immersive-vehicles.json`. Set
`enabled` to `true` only for the car under test. The key is the IV definition's
`packID:systemName`; clients and the server must use identical configuration and
must restart after changes.

```json
{
  "vehicles": {
    "examplepack:ordinary_car": {
      "enabled": true,
      "massKg": 1450.0,
      "suspensionTravel": 0.3,
      "friction": 1.0,
      "driveForce": 8000.0,
      "brakeForce": 12000.0,
      "maxSteerDegrees": 30.0
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
