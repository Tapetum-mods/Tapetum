# Contributing

Tapetum is open source under the existing LGPL-3.0-only license. Preserve attribution notices.
The renderer must not depend on, embed, ship or enable Iris or Sodium.

## Branches

Use one Minecraft version line per branch, with `common/` and `fabric/` on every active line.

| Branch | Target |
|---|---|
| `26.1` | Default branch; Minecraft 26.1.2 |
| `26.2` | Minecraft 26.2 |
| `26.3` | Minecraft 26.3, experimental OpenGL |
| `future` | Shared development; check gradle.properties for its current baseline |

Create a work branch from the intended target, for example:

```sh
git fetch origin
git switch -c fix/26.3-render-state origin/26.3
```

Open the pull request against that version branch. For shared work, target `future` and track
separate backport/forward-port pull requests for affected version lines. Do not merge a whole
version branch into another to synchronize names: Minecraft adapters and dependency versions differ.
Use `feature/*`, `fix/*`, `hotfix/*` or `refactor/*` work branches.

Historical version branches are created only when real port work exists. Branch names are not
compatibility claims. Preserve history and never force-push version lines.
Remove completed work branches only after their commits are reachable from a retained branch.
The obsolete `main` branch is not part of the active layout.

## Source ownership

`common/` has no Minecraft dependency. `fabric/` owns the loader, Minecraft integration and renderer.
Version-specific APIs stay in `fabric/`. Each version branch contains its own adapter.
No Iris/Sodium dependency may enter compile/runtime classpaths or production JARs.
NeoForge is not implemented; do not add an empty module to imply support.

## Build and tests

Install JDK 21 (daemon/common) and JDK 25 (Fabric):

```sh
./gradlew clean build :fabric:compileGlTestJava --console=plain
ruby tools/repair-java-gradle-init-test.rb
```

Add `--offline --no-build-cache` when dependencies are cached.
`build` runs common JUnit tests, version-policy checks and headless native-engine contracts.
`compileGlTestJava` compiles GPU tests only. Neither CI workflow runs the game or GPU harness.

On the maintainer's Mac use terminal-only checks. Do not control applications, launch Minecraft
or other windows, or use GUI/browser automation without renewed explicit permission.
The maintainer performs in-game visual checks. Record the exact game, pack, driver, settings and
dimensions tested. Compilation and synthetic pixels do not establish faithful world rendering.

For missing IDE-generated init scripts, see [IDE troubleshooting](docs/IDE-TROUBLESHOOTING.md).
After switching version lines, reload Java projects to replace stale IDE module models.

## Releases

See [the release checklist](docs/development/RELEASING.md).
Builds default to snapshots. `-Pbuild.release=true` creates release-named artifacts but does not
publish them. Do not tag or publish unvalidated builds as stable. Changes and release notes are
written in English. Keep the repository default at `26.1`.

## Modrinth

After runtime changes, compile and test first. Artifacts live in `fabric/build/libs/` on each branch.
Use the launcher's supported import workflow; never overwrite/add managed files directly or edit
`app.db`. Preserve unrelated mods and saves; retain replacement backups outside `mods`.

The current profile folders under `~/Library/Application Support/ModrinthApp/profiles/` are
`Tapetum` for game 26.1.2 and `Tapetum 26.1.2` for game 26.2. Names are not authoritative:
verify actual profile metadata and JAR metadata before importing.
No 26.3 profile is selected automatically.

`ruby tools/verify-modrinth-import.rb /absolute/path/to/production.jar` checks the artifact and
profile read-only. If import requires the forbidden GUI, provide verified artifacts for the
maintainer to import. Verify installed hashes when available and restart Minecraft after import.
A successful installation is not shaderpack visual validation.
