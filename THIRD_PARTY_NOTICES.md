# Third-party dependencies

This repository is an independent companion mod. It contains no Immersive
Vehicles or Sable source, assets, or binaries, and its build does not package
their jars into the output artifact.

## Immersive Vehicles 24.0.0

- Upstream: <https://github.com/DonBruce64/MinecraftTransportSimulator>
- Official binary: CurseForge file `7926606`,
  `Immersive Vehicles-1.21.1-24.0.0.jar`
- CI coordinate: `curse.maven:minecraft-transport-simulator-286703:7926606`
- License: Immersive Vehicles General Public License v1
  (<https://github.com/DonBruce64/MinecraftTransportSimulator/blob/master/LICENCE.md>)

The IV license reserves ownership, prohibits public redistribution outside its
official CurseForge channel except for its stated exceptions, and restricts
public derivative works. CI therefore downloads the official CurseForge file
only as a compile-time dependency and never uploads, bundles, forks, or modifies
it. This project calls IV's public classes as an independent companion; it does
not contain copied IV implementation code.

## Sable 2.0.5 and Sable Companion 1.6.0

- Upstream: <https://github.com/ryanhcode/sable>
- Publisher Maven: <https://maven.ryanhcode.dev/releases>
- CI coordinates:
  - `dev.ryanhcode.sable:sable-neoforge-1.21.1:2.0.5`
  - `dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0`
- Sable license: PolyForm Shield License 1.0.0

These are compile-time dependencies and are not bundled or redistributed. Users
must obtain runtime dependencies from their publishers and comply with their
licenses. This inventory documents the current boundary; it is not legal advice.
