# Minecraft version support

Requested range: Minecraft Java Edition 1.16.5 through 26.3, Fabric, without Iris or Sodium.
This is the target range, not a compatibility claim. Each active version is built from its own
Git branch using `common/` and `fabric/`; older multi-version builds are historical.

| Version | Current state | Remaining work |
|---|---|---|
| 1.16.5 | Experimental remapped JAR builds, Java 21 required | In-game startup, GUI backport and complete world rendering |
| 1.17.x through 1.20.x | Not ported | Version-family adapters and separate tested artifacts |
| 1.21.x before 1.21.11 | Not ported | Version-family adapters and separate tested artifacts |
| 1.21.11 | Historical prototype only | Resolve Loom/remapping setup and develop a standalone port |
| 26.1 / 26.1.1 | No matching artifact | Port and test separately; the 26.1.2 JAR does not declare them compatible |
| 26.1.2 | Built, headless checks pass | Complete world rendering and in-game validation |
| 26.2 | Built, headless checks pass | Complete world rendering and in-game validation |
| 26.3 | New experimental OpenGL build | Complete world rendering, Vulkan implementation and in-game validation |

An artifact compiling is not proof that a shaderpack renders correctly. Do not broaden the
Minecraft dependency range or rename a JAR to imply support for unported versions. The shared
Minecraft code uses Java 25 APIs/language features; the common module currently targets Java 21.
A legacy port must address these baselines deliberately rather than promising a Java 8-compatible JAR.

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
