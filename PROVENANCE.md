# Prototype provenance

The prototype was recovered on 2026-11-08 from:

```text
host: taynestation
path: /home/brooswit/Documents/DynamicImmersiveVehicles/dynamic-immersive-vehicles
transport: Tailscale SSH with coordination-server host-key verification
destination host: servyboi
```

Only authored source, resources, tests, build configuration, documentation, and
small helper tools were transferred. The remote `.git/`, `.gradle/`, `build/`,
and `libs/` directories were excluded. In particular, neither
`libs/immersive-vehicles-24.0.0.jar` nor `libs/sable-2.0.5.jar` was transferred.
The remote host did not have a usable `git` executable, so no remote commit or
remote URL could be recovered. The ticket's pre-transfer record stated that no
prototype commit, remote, CI workflow, or publication existed.

## SHA-256 of recovered files before repository edits

```text
719972a628f2d1fd04e9ac520640429fea133c3f0966aafd19e21b6400a00c12  .gitignore
c16730f9546a609bbac127886dc0a9457ed3f58dc4bd1533749991cdcc9fa383  README.md
7c22035ba6f603f08fb8b17033bbe1010044ee45f8b913b783397b144d712476  VERIFICATION.md
3696cd12871b45dc75a230b7e42c62e24b1d8815e049624bfffd5f1924ee698a  build.gradle
9b42739315016e32e7123b99682172c88706f52f87956c90165c6e6ef6fe036a  gradle.properties
ea90a7f00207e319110df6c0e62dca0e6d0c4eb7a85ef912b9db8c69a1a52dbe  settings.gradle
b2113ef2ac46d388adaa94a6b35d513676915fed83cb8c6c8355a4eb8f68972e  src/main/java/io/github/brooswitminecraft/div/DynamicImmersiveVehicles.java
15593951fb920ab7a1d107e00accb09f1c95f93154e77d6ab39149d4c4ff6bf6  src/main/java/io/github/brooswitminecraft/div/Overrides.java
a39c8db8386294df6fb7f1b3a5dfb452fd18ae764e8052b3eb61ea02f9219e6e  src/main/java/io/github/brooswitminecraft/div/PhysicsBridge.java
4abd171edc446a02f38db9f2e1754a7b6a05dfcab859e58e3a3fa8e839473d84  src/main/java/io/github/brooswitminecraft/div/TireForces.java
76623d56d30389914baee774ee9f7e6a4267f8be27f6d6d2c6ebd1e42c302116  src/main/java/io/github/brooswitminecraft/div/mixin/VehicleForcesMixin.java
251e9c2f54ca4d185746abd776e0d4067589d895548f830e76fdd838ea2cb692  src/main/java/io/github/brooswitminecraft/div/mixin/VehicleMovementMixin.java
b38162c301ddac84bdb4e401487f626f1b9817fd1947351857f23cfcc8063247  src/main/java/io/github/brooswitminecraft/div/mixin/WorldAccessor.java
3be1e24d63a8130b011c372e854967f97344f4f94d6ad01cd80ee84aa256b8c9  src/main/resources/META-INF/neoforge.mods.toml
130a8e81bcff7401499496a19dfa9a5e482c3bd116e5ceb259fd1cdc04e6dbb8  src/main/resources/div.mixins.json
22585b1b083ce710e2fd0cd2ef09b219c1220a821692fda610d6999d18f448e6  src/test/java/io/github/brooswitminecraft/div/TireForcesTest.java
490d4cbfe0dc0f701a9b461e07e37dde9572b374cb4744740343bea144f39fe4  tools/TireForcesSmoke.java
8983cb03852753c237c7c5e8cdd965ff23a55119fb8df9fc08775ce7b62528fa  tools/bounded-build.gradle
9c47dfec85fdffacda05415504b160f5a6012fef535ed7585e8befdcbfed0820  tools/java-bounded
```
