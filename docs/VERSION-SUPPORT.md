# Minecraft version support

Requested range: Minecraft Java Edition 1.16.5 through 26.3, Fabric, without Iris or Sodium.
This is the target range, not a compatibility claim. Each active version is built from its own
Git branch using `common/` and `fabric/`; older multi-version builds are historical.

| Version | Current state | Remaining work |
|---|---|---|
| 1.16.5 | Experimental remapped JAR, Java 21; first terrain-only draw path implemented | In-game validation, GUI backport, complex-pack geometry/G-buffers and shadows |
| 1.17 | Experimental remapped JAR, Java 21; headless checks pass | In-game validation, modern GUI and full rendering |
| 1.17.1 | Experimental remapped JAR, Java 21; headless checks pass | In-game validation, modern GUI and full rendering |
| 1.18 | Experimental remapped JAR, Java 21; headless checks pass | In-game validation, modern GUI and full rendering |
| 1.18.1 | Experimental remapped JAR, Java 21; headless checks pass | In-game validation, modern GUI and full rendering |
| 1.18.2 | Experimental remapped JAR, Java 21; biome-holder adapter and headless checks pass | In-game validation, modern GUI and full rendering |
| 1.19 | Experimental remapped JAR, Java 21; headless checks pass | In-game validation, modern GUI and full rendering |
| 1.19.1 | Experimental remapped JAR, Java 21; headless checks pass | In-game validation, modern GUI and full rendering |
| 1.19.2 | Experimental remapped JAR, Java 21; headless checks pass | In-game validation, modern GUI and full rendering |
| 1.19.3 | Experimental remapped JAR, Java 21; native JOML and menu adapters verified | In-game validation, modern GUI and full rendering |
| 1.19.4 | Experimental remapped JAR, Java 21; headless checks pass | In-game validation, modern GUI and full rendering |
| 1.20 | Experimental remapped JAR, Java 21; GuiGraphics adapter and headless checks pass | In-game validation, modern GUI and full rendering |
| 1.20.1 | Experimental remapped JAR, Java 21; headless checks pass | In-game validation, modern GUI and full rendering |
| 1.20.2 | Experimental remapped JAR, Java 21; renamed terrain hook and headless checks pass | In-game validation, modern GUI and full rendering |
| 1.20.3 | Experimental remapped JAR, Java 21; native list dimensions and headless checks pass | In-game validation, modern GUI and full rendering |
| 1.20.4 | Experimental remapped JAR, Java 21; headless checks pass | In-game validation, modern GUI and full rendering |
| 1.20.5 | Not ported | Version-specific build and validation |
| 1.20.6 | Not ported | Version-specific build and validation |
| 1.21 | Not ported | Version-specific build and validation |
| 1.21.1 | Not ported | Version-specific build and validation |
| 1.21.2 | Not ported | Version-specific build and validation |
| 1.21.3 | Not ported | Version-specific build and validation |
| 1.21.4 | Not ported | Version-specific build and validation |
| 1.21.5 | Not ported | Version-specific build and validation |
| 1.21.6 | Not ported | Version-specific build and validation |
| 1.21.7 | Not ported | Version-specific build and validation |
| 1.21.8 | Not ported | Version-specific build and validation |
| 1.21.9 | Not ported | Version-specific build and validation |
| 1.21.10 | Not ported | Version-specific build and validation |
| 1.21.11 | Historical prototype only | Resolve Loom/remapping setup and develop a standalone port |
| 26.1 | Separate experimental JAR, Java 25; 260 common tests and native contract checks pass | Complete world rendering and in-game validation |
| 26.1.1 | Separate experimental JAR, Java 25; 260 common tests and native contract checks pass | Complete world rendering and in-game validation |
| 26.1.2 | Built, headless checks pass | Complete world rendering and in-game validation |
| 26.2 | Built, headless checks pass | Complete world rendering and in-game validation |
| 26.3 | New experimental OpenGL build | Complete world rendering, Vulkan implementation and in-game validation |

An artifact compiling is not proof that a shaderpack renders correctly. Do not broaden the
Minecraft dependency range or rename a JAR to imply support for unported versions. The 26.x
Minecraft adapter targets Java 25; the common module and the new legacy ports target Java 21.
A legacy port must address these baselines deliberately rather than promising a Java 8-compatible JAR.

## Exact Target List

`minecraft-versions.json` records all 35 requested releases individually, starting at **1.16.5**, not
1.16.4. There is no universal artifact and a branch alone does not establish compatibility.

Run `ruby tools/version-matrix.rb` (or add `--json`) to inspect local exact-version branches and
collected JARs. The check rejects duplicate artifacts and mismatched Minecraft metadata. It reports
archive integrity separately from compilation, launch and shader appearance; none implies the others.
The `26.1.2` branch preserves the previously verified 26.1.2 code under its exact release name.

The new 1.17-1.20.4 ports each pass 243 common tests, the exact-version API/remapping contract and
13 matrix/history checks. GPU test sources compile, but these ports have not been launched in-game.

The 26.1 and 26.1.1 builds each pass 260 common tests, 260 native contract checks and 13 matrix/history
checks. The previous 26.1.2 hidden-window GPU results do not count as GPU validation of these new targets.

As of this update, 21 of the 35 requested releases have an experimental artifact. The 14 releases
from 1.20.5 through 1.21.11 still require separate ports; they are not covered by the other JARs.

## 26.3 adaptation

- Fabric API 0.161.0+26.3 and Loom 1.17.20, with the existing Gradle 9.7.1 wrapper.
- Version-specific aliases call Mojang's real OpenGL state cache after its RenderPearl relocation.
- Device detection, texture IDs and platform actions are isolated behind VersionCompat.
- The render hook matches the new method signature and captures the camera-state view matrix.
- The GLFW test dependency is restricted to the standalone GPU harness; Minecraft itself uses SDL.
- Headless checks verify render-hook descriptors and GL bridge method resolution against the actual game JAR.

References: [Fabric porting notes](https://www.fabricmc.net/2026/09/15/263.html) and
[Minecraft release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3).

## Artifacts

Each active version branch produces artifacts in `fabric/build/libs/`, with a separate sources JAR.
Default builds are `tapetum-shaders-0.1.0-snapshot+mc<version>-local.jar`; CI replaces `local`
with `build.<run>`. Explicit release mode uses `0.1.0+mc<version>`.
Import only the production artifact using the launcher's supported workflow.
No 26.3 Modrinth profile has been selected or modified automatically. No release tag or universal
compatibility claim follows from generating these local artifacts.
