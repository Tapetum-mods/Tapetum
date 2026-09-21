# Changelog

## Unreleased

### Fixed

- Added hash-verified recovery for missing Java language server Gradle initialization scripts,
  with tests for exact restoration, hash mismatches, existing files, symlinks and invalid paths.
- Restored standalone shader controls and the vanilla video-settings entry without Sodium.
- Limited the video-settings injection to the preferences section to avoid duplicate entries.
- Preserved pipeline failure details for the shader screen instead of silently reporting success.

### Changed

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
- Builds and headless tests do not prove shaderpack visual compatibility. No Minecraft window
  or GPU test was launched for this update.
- Modrinth-managed JARs must be imported through the launcher. Direct replacements can cause
  a "needs repair or re-import" error; the read-only verification tool does not install them.
