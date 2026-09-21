# Contributing

Tapetum is open source under the existing LGPL-3.0-only license. Preserve attribution notices;
removing a runtime dependency does not change the provenance of existing code.

## Branches

| Branch | Purpose |
|---|---|
| `26.1` | Default branch and Minecraft 26.1 line |
| `26.2` | Minecraft 26.2 line |
| `future` | Cross-version work that is not ready for a release line |
| `main` | Existing integration branch; do not use it for version-specific fixes |

A change that only affects one Minecraft version goes to that version's branch.
Develop cross-version changes on `future`, then backport or merge them into each affected line.
Use a separate `feature/*`, `fix/*`, or `hotfix/*` branch for non-trivial work.

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
`mc26.1/build/libs/` and `mc26.2/build/libs/`. Each directory contains a production JAR and a
`-sources.jar` containing the version-specific, shared and common sources. Install only the
production artifact. No Iris/Sodium artifact may enter compile/runtime classpaths or production JARs.

## Testing

`build` runs common JUnit tests and headless native-engine contract checks for both versions.
The latter inspect Minecraft bytecode and the built artifacts without initializing the game.
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
