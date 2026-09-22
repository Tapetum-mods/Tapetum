![banner](https://cdn.modrinth.com/data/cached_images/27ae645c7dc03f035fe4444fe5c81700063576d0_0.webp)

# Tapetum Shaders

An open-source Fabric shader engine with no Iris or Sodium dependency.
Tapetum owns its renderer. Existing LGPL licensing and attribution remain unchanged.

## Status

**Experimental: faithful shaderpack world rendering is not complete.** The current backend runs
screen-space deferred/composite/final passes on approximate scene inputs. Real geometry passes,
accurate G-buffers, shadows and authored pack settings remain under development.
A successful build or an activated pack is not proof of the pack's intended appearance.

## Versions and branches

Each Minecraft line has its own branch and contains only that line's sources and build target.

| Branch | Minecraft | State |
|---|---|---|
| [26.1](https://github.com/Tapetum-mods/Tapetum/tree/26.1) | 26.1.2 | Default development line |
| [26.2](https://github.com/Tapetum-mods/Tapetum/tree/26.2) | 26.2 | Experimental native renderer |
| [26.3](https://github.com/Tapetum-mods/Tapetum/tree/26.3) | 26.3 | Experimental, OpenGL only |
| [future](https://github.com/Tapetum-mods/Tapetum/tree/future) | See its gradle.properties | Shared development, not a release line |

The selected branch's exact target is in `gradle.properties`. The requested 1.16.5-26.3 range
is a goal, not a support claim. See [version status](docs/VERSION-SUPPORT.md).
The active branches are `26.1`, `26.2`, `26.3` and `future`.
Completed work branches and the obsolete `main` branch are removed after their history is preserved.

This organization follows the version-branch and loader-module approach seen in
[Iris](https://github.com/IrisShaders/Iris). Tapetum remains Fabric-only and does not copy Iris's
runtime dependencies, branding, release numbers or compatibility claims.

## Repository layout

- `common/`: Minecraft-independent parsing, configuration, expressions and unit tests (Java 21).
- `fabric/`: Fabric entry points, renderer, Minecraft hooks, resources and headless contract tests (Java 25).
- `docs/`: architecture, development, compatibility and verification records.
- `research/`: retired experiments, excluded from production source sets.
- `tools/`: terminal-only import diagnostics and artifact verification.

There are no parallel `mc26.*` modules or `shared/` source tree in a version branch.
Switch branches to build another Minecraft version; do not change a version number to simulate a port.

## Building

Use the checked-in Gradle wrapper with JDK 21 and JDK 25 installed. The Gradle daemon uses 21.

```sh
./gradlew clean build :fabric:compileGlTestJava --console=plain
```

For cached dependencies, add `--offline --no-build-cache`. This runs unit tests, version-policy
checks and native-engine checks; it compiles but never runs the GPU harness.
Do not launch Minecraft or control desktop applications on the maintainer's Mac without permission.

Artifacts are in `fabric/build/libs/`:
- Local: `tapetum-shaders-0.1.0-snapshot+mc<game>-local.jar`.
- CI: `tapetum-shaders-0.1.0-snapshot+mc<game>-build.<run>.jar`.
- Explicit release build (`-Pbuild.release=true`): `tapetum-shaders-0.1.0+mc<game>.jar`.

The mod version and Minecraft version are separate. Each production JAR has a matching
`-sources.jar`; only the production JAR belongs in a launcher.
A release-mode build does not publish a release or establish stable rendering.

## Installation and controls

Use the launcher import workflow for the JAR matching the actual game version.
Do not overwrite Modrinth-managed files or modify its database.
After import, restart Minecraft to load the new JAR.
`O` opens the shaderpack picker, `K` toggles processing, and Video Settings has a Shaderpacks entry.

- [Modrinth](https://modrinth.com/mod/tapetum-shaders)
- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/tapetum-shaders)
- [GitHub issues](https://github.com/Tapetum-mods/Tapetum/issues)

## Development

- [Contribution and branch workflow](CONTRIBUTING.md)
- [Release checklist and version naming](docs/development/RELEASING.md)
- [Architecture](docs/ENGINE-ARCHITECTURE.md)
- [Native renderer roadmap](docs/NATIVE-RENDERER.md)
- [Rendering checkpoint](docs/RENDERING-CHECKPOINT.md)
- [IDE troubleshooting](docs/IDE-TROUBLESHOOTING.md)
- [Changelog](CHANGELOG.md)
- [Historical prototypes](docs/history/PROTOTYPE.md)
