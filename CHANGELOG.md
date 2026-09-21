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
