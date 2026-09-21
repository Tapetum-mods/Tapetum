# Contributing

Tapetum is open source under the existing LGPL-3.0-only license. Preserve attribution notices;
removing a runtime dependency does not change the provenance of existing code.

## Branches

Tapetum follows the version-named branch organization visible in
[Iris](https://github.com/IrisShaders/Iris/branches): Minecraft version lines, a `future`
branch and short-lived development branches. The routing rules below are Tapetum's policy;
this does not introduce any Iris or Sodium dependency.

| Branch | Purpose |
|---|---|
| `26.1` | Default branch; Minecraft 26.1 line, currently targeting 26.1.2 |
| `26.2` | Minecraft 26.2 line |
| `26.3` | Experimental Minecraft 26.3 OpenGL port |
| `future` | Cross-version work that is not ready for a release line |
| `main` | Retained legacy integration branch; not the default or a version line |

A change that only affects one Minecraft version goes to that version's branch.
Develop cross-version changes on `future`, then backport or merge them into each affected line.
Use a separate `feature/*`, `fix/*`, or `hotfix/*` branch for non-trivial work.

Create a work branch from its intended target, for example:

```sh
git fetch origin
git switch -c fix/26.3-render-state origin/26.3
```

Open the pull request against `26.3` in this example, not against the default branch.
For a shared change, start from `origin/future` and target `future`; track follow-up pull
requests for each affected version line. Existing pull requests keep their current targets
unless explicitly retargeted after review. Do not merge a version branch into another merely
to synchronize names: inspect API changes and test each affected artifact first.

These are Git development lines, not separate single-module source trees. Tapetum retains its
multi-module build (`common`, `shared`, `mc26.*`); adding a branch does not remove other adapters
or automatically synchronize their histories. Older branches can lag behind development work.
In particular, the new `26.3` line starts from the existing native-engine/26.3 port work.

Create historical version branches only when an actual port is being developed. Do not create
empty `1.16.5` through `1.21.x` branches to imply compatibility. Branch names and successful
builds are not release or visual-validation claims; see [version status](docs/VERSION-SUPPORT.md).
Keep existing branches and history; no force-push, deletion, automatic merge or release tag is
part of adopting this layout.

## Pull requests and releases

- Target the oldest affected version branch first when preparing version-line pull requests.
- Keep version-specific Minecraft APIs out of `common/` and shared code where possible.
- Run `./gradlew clean build --console=plain` and focused regression tests before opening a pull request.
- Merging into `26.2` does not update `26.1` automatically.
- Merge after review, then tag releases from the corresponding version branch.
- Keep `26.1` as the repository default branch.

## Build

Use Java 25 for Minecraft modules and the checked-in Gradle wrapper:

```sh
./gradlew build --console=plain
```

With dependencies already cached, add `--offline --no-build-cache`. Artifacts are in
`mc26.1/build/libs/`, `mc26.2/build/libs/` and `mc26.3/build/libs/`. Each directory contains a production JAR and a
`-sources.jar` containing the version-specific, shared and common sources. Install only the
production artifact. No Iris/Sodium artifact may enter compile/runtime classpaths or production JARs.

## Testing

`build` runs common JUnit tests and headless native-engine contract checks for all three active versions.
The latter inspect Minecraft bytecode and the built artifacts and exercise camera math without starting a client.
`compileGlTestJava` only compiles GPU tests. `glRegressionTest` and `runClient` need an actual
graphics context and are not part of build; do not run them on the maintainer's Mac without consent.

For rendering changes, record the exact game, pack, driver, options and dimensions actually tested.
Compilation is not a visual acceptance test. Do not advertise universal compatibility or change a
pack's authored visual settings silently to make a test pass.

For a missing IDE-generated Gradle initialization script, see
[IDE troubleshooting](docs/IDE-TROUBLESHOOTING.md). Do not disable imports or Java diagnostics to hide it.

## Modrinth

Managed installations need launcher registration as well as correct file bytes. Do not overwrite a
registered JAR via rsync and assume it is installed: Modrinth may mark it as requiring re-import.
Use the launcher import workflow for replacements; do not edit its internal database. Retain backups
outside `mods` and leave saves, pack settings and unrelated mods unchanged.
