# Changelog

## Unreleased

### Fixed

- Restored the complete Java extension Gradle script bundle, not only the first missing file.
- Disabled parallel Gradle execution by default to avoid the JDT LS annotation-processor model
  locking failure; added a terminal-only Tooling API reproducer and verification utility.
- Select Nether and End shader chains instead of always loading Overworld programs.
- Rebuild world-owned pipelines on world changes and release them on disconnect.
- Reset temporal camera history on reload/world changes and reject singular matrices before
  uploading their inverses; invalid camera coordinates and clip planes are guarded as well.
- Added hash-verified recovery for missing Java language server Gradle initialization scripts,
  with tests for exact restoration, hash mismatches, existing files, symlinks and invalid paths.
- Restored standalone shader controls and the vanilla video-settings entry without Sodium.
- Limited the video-settings injection to the preferences section to avoid duplicate entries.
- Preserved pipeline failure details for the shader screen instead of silently reporting success.

### Changed

- Removed retired external-renderer integration sources, dependent tests and the embedded-engine smoke script.
- Published the unfinished 1.16.5 port separately from buildable 26.x branches; no legacy artifact is claimed.

- Reorganized active development into one Minecraft version per Git branch, with common/ and
  fabric/ modules instead of shared/ and parallel mc26.* modules. Preserved existing history.
- Retired the obsolete main branch and completed work branches after preserving their commits;
  active GitHub branches contain no retired root-level guidance files.
- Separated local snapshots, numbered CI builds and explicit release-mode artifact versions.
- Added headless GitHub build workflows and an explicit release checklist, without publishing a release.
- Updated repository navigation, issue templates and read-only Modrinth verification for the new layout.

- Removed obsolete root-level guidance files and consolidated maintainer testing and launcher-import
  procedures in CONTRIBUTING.md. Preserved licensing, attribution and technical verification records.
- Added a phased Eclipse/Gradle import check using the IDE's original initialization scripts,
  complementing annotation-processor model checks. Documented stale Buildship diagnostic recovery
  separately from successful terminal builds; editor verification is still required.
- Aligned the GitHub branch policy with Iris's version-named layout: keep `26.1` as default,
  add the experimental `26.3` line, and retain `26.2`, `future` and existing `main` history.
- Documented work-branch targets and explicit backport/forward-port tracking in the pull request
  template. No historical compatibility branches, runtime dependency changes or releases are implied.
- Added an experimental Minecraft 26.3 OpenGL module, adapting RenderPearl package changes,
  platform actions and render-hook signatures without replacing Mojang's GL state cache.
- Added headless camera-history tests, exact render-hook signature checks and GL bridge linkage checks.
- Report an explicit activation failure for unsupported Vulkan rendering.
- Recorded the requested 1.16.5-26.3 range separately from versions that actually have built artifacts.
- Removed Sodium from the active Minecraft 26.1.2 and 26.2 build and runtime dependencies.
  Tapetum uses its own experimental pipeline; neither Iris nor Sodium is bundled or enabled.
- Archived retired Sodium integrations outside all production and test source sets.
- Labeled the current effect as experimental post-processing, not complete shaderpack rendering.
- Added LGPL license files and source JARs to both versioned builds.
- Expanded headless checks for dependency boundaries, packaged hooks and standalone controls.
- Documented the native-renderer roadmap and safe Modrinth import procedure.

### Known limitations

- Faithful in-world rendering is not complete: world geometry passes, real shadow maps and
  other shaderpack features still require implementation and visual validation.
- Versions older than 26.1.2 are not supported by the current artifacts. Custom dimension mappings
  are also unfinished; vanilla dimension selection is only one part of dimension compatibility.
- Builds and headless tests do not prove shaderpack visual compatibility. No Minecraft window
  or GPU test was launched for this update.
- Modrinth-managed JARs must be imported through the launcher. Direct replacements can cause
  a "needs repair or re-import" error; the read-only verification tool does not install them.
