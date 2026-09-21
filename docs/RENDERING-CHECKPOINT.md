# Rendering checkpoint: 2026-09-21

## 26.3 and IDE Import Update (Current)

Added an experimental 26.3 OpenGL build with Loom 1.17.20 and Fabric API 0.161.0+26.3.
RenderPearl's relocated device/texture/state types and the changed LevelRenderer descriptor are
handled by version-local compatibility code. The state alias delegates to Mojang's existing cache.
Vulkan is detected and rejected with an activation error; it is not implemented by this port.

Native rendering now selects the vanilla dimension's chain, rebuilds targets when the ClientLevel
instance changes, releases targets on disconnect and resets camera history before the next capture.
Finite matrices with singular inverses no longer poison uniform state. Custom dimension mappings,
authored geometry, shadow drawing and full visual fidelity remain unfinished.

The first IDE repair restored only the reported main script. A subsequent actual IDE import exposed
a missing Protobuf init script and then JDT LS issue #3807 (cross-project AP resolution without a
Gradle 9 lock). Batch restoration verified two scripts and restored seven others byte-for-byte.
Parallel builds are now disabled by default. CheckIdeImport reproduced the failure with --parallel
and successfully fetched the annotation-processor model for all five projects with the default
serial configuration. No editor settings, caches or extension implementation were patched.
The IDE's displayed diagnostic was not visually checked; refresh/restart remains a user action.

Final verification:

```sh
./gradlew clean build :mc26.1:compileGlTestJava :mc26.2:compileGlTestJava :mc26.3:compileGlTestJava --offline --no-build-cache --console=plain --init-script <restored-main-script>
ruby tools/repair-java-gradle-init-test.rb
```

- Clean build succeeded: 33 tasks executed; 234 JUnit tests, no failures/errors/skips.
- Native artifact/hook/GL-linkage checks: 212 (26.1.2), 216 (26.2), 215 (26.3).
- Pure frame-state math: 13 checks on each of the three versions.
- Repair helper: 10 tests, 23 assertions, no failures/errors/skips.
- Production and source ZIP integrity checked for all three versions.
- Existing JOML dependency warnings and Gradle deprecation warnings remain.
- GPU harnesses compiled only. No GUI, Minecraft client or GPU test was launched.
- No Modrinth profile was modified; 26.1.2/26.2 targets verified read-only, no 26.3 profile selected.

The requested 1.16.5-26.3 range is not complete. VERSION-SUPPORT.md distinguishes the actual built
artifacts from missing legacy ports. All current artifacts still have experimental post-processing.

## Standalone Update (Earlier on 2026-09-21)

The user now requires no Iris or Sodium dependency. Both active modules compile against Minecraft
and Fabric only, with native Tapetum screen-space rendering. The old Sodium integration, packed
vertex adapter and dependent tests are archived outside all source sets. The Iris-era sections
below are historical, not the current implementation or verification state.

Restored native O/K controls and the vanilla Video Settings entry. Bytecode inspection found three
addSmall invocations; the picker hook now targets only the preferences section (ordinal 2), avoiding
duplicate entries. Shader UI now labels active processing as experimental, not a faithful full render.
Runtime/compile failures are retained for the UI error tooltip. Production JARs include the license;
separate source JARs are generated for both versions. The existing license/provenance is preserved.

Verified without launching Minecraft or opening a window:

`./gradlew clean build :mc26.1:compileGlTestJava :mc26.2:compileGlTestJava --offline --no-build-cache --console=plain`

The clean build caught a changed chat API; after correction, `build` plus both compileGlTestJava
tasks passed. 230 common unit tests passed (zero failures/errors/skips); the four Sodium-specific
tests were retired, not skipped. Headless artifact/API checks: 84 for 26.1.2, 88 for 26.2. Existing
Gradle deprecations remain. GPU tests were compiled only, never executed.

The complete clean build was subsequently rerun successfully with the restored JDT LS init
script passed explicitly via `--init-script`. The IDE failure referenced a missing temporary
resource, not invalid Kotlin build code. Its bytes were recovered from the installed extension
only after matching the filename's SHA-256. The repair helper passed seven tests (14 assertions).
See IDE-TROUBLESHOOTING.md; at this checkpoint the editor still needed to refresh its import.
No desktop interaction or visual verification was performed.

The source directory is again `~/Desktop/Tapetum Shaders/Mods`. The old Downloads path no longer
exists. Neither complete geometry/shadow rendering nor real pack appearance has been validated.
See NATIVE-RENDERER.md for the remaining implementation, not just a testing checklist.

Modrinth targets were rechecked via read-only database queries and game logs: folder `Tapetum`
is game 26.1.2; folder `Tapetum 26.1.2` is game 26.2. Both currently contain Fabric API only.
The safety review rejected direct file installation even for new filenames, due to managed-store
integrity risks. No profile, database, save, or external mod was changed. Production/source JARs
are ready in the module build/libs folders; import the production JARs through Modrinth manually.
`tools/verify-modrinth-import.rb` checks the prepared artifacts and targets without writing anything.

## Historical Notes

## Embedded Engine Update

User approval on September 14 changed the rendering direction: Iris 1.11.4 is now nested inside
Tapetum, with its own identity, licenses and release-era source archives. Sodium stays external.
The former LevelRenderer and VideoSettings mixins are no longer registered; the prototype below
is historical. The active bridge delegates rendering to Iris and drives its native pack options.

On September 15 the user's latest 26.1.2 log still showed the old Tapetum JAR, without Iris loaded.
That installed JAR was 275 KiB versus approximately 4.1 MiB for the new embedded-engine build.
The installed Sodium 0.9.2 binary matched the official Modrinth release SHA-512 (version tZQ3jqnf,
published September 11). Both compile dependencies are now aligned to Sodium 0.9.2.

Headless verification includes 38 archive/API checks per version: engine discovery and identity,
exact nested binary equality, notices/source packaging, accepted/rejected Sodium versions,
absence of test mods and duplicate Sodium, mixin target members and public bridge API signatures.
These checks inspect bytecode without loading Minecraft or starting a graphics context.

Final September 15 verification: `./gradlew build --offline --no-build-cache --rerun-tasks
--console=plain` passed: 234 unit tests (zero failures/errors/skips), plus 38 contract checks
per version. No Java compilation warnings; existing Gradle deprecations remain.
Both production JARs were installed into the corresponding Modrinth profiles, with old JARs
backed up under each profile's `tapetum-backups/20260915-iris-engine-install-01/` (outside mods).
Installed SHA-256 values were verified against build outputs:

- 26.1.2: `5a92de0d433e3fba96bdca12c34f526393c75bdd7f89d0bdfc82452de2047e3b`
- 26.2: `e1fe7ef657790c776d191fd68a8897500bd02caa40fc1b578a06bbb2e164bcb9`

No launcher/game was started, and no original shaderpack, pack setting or save was modified.
The separate Iris JAR remains disabled; the installed Tapetum JAR supplies the embedded engine.

The September 14 isolated 26.1.2 game run, before the user's no-GUI instruction, loaded BSL 10.1.5,
Complementary Reimagined/Unbound 5.9.1 and MakeUp UltraFast 9.5e. The captures were partially
obscured by the pause menu, so this is not a faithful visual comparison. Mellow 3.4 hit a recursive
reload/closed-ZIP failure after requesting unsupported CUSTOM_IMAGES on this Mac. A mixin now
rejects those requirements before recursive reload and preserves the explicit error for the UI.
That latest error-handling patch has only compile/bytecode checks, not a new in-game verification.

The test harness contains a QA-only trial with Mellow's authored COLORED_LIGHTS=false setting;
that trial has not run. No user's pack or options were rewritten. Pack compatibility and hardware
limitations still apply. Minecraft 26.2 has not been visually verified with the embedded engine.

The user forbids controlling the Mac or opening Minecraft/windows, including for tests.
Only terminal builds and headless tests may run; the user performs visual validation.

## Historical Independent Renderer (September 14)

## Scope

The active source directory is `~/Downloads/Tapetum Shaders/Mods`, not the old Desktop path.
This patch stabilizes the existing screen-space chain. It does not implement terrain G-buffers
or establish full OptiFine/Iris shaderpack compatibility.

## Reproduced and corrected

- Minecraft's `GlStateManager.getFrameBuffer(GL_FRAMEBUFFER)` returns zero. Preserve the independent
  read and draw bindings instead, including capture, presentation and diagnostic reads.
- Raw `glActiveTexture` above unit 11 left Minecraft's active-unit cache out of sync. Route unit
  selection through its cache, while only the high-unit texture binding bypasses the short array.
- Inherited sampler objects override texture filtering/comparison. Unbind them for shaderpack
  textures and restore the caller's bindings when the chain finishes.
- The fullscreen triangle discarded the caller's VAO. Restore it and make deletion idempotent.
- Isolate viewport, indexed blend/color masks, clipping tests, textures, samplers and pixel transfer
  state around the chain. Restore state even if a pass throws; stop the failed pipeline once.
- Allocate noise/shadow textures before installing pass inputs, so allocation cannot replace an
  already-bound depth texture on the active unit.
- Upload `viewWidth` and `viewHeight` as floats. Sending integers to BSL's float uniforms generated
  GL_INVALID_OPERATION; the old name-only uniform audit did not detect the failed upload.
- Reject invalid/excessive MRT output lists rather than silently shifting/dropping outputs.

## Verification

`./gradlew clean build --offline --no-build-cache --console=plain`

`./gradlew :mc26.1:glRegressionTest :mc26.2:glRegressionTest --offline --console=plain`

The explicit GL tasks use an invisible GLFW 4.1 core-profile window and the real Minecraft
GlStateManager classes. They do not need a launcher, an account or a world. They are not part of
the default build because GPU/display access is required. Tests include real pixel readbacks,
two-output rendering, ping-pong, high texture units, repeated frames, resize and exception cleanup.
Test code is not shipped in the mod JARs.

Local result for the initial GL-state patch: 224 unit tests passed (zero failures/errors/skips), plus 9 GL regression scenarios
passed for each version, on Apple M1 / OpenGL 4.1 Metal - 91.7. The fallback test intentionally
logs one synthetic rendering exception. Gradle and LWJGL still report upstream deprecations.

## Still unverified or missing

- Actual pack appearance in Minecraft after this patch. GPU micro-tests are not a world-render test.
- Correct geometry inputs: extended Sodium vertex format, terrain program substitution, MRT terrain,
  entities, sky, clouds, particles and hand.
- Real shadow rendering, pack options and Nether/End program selection.
- The cause of the depth samples at 1.0. The local 26.1.2 bytecode imports the main render target
  into the frame graph; it does not establish the handover's transient-target hypothesis. Late debug
  drawing can clear depth, but that alone does not prove it caused the reported frame.
- Conditional draw-buffer selection, uniform type validation beyond the viewport fix, and sampler2D
  versus sampler2DShadow handling need further pack-level verification.
- Iris conflict handling and older Minecraft versions remain unfinished.

Reference for sampler precedence: https://wikis.khronos.org/opengl/GLAPI/glSamplerParameter

## Sodium terrain input milestone (2026-09-14, later update)

- Tapetum remains independent of Iris; Sodium is the rendering dependency. No Iris runtime was added.
- FrameClock now advances once per rendered frame, not per pass. Its frame counter previously stayed
  zero, breaking temporal state and preventing the post-startup audit from running.
- Added a Sodium compact-vertex adapter for gbuffers terrain/solid/water compilation. It decodes
  20-bit positions, section coordinates, UV bias, ABGR color and lightmap inputs. Sodium stores light
  with a +8 texel-center bias; shaderpack fixed-function inputs need that bias removed.
- Attribute locations are bound before linking, so GLSL 150 packs do not need GLSL 330 layout syntax.
- Incomplete vertex/fragment pairs now fall back to a complete pair in the geometry audit.
- The native encoder stays in Sodium. No Sodium encoder or Iris renderer is bundled in Tapetum.

Verification: 234 common tests pass. The GPU harness draws a synthetic quad encoded by the real Sodium
encoder into three render targets, checks all RGBA channels and all 256 section addresses, and covers
negative/fractional local positions and the light/UV conversions. The installed-pack option compiles
all 25 geometry roles in each of the three dimensions of BSL 10.1.5, both Complementary 5.9.1 packs,
MakeUp 9.5e and Mellow 3.4. That is 375 role/dimension checks per Minecraft version, including inherited
programs, not 375 distinct programs or world-render comparisons.

Use `TAPETUM_SHADERPACK_TEST_DIR=/path/to/shaderpacks` with the GL Gradle tasks for the pack sweep.
Optional Gradle properties `sodiumTestJar261=/path/to/installed.jar` and `sodiumTestJar262=...` replace
only the test runtime's Sodium dependency. Both the compile-time Sodium 0.9.1 and installed 0.9.2
have passed the pixel test. The extra private-pack fixture tests GLSL 150 and incomplete-pair fallback.

Still NOT implemented: replacing Sodium's draw programs in a live world, retaining and scheduling
geometry programs, extended normals/tangents/material IDs, raw color/separate AO, terrain MRT depth,
opaque/translucent ordering and actual shadows. The new adapter is used in the compile audit only;
the screen-space pipeline's visuals remain approximate. The two Sodium versions have different
renderer APIs (26.1.2: GL chunk programs; 26.2: Blaze3D RenderPipeline), so the live hooks need separate
version-specific implementations. Do not tell the user this milestone restores full shader visuals.

Upstream interfaces studied:
- https://github.com/IrisShaders/Iris/blob/26.1/common/src/main/java/net/irisshaders/iris/vertices/sodium/terrain/XHFPTerrainVertex.java
- Sodium 0.9.2 installed JARs: ChunkVertexEncoder, CompactChunkVertex, ShaderChunkRenderer,
  assets/sodium/shaders/include/chunk_vertex.glsl.
- License references: https://github.com/IrisShaders/Iris/blob/26.1/LICENSE and
  https://github.com/CaffeineMC/sodium/blob/dev/LICENSE.md (PolyForm Shield, not an unrestricted license).

Delivery: both Modrinth profiles updated after tests, with previous JARs backed up under each profile's
`tapetum-backups/2026-09-14-sodium-terrain-inputs/`. Source/installed SHA-256 pairs verified identical:
- 26.1.2: `5c8d7cfb7b55dfc7591d13fcc5c3f6f8d116c2af57dacf861586bedc45b33d1c`
- 26.2: `db978557c3e8ffb8deec353c689718e02c6352742ed743cb8dc8169024a161e0`
Final verification: 11 standard GL scenarios per version pass with both Sodium 0.9.1 and 0.9.2;
the optional installed-pack sweep also passes for both Minecraft versions. No live-world visual
comparison was performed. Existing Gradle/LWJGL/JOML deprecation warnings remain upstream.
