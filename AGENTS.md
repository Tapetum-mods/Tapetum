# Modrinth updates

## Rendering direction

Tapetum owns its renderer. Do not depend on, embed, ship, or enable Iris or Sodium.
The project is open source; retain the existing LGPL license and attribution notices.
The native Tapetum pipeline must be the production renderer; never equate shader compilation or
synthetic GPU pixels with correct in-world visuals.

## User's Desktop

The user forbids controlling the Mac or launching Minecraft/windows, including for tests.
Use terminal-only builds and headless checks. Do not run runClient, run-embedded-smoke.rb,
glRegressionTest or GUI/browser automation without renewed explicit permission.
The user performs in-game visual checks; report that limit honestly.

## Installation

The user requests installing updated Tapetum JARs into Modrinth after every successful
code update, not just leaving them in build/libs. Compile and run the relevant tests first.

- mc26.1/build/libs/tapetum-shaders-0.1.0+mc26.1.2.jar goes to
  ~/Library/Application Support/ModrinthApp/profiles/Tapetum/mods/ (game 26.1.2).
- mc26.2/build/libs/tapetum-shaders-0.1.0+mc26.2.jar goes to
  ~/Library/Application Support/ModrinthApp/profiles/Tapetum 26.1.2/mods/ (game 26.2 despite folder name).

Verify the actual profile versions and artifact names before installation if versions change.
Back up replaced JARs outside mods, preserve unrelated mods, and verify installed file hashes.
Modrinth can reject an externally overwritten managed JAR even when its ZIP and hash are valid.
Do not repeat an rsync-only replacement of a managed file or rewrite app.db. Use a supported import
workflow; if that requires the forbidden GUI, prepare verified artifacts for the user to import.
On 2026-09-21 the safety review also rejected direct addition of new JARs to managed profiles.
Do not retry direct filesystem installation; use the official import workflow with user involvement.
Tell the user to restart Minecraft to load an updated JAR. Never present installation or a
successful build as proof of complete shaderpack rendering compatibility.
