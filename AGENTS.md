# Modrinth updates

## Rendering direction

Tapetum owns its renderer. Do not embed, ship, or enable Iris. Sodium remains an external dependency.
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
  ~/Library/Application Support/ModrinthApp/profiles/Tapetum Shaders (1)/mods/.
- mc26.2/build/libs/tapetum-shaders-0.1.0+mc26.2.jar goes to
  ~/Library/Application Support/ModrinthApp/profiles/Tapetum Sahders 26.2/mods/.

Verify the actual profile versions and artifact names before installation if versions change.
Back up replaced JARs outside mods, preserve unrelated mods, and verify installed file hashes.
Tell the user to restart Minecraft to load an updated JAR. Never present installation or a
successful build as proof of complete shaderpack rendering compatibility.
