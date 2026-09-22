# Versions and releases

## Independent version numbers

Each branch selects one exact Minecraft release via `minecraftVersion` in `gradle.properties`.
`modVersion` is Tapetum's own semantic version; it is not copied from Iris.
The same mod version may have separate builds for different Minecraft releases.

| Mode | Example |
|---|---|
| Local default | `0.1.0-snapshot+mc26.1.2-local` |
| GitHub build 42 | `0.1.0-snapshot+mc26.1.2-build.42` |
| Explicit release build | `0.1.0+mc26.1.2` |
| Explicit prerelease | `0.2.0-beta.1+mc26.3` |

Use `-Pbuild.release=true` for release-named artifacts. Default builds remain snapshots.
The Fabric metadata version and JAR filename must match. The Minecraft dependency is exact;
changing the filename or widening a dependency range is not a port.

## Workflows

`build.yml` checks pushes and pull requests, runs unit and headless contract tests, compiles
the GPU harness without executing it, and uploads production/source JARs as CI artifacts.
It installs Java 21 and 25 and uses the checked-in Gradle wrapper.

`build-release.yml` can be dispatched manually or triggered by an explicitly published GitHub
release. It performs the same checks in release mode. For a published release, its tag must be
`v<artifact-version>`, for example `v0.1.0+mc26.1.2`.

Both workflows have read-only repository permissions. They do not create tags, attach release
assets, publish to Modrinth/CurseForge, use deployment tokens, or start Minecraft.
CI artifacts are not stable releases. Release publication and uploads remain maintainer actions.

## Checklist

1. Work on the correct version branch; inspect its Minecraft adapter and dependency versions.
2. Update `modVersion` deliberately and write the changes and known limitations in English.
3. Run `./gradlew clean build :fabric:compileGlTestJava -Pbuild.release=true --console=plain`.
4. Run the Ruby regression tests and `ruby tools/verify-artifact.rb fabric/build/libs`.
5. Have the maintainer perform in-world visual acceptance for the exact game, packs and GPU.
6. Review the results and prepare the appropriate alpha/beta/stable release classification.
7. Only after explicit approval, create the version tag and release from that version branch.
8. Attach the production/source JARs and their SHA-256 hashes to the intended release.
9. Import locally through the launcher's supported workflow and restart Minecraft.

Keep LGPL and attribution notices. Do not relabel experimental rendering as stable, invent a
historical Minecraft port, or publish a release merely because a build passed.
