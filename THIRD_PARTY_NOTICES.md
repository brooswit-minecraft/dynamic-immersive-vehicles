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
public derivative works. CI downloads the official CurseForge file as a build
dependency (compile, and, since MINECRAFT-491, transiently on the classpath of
one disposable headless server-boot CI job) and never uploads, bundles, forks,
or modifies it; no CI job publishes this or any other dependency's jar as an
artifact. This project calls IV's public classes as an independent companion;
it does not contain copied IV implementation code.

## Sable 2.0.5

- Upstream: <https://github.com/ryanhcode/sable>
- Publisher Maven: <https://maven.ryanhcode.dev/releases>
- CI coordinate: `dev.ryanhcode.sable:sable-neoforge-1.21.1:2.0.5`
- License: PolyForm Shield License 1.0.0

## Sable Companion 1.6.0

- Upstream: <https://github.com/ryanhcode/sable>
- Publisher Maven: <https://maven.ryanhcode.dev/releases>
- CI coordinate: `dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0`
- License: MIT License (distinct from Sable's PolyForm Shield License; the
  Companion jar's own metadata declares MIT)
- A required runtime dependency of this addon (`neoforge.mods.toml` declares
  `sablecompanion` as required); the addon calls its `math.Pose3d` type
  directly.

These are build dependencies (compile, and, since MINECRAFT-491, transiently
on the classpath of one disposable headless server-boot CI job) and are not
bundled or redistributed. Users must obtain runtime dependencies from their
publishers and comply with their licenses. This inventory documents the
current boundary; it is not legal advice.
