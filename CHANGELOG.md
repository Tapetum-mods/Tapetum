# Changelog

## Unreleased

### Minecraft 1.18 Port

- Compile and remap the standalone renderer against Minecraft 1.18 and Fabric API 0.46.6+1.18.
- Verify the indexed terrain adapter, exact render hooks and runtime archive against this release.
- This experimental Java 21 build still requires in-game validation and complete complex-pack rendering.

### Minecraft 1.17.1 Port

- Build and remap against Minecraft 1.17.1 itself, with an exact runtime version constraint.
- Retain the 1.17 indexed terrain adapter and run the same API and artifact checks against 1.17.1.
- This remains experimental, requires Java 21 and needs an in-game validation run.

### Minecraft 1.17 Port

- Adapt the standalone Fabric build, video settings entry and render hooks to Minecraft 1.17.
- Preserve Minecraft's indexed terrain buffers and translucent ordering, with per-layer matrices
  and the game's chunk-offset uniform instead of the 1.16.5 quad draw contract.
- Verify exact draw targets, vertex fields, frame hooks, remapped selectors and archive metadata.
- Require Java 21. This experimental build has not been launched in-game; complex shaderpack
  rendering and the modern pack-settings UI are not complete.

### Native Terrain Milestone

- Added a scoped 1.16.5 terrain-only pipeline that retains pack programs and replaces actual
  block VBO draws, using the game's atlas, lightmap, per-chunk matrices and 32-byte vertex layout.
- Preserve vanilla depth/blending and restore program, texture, VAO and buffer state. Terrain
  ownership is released in a finally block; unsupported inputs are rejected instead of invented.
- Added the missing cutout fallback role, bounded quad-to-triangle indices, typed driver input
  reflection and idempotent program cleanup. Added a private native-terrain fixture and GPU tests.
- This first native path supports only simple, single-color, terrain-only packs. Existing complex
  packs still use experimental post-processing; G-buffers, shadows and full pack fidelity remain
  unfinished. GPU tests are compiled, not executed automatically; no in-game validation is claimed.

### Minecraft 1.16.5 experimental port

- Added a dedicated legacy branch with its own common/ and fabric/ sources.
- Selected the remapping Loom plugin, official game mappings and the 1.16 Fabric API modules.
- Began adapting camera/render hooks, key bindings, legacy matrices and OpenGL state calls.
- Declared a Java 21 runtime baseline. The legacy UI, uniforms and headless contracts remain unfinished.
- Fixed 36 legacy compilation errors in GUI, uniforms, matrix rotations and texture uploads.
- Build and remapping now pass; production mixin selectors are verified in intermediary namespace.
- Added a logging provider and translation/scale matrix regression checks.
- The experimental JAR requires Java 21. In-game startup and faithful shader rendering remain unverified.

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
- This branch targets only Minecraft 1.16.5 with Java 21. Other legacy versions need separate ports.
  Custom dimension mappings are unfinished; vanilla dimension selection is only part of compatibility.
- Builds and headless tests do not prove shaderpack visual compatibility. No Minecraft window
  or GPU test was launched for this update.
- Modrinth-managed JARs must be imported through the launcher. Direct replacements can cause
  a "needs repair or re-import" error; the read-only verification tool does not install them.
