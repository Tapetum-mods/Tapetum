# Tapetum Shaders

A Fabric shaderpack interface using the embedded [Iris](https://github.com/IrisShaders/Iris)
rendering engine and an externally installed Sodium. Iris is credited and retains its own mod ID.
The user authorized this integration on 2026-09-14; no separate Iris installation is needed.

## Current status

The active builds target Minecraft **26.1.2 and 26.2**, with **Iris 1.11.4 and Sodium 0.9.2**.
Tapetum delegates world geometry, shadows, deferred/composite passes and pack settings to Iris.
The old screen-space renderer is no longer registered in the shipped client.

Use the JAR matching the Minecraft version, with Sodium installed. Keep any old external Iris
JAR disabled. The `O` key opens Tapetum's picker; native Iris pack options are available from it.
An unsupported hardware feature is an error, not a successful activation or an approximated image.
Mellow 3.4's default colored-light option requires CUSTOM_IMAGES, unavailable on this Mac;
the integration does not silently change the author's settings or pretend that feature works.

Build and headless verification: `./gradlew build --offline --no-build-cache --console=plain`.
This includes unit tests and embedded-engine archive/API checks, without opening Minecraft.
The user does in-game visual verification. Do not run GUI or GPU harnesses without new permission.

See [the rendering checkpoint](docs/RENDERING-CHECKPOINT.md) for measured results and limitations,
and [third-party notices](third_party/iris/NOTICE.md) for licenses, source archives and provenance.
These builds do not establish compatibility with every pack, GPU, or older Minecraft version.

## Historical Prototype Notes

Everything below documents the earlier independent renderer, not the active embedded engine.
Its listed rendering limitations and old verification claims must not be read as current behavior.
What's here:

- A Fabric mod that builds and loads: `fabric.mod.json`, mixin config, client/main entrypoints.
- Shaderpack discovery: scans `shaderpacks/` for `.zip` files and directories, tolerating one
  wrapping folder (`MyPack-v1/shaders/...`), and does a first parse of `shaders.properties`.
- A persistent config (`config/tapetumshaders.properties`) storing the selected pack and whether
  shaders are enabled.
- An in-game shaderpack screen (default keybind `O`, mirroring Iris): a scrollable list filling the
  window — long names ellipsize, many packs scroll — with the currently-loaded pack highlighted,
  drag-and-drop installation of packs onto the window, a shaders on/off toggle, a button to open the
  shaderpacks folder, and Apply/Done. Picking a row only stages the choice; nothing is written or
  reloaded until Apply or Done, as in Iris. A second keybind (`K`, also mirroring Iris) toggles
  shaders directly from gameplay with a chat confirmation. Both keys are rebindable in Controls.
- A "Shaderpacks..." entry in vanilla's own Video Settings screen — the same entry point Iris and
  Sodium both use — that opens the same screen, so it's discoverable without knowing the keybind.
- Sodium integration (Sodium is required — see below): a "Tapetum Shaders" section in its options
  screen, with a page opening the shaderpack picker and a settings page carrying the shaders
  toggle, registered through Sodium's public config API exactly as Iris does.
- A `RenderingPipeline` extension point and a mixin into `LevelRenderer` that calls into it at the
  start/end of the frame.
- GPU backend detection (`pipeline.backend.GpuBackendType`), logged on startup, so the real
  pipeline can tell OpenGL and Vulkan apart once there's something to tell apart (see below).
- A `CompositeChainPipeline` running deferred/composite/final programs with the pack's own vertex
  shaders, ping-pong render targets, standard and custom uniforms, and an explicit copy back into
  Minecraft's scene texture. The geometry inputs are still approximations.
- **A GLSL preprocessing/patching layer** (`shaderpack.glsl`, in `common/` — no Minecraft
  dependency, so it is unit-tested without a game) that turns real OptiFine-format pack
  source into something a core-profile driver accepts. See *OptiFine/Iris pack compatibility* below
  for what it does, what it was verified against, and what it still cannot do.

What's **not** here yet: drawing geometry with `gbuffers_*`, real shadow maps, complete dimension
support and shaderpack option screens. Compiling the geometry shaders does not mean they render
the world. A pack marked active therefore does not yet guarantee its intended appearance.

## OptiFine/Iris pack compatibility

Real packs are not written in the GLSL a modern core-profile OpenGL context accepts. They target
OptiFine, which compiles them in a compatibility profile and preprocesses them first. Handing such a
file to the driver verbatim fails outright — which is why a pack could previously be discovered,
listed, selected, and still render nothing at all.

`common/src/main/java/dev/tapetum/shaders/shaderpack/glsl/` closes most of that gap, in two steps
run by `ShaderPack.readCompilableProgramSource(...)`:

**1. `GlslIncludeResolver` — `#include` expansion.** `#include` is not part of GLSL; no driver
implements it. This matters more than it sounds: pack entry files are usually stubs. Complementary's
whole `final.fsh` is six lines whose body is a single `#include "/program/final.glsl"`; Bliss' is
two. Without expansion there is essentially no shader to compile. The resolver handles the
root-relative form (`/lib/common.glsl`, resolved against the pack's `shaders/` directory — the form
every pack surveyed uses exclusively) and the file-relative form, expands recursively (Bliss' real
chain is `final.fsh` → `/dimensions/final.fsh` → seven further `/lib/*.glsl`), inlines a file once
per reference the way a textual preprocessor does, and rejects genuine cycles with a readable
message instead of hanging the game.

**2. `GlslCompatPatcher` — compatibility→core rewriting.** Rewrites `varying` → `in`/`out`
(direction depending on shader stage), `attribute` → `in`, `texture2D`/`texture3D`/`textureCube`
and their `Lod`/`Proj`/`Grad` variants → the overloaded core `texture*` forms, and
`gl_FragColor`/`gl_FragData[N]` → declared `out vec4` outputs; raises or inserts `#version` as
needed. It also strips OptiFine's `const int colortex0Format = R11F_G11F_B10F;` pseudo-constants —
these look like GLSL but are configuration OptiFine parses out of the source, and the right-hand
side is not an identifier any compiler can resolve.

**Verified, not assumed.** The rules above were derived from four real open-source packs' actual
source (Complementary Shaders V4, Bliss, AstralCore, Allium), fetched and read rather than recalled.
End to end: Complementary's real `final.fsh` include chain expands from 6 lines to 981, and the
patched result **compiles clean under `glslangValidator` (exit 0)** — a real GLSL compiler, not a
self-check. That test also caught a genuine bug in a first draft (`layout(location = ...)` on a
fragment output requires `#version 330`, not 150), which is why single-output shaders are now
emitted without a layout qualifier.

**What still doesn't work, and why a pack may still render nothing:**

- **Only a handful of uniforms.** The rendered scene *is* bound now — `RenderTarget.getColorTexture()`
  is public and the OpenGL backend exposes the raw handle, so `colortex0`/`gcolor`/`colortex1..3` and
  `viewWidth`/`viewHeight`/`aspectRatio` reach the pack. (An earlier revision of this file claimed
  Blaze3D "does not hand out" the colour target; that was wrong, and checking the actual class rather
  than assuming is what corrected it.) Everything else a pack reads — `frameTimeCounter`,
  `cameraPosition`, the gbuffer matrices, the sun/moon vectors — is still absent, so a pack that
  gates on them behaves as if the world were frozen at the origin.
- **Every `colortex` is the same image.** With no gbuffers chain there are no distinct buffers to
  hand out, so `colortex1` (which Complementary uses for raw albedo and bloom) receives the finished
  scene colour instead. That is deliberately wrong-but-visible: it lets a pack's final pass show
  *something* derived from the frame rather than sampling black.
- **The scene is read through a copy.** A pass cannot sample the render target it draws into —
  that is undefined in OpenGL, and it is exactly why an earlier build compiled, linked, ran, and
  changed nothing on screen. `SceneColourCopy` takes a per-frame copy so the read and the write
  target different textures. Iris does the same thing properly, with a full ping-pong chain.
- **No macro/`#ifdef` evaluation.** This is a line-based rewrite, not a GLSL parse. Packs gate large
  regions on `#ifdef`s whose macros come from `shaders.properties` GUI options (Bliss' `#if
  DEBUG_VIEW == debug_SHADOWMAP` style). Those are passed through to the driver as-is, which mostly
  works, but a pack whose `#ifdef` arms disagree about types can still defeat it.
- **Legacy vertex-stage builtins are not rewritten**: `ftransform()`, `gl_MultiTexCoord0`,
  `gl_TextureMatrix[N]` (all present in AstralCore) have no core equivalent. The final pass uses a
  built-in vertex shader so this does not bite yet, but it will the moment gbuffers programs land.
- **Only `final` runs.** gbuffers/composite/deferred/shadow are still unimplemented, so a pack's
  actual lighting and shading never executes.
- **One dimension at a time.** Programs are read from the pack's `world0/` (Overworld) folder, and
  the pipeline is only rebuilt when the selection changes — so walking into the Nether does not
  switch to the pack's `world-1/` programs. `ShaderDimension` and `ShaderPack.locateProgram` already
  model the lookup; what is missing is reloading on dimension change.

### Things real packs do that a synthetic test never would

Testing against the actual Complementary Unbound r5.9 zip (rather than the tidier GitHub sources)
turned up three problems no hand-written fixture had:

- **Programs live in per-dimension folders.** The release ships `world0/final.fsh`,
  `world-1/final.fsh` and `world1/final.fsh` and *no* root `final.fsh`. Looking only at the shaders
  root found nothing and fell back to vanilla — the pack loaded, was listed, was selected, and did
  nothing. `ShaderPack.locateProgram` now checks `world<id>/` before the root, per the OptiFine
  convention. Include paths stay rooted at `shaders/`: `/program/final.glsl` inside
  `world0/final.fsh` means `shaders/program/final.glsl`, not `shaders/world0/program/final.glsl`.
- **CRLF line endings.** The pack is authored on Windows. Splitting on `\n` left a `\r` on every
  line, which makes a trailing backslash ambiguously a line continuation — the kind of thing that
  compiles on one driver and fails on another.
- **Backslash line continuations, both real and accidental.** Complementary has a genuine multi-line
  `#define printString(...)`, which a line-by-line rewrite would otherwise see in fragments; and an
  ASCII-art banner inside a block comment whose line happens to end in `\`, which GLSL 150 rejects
  outright. Both are handled by splicing continuations first, exactly as a C preprocessor's line
  splicing phase does.

- **The loader must define OptiFine's macros.** Packs gate real code on `#if MC_VERSION >= 260200`,
  `#if IRIS_VERSION >= 10800`, `!defined MC_OS_MAC` — and nothing defines those but the loader. The C
  preprocessor would treat an undefined name in `#if` as 0, but Apple's OpenGL does not: it rejects
  the directive outright with *"syntax error: incorrect preprocessor directive"*, failing the whole
  compile. `ShaderMacros` now supplies `MC_VERSION`, the `MC_OS_*` and `MC_GL_VENDOR_*` flag for the
  running machine, and `IRIS_VERSION`. Note `IS_IRIS` is deliberately **not** defined — this loader
  implements none of Iris' extensions, so packs should take their OptiFine path; `IRIS_VERSION` is
  still defined (as 0) because packs only ever compare it numerically, never with `#ifdef`.

`MC_VERSION`'s encoding (`major * 10000 + minor * 100 + patch`) is pinned by the comparisons packs
ship — Complementary tests `>= 11605` for 1.16.5, `< 12109` for 1.21.9, and `>= 260200` for 26.2,
which only agree under that formula.

This one was found the only way it could be: on the real GPU. `glslangValidator` accepted the same
source that the Apple M1 driver rejected, so a local validator pass is necessary but not sufficient —
the game's own log is the last word.

With all of the above fixed, the user's actual `ComplementaryUnbound_r5.9.zip` expands to 2660 lines
and compiles clean.

### Treating packs as untrusted input

Shaderpacks are downloaded from the internet and opened by the mod, so pack contents are untrusted
input, not merely input. Two concrete holes were found and closed by testing this rather than
assuming it:

- **Arbitrary local file read via `#include` (path traversal).** `#include "/../../secret.txt"`
  resolved outside the pack and inlined the file's contents into the shader source, where it reaches
  the driver and the log. Reproduced live before the fix. `GlslIncludeResolver` now normalizes each
  resolved include and rejects anything that does not stay under the pack's own `shaders/` directory
  — `normalize()` alone is *not* the check, since it collapses `..` quite happily into a path above
  the root. Relative includes that dip through a parent but stay inside the pack still work.
- **Include-expansion bomb (memory exhaustion).** A file that includes the same child twice doubles
  the output per level, so a small, shallow pack could expand exponentially — the cycle check never
  fires, and memory is what runs out. Total expanded output is now capped. Real packs are orders of
  magnitude below the cap: Complementary's chain expands to about 30 KB.

`ShaderpackManager.load` additionally rejects pack names containing path separators. The name
arrives from the persisted config as well as the picker, so a hand-edited `shaderPack=../../..`
would otherwise resolve outside the shaderpacks folder.

Both holes have regression tests, and the live reproductions were re-run against the fixed code.

In short: the source-level barrier is largely handled and verified; the *runtime* barrier (uniforms,
render targets, the other passes) is the remaining work, and it is the larger half. Iris does the
equivalent of step 2 with glsl-transformer, a real GLSL parser, rather than the targeted rewrite
here — the deliberate trade-off is documented in `GlslCompatPatcher`'s class docs.

## Requirements

- Minecraft **26.1.2** or **26.2** (one jar each — see *Supported Minecraft versions*)
- Fabric Loader ≥ 0.19.3
- Java 25

## Building

```
./gradlew build
```

Produces one jar per supported version, both confirmed by an actual local build (`./gradlew build`
and `./gradlew :mc26.2:build` both ran clean end to end, not just "should work"):

- `mc26.1/build/libs/tapetum-shaders-<version>+mc26.1.2.jar`
- `mc26.2/build/libs/tapetum-shaders-<version>+mc26.2.jar`

```
./gradlew :mc26.1:runClient      # or :mc26.2:runClient
```

Launches a dev client for that version — this part genuinely is untested (no display/GPU in this
sandbox), so treat a fresh checkout's first `runClient` as unverified until you've run it locally,
even though the build itself is now confirmed.

The `common` module has no Minecraft or loader dependency, so its behaviour can be exercised
without a game on the classpath at all — its GLSL layer is unit-tested there (`./gradlew :common:test`).

### Toolchains: three Java versions, deliberately

Three different Java versions are in play, and conflating them causes confusing failures:

- **The Gradle daemon runs on Java 21**, pinned in `gradle/gradle-daemon-jvm.properties`. This is
  not arbitrary. Left to default it picks up whatever JDK launched it — Java 26 on this machine —
  and IDE tooling that reads Gradle's compiled build-script cache then fails with *"Unsupported
  class file major version 70"* (70 being Java 26), reporting every build script as broken even
  though the command line builds fine. Pinning the daemon to an LTS release the tooling understands
  fixes that without touching what gets compiled.
- **`common` compiles to Java 21**, since it has no Minecraft dependency and nothing requires more.
- **Each `mcXX` module compiles to Java 25**, which is what Minecraft 26.x itself requires.

The daemon JVM and the compile targets are independent on purpose: that is exactly what Gradle's
toolchain support is for, and it is why the daemon can sit on 21 while the mod is still built as
Java 25 bytecode (verified: `major version: 69`).

A third module, **`mc1.21.11`**, exists on disk but is deliberately left out of
`settings.gradle.kts`'s `include(...)` for now — see *Supported Minecraft versions* below for why.

## OpenGL and Vulkan

Minecraft's rendering layer (`com.mojang.blaze3d.systems`) is already built around backend-agnostic
types — `GpuDevice`, `CommandEncoder`, `RenderPass`, `GpuTexture`, `GpuBuffer` — but as of 26.1.2
the only implementation shipped in the game is OpenGL (`GlBackend`); there is no Vulkan backend to
target yet, and Iris doesn't attempt one either. Iris' actual shaderpack rendering still goes
through its own OpenGL-specific `iris.gl` layer underneath, because OptiFine-format packs need
low-level control (arbitrary multi-target framebuffers, image load/store, SSBOs, per-buffer blend
overrides) that Blaze3D's generic API doesn't expose.

This project follows the same split, deliberately: use Blaze3D's abstract types wherever they're
enough, and put everything else behind a small internal backend interface under `pipeline.backend`
— one implementation for OpenGL now, with room for a Vulkan implementation the day Mojang ships a
real `GpuBackend` for it, instead of a rewrite. `GpuBackendType.detectActive()` already reads which
backend is live from the device rather than assuming OpenGL, and is logged once the client finishes
starting.

`pipeline.backend.gl` (`GlProgram`, `FullScreenTriangle`) is that OpenGL implementation now that
`FinalPassPipeline` needs one: compiling a shaderpack's GLSL at runtime has to go through
`GlStateManager` directly, because `GpuDevice.precompilePipeline` is built for Minecraft's own
statically-known shaders, not arbitrary source discovered from a pack — confirmed by reading how
Iris' own shader compilation works, which does exactly the same thing. `PipelineManager` checks
`GpuBackendType` before ever constructing a `FinalPassPipeline`, so a future Vulkan backend won't
silently get fed OpenGL-specific calls it can't run.

## Sodium compatibility

Sodium is a **required** dependency, declared in `fabric.mod.json`'s `depends` block. Fabric Loader
enforces it before any of this mod's code runs: without Sodium the game stops at Loader's own
missing-dependency screen naming what to install, rather than launching into a broken renderer.

This matches Iris' choice, for the same underlying reason. Sodium replaces Minecraft's chunk
renderer wholesale, so terrain shading — the gbuffers work this pipeline still needs — can only be
built on top of it. Requiring Sodium up front rather than after that work lands is deliberate: a
dependency added in a later version breaks every existing install at update time, whereas one
declared before publication is simply part of what players install on day one.

Sodium remains a `compileOnly` dependency and is never bundled into the jar. That is not a
contradiction — required at runtime, absent from the jar — and it is the correct pairing: shipping a
copy would collide with the player's own installation.

The structural isolation of `compat.sodium.SodiumConfigIntegration` is kept even though it is no
longer load-bearing. It is reachable only through the `sodium:config_api_user` entrypoint, which
nothing but Sodium invokes, so its `net.caffeinemc` references resolve only when Sodium is present.
The dependency declaration now guarantees that anyway; the isolation costs nothing and keeps the
class honest about what it may reference.

Two separate things go under "Sodium compatibility", and only one of them is done:

- **Options-screen integration — done.** Registered through Sodium's public config API
  (`net.caffeinemc.mods.sodium.api.config`), giving the sidebar entry with its own pages, the same
  way Iris appears there.
- **Terrain-rendering compatibility — required by the dependency, not yet done.** Sodium replaces Minecraft's
  chunk renderer wholesale, so a shader loader that shades terrain has to meet it there; Iris
  carries ~24 mixins into Sodium internals (chunk mesh building, vertex formats, render regions,
  chunk renderers) for exactly this. Tapetum doesn't shade terrain at all yet — its one pass is a
  full-screen pass after level rendering — so there is nothing for those mixins to do. This
  becomes real work the moment gbuffers programs land, and it is substantial work.

What the current pass does owe Sodium is not corrupting shared GL state, since both mods drive the
same context: `FinalPassPipeline` unbinds its program and VAO after drawing rather than leaving its
own objects bound behind it.

## Supported Minecraft versions

**26.1.2 and 26.2**, as two separate jars, confirmed by an actual local `./gradlew build`. One jar
cannot span Minecraft versions: the rendering internals a shader loader hooks into are renamed and
reshaped between releases. Iris, the reference implementation, ships separate builds across the same
range this project is walking towards — from 1.16.5 up through the current 26.x line — and between
two adjacent versions meaningfully more than a few files typically differ.

The layout here is built around that reality:

```
common/   version-independent core - shaderpack discovery, config, the pipeline contract.
          No Minecraft, no loader. Every version shares it unchanged, and it is testable
          without a game.
shared/   Minecraft code every *currently included* version compiles as-is (GUI, most of the
          pipeline, the Sodium integration). Not a module - each version module compiles it.
mc26.1/   \ one module and one jar per Minecraft version, holding only what genuinely
mc26.2/   / cannot be shared.
```

Adding a version means adding a module, not forking the project — *when* the target version is
close enough to an existing one to still fit `shared/`'s assumptions. For 26.1.2 → 26.2 the entire
version-specific surface came to **two classes**:

- `VersionCompat` — four lines differ. 26.2 takes a draw-buffer index on
  `GlStateManager._enableBlend/_disableBlend`, and moved the backend name from
  `GpuDevice.getBackendName()` onto `GpuDevice.getDeviceInfo().backendName()`.
- `MixinLevelRenderer` — 26.2 renamed `LevelRenderer.renderLevel` to `render` and dropped its last
  parameter. Worth stressing: a mixin binds at *runtime*, so this one compiles cleanly against the
  wrong version and then fails at launch. Mixin targets have to be checked against each version's
  jar by hand; the compiler will not do it.

Everything else — 19 of 21 classes — is byte-identical between the two jars.

### Status: `mc1.21.11` (parked, not deleted)

1.21.11 — the release immediately preceding 26.1.2, before the calendar renumbering (26.1 "Tiny
Takeover" picked up where it left off in March 2026) — was the obvious next step back, and its
source still exists under `mc1.21.11/`. It's currently **left out of `settings.gradle.kts`'s
`include(...)`** rather than deleted, for two independent reasons, both discovered by actually
building it rather than assuming:

Its build script is named `build.gradle.kts.parked` rather than `build.gradle.kts`: dropping the
module from `settings.gradle.kts` is enough for the Gradle CLI, but IDEs scan the working tree for
build scripts and will configure the project anyway, surfacing the Loom failure below as a permanent
workspace error. Rename it back (and re-add the module to `settings.gradle.kts`) to resume work.

1. **A real Fabric Loom bug**, not a code bug: setting up the 1.21.11 Minecraft jar fails with
   `Failed to process jar when running jar processor: fabric-loom:access-widener - Expected official
   namespace for access widener entry, found: intermediary in mod: fabric-lifecycle-events-v1`. The
   embedded module (`fabric-lifecycle-events-v1` 2.6.15+4ebb5c083e, bundled in
   `fabric-api-0.141.3+1.21.11`) ships its access widener in `intermediary` namespace, which is the
   normal, correct format for a published mod — confirmed by extracting and reading the file
   directly. Something in Loom's jar-in-jar access-widener merge step fails to remap it to `official`
   before validating it. This reproduces identically across Loom 1.15.4, 1.16.3, and 1.17.20 (the
   newest release), and survives a full Loom-cache wipe, so it isn't stale state — root cause not
   yet found. Because Gradle configures every included subproject up front, leaving `mc1.21.11`
   included breaks `./gradlew build` for *every* module, including the working mc26.1/mc26.2 — hence
   parking it out of `include(...)` rather than leaving it half-broken in place.
2. **`shared/`'s GUI package doesn't compile against 1.21.11 at all**, which is a real scope
   correction from what this README said earlier. Checked against Mojang's own official client
   mappings (the `client_mappings` artifact linked from Mojang's version manifest — a plain proguard
   text file; no Loom or decompiler needed to read it) rather than web search this time:
   - `Screen` at 1.21.11 has no `extractRenderState` method at all — the override point is still
     `render(GuiGraphics, int, int, float)`, the classic direct-draw model. `GuiGraphicsExtractor`
     doesn't exist as a type Screen uses. This contradicts what this README said in an earlier
     revision ("the render-state-extraction model was already present by 1.21.8") — that claim came
     from web search and was wrong; the mappings say otherwise for 1.21.11 specifically.
   - `GuiGraphics` at 1.21.11 draws via `drawCenteredString(...)`, `drawString(...)`, `blit(...)` —
     not `centeredText(...)`/`text(...)`, the names `shared/gui` actually calls. `fill(...)` is the
     one drawing method that matches both eras.
   - `AbstractSelectionList.Entry`'s render override is `renderContent(GuiGraphics, int, int,
     boolean, float)` at 1.21.11 — a third name, neither `extractContent` (26.x) nor a guessed plain
     `render`.
   - Two things *do* already match 26.x at 1.21.11, confirmed the same way: `Identifier` already
     exists (`ResourceLocation` does not — that rename predates 1.21.11, not a 26.x thing as this
     README previously guessed), and `ObjectSelectionList.Entry#mouseClicked(MouseButtonEvent,
     boolean)` is already the same signature `shared/` uses.
   - `LevelRenderer.renderLevel`'s real 1.21.11 signature is
     `(GraphicsResourceAllocator, DeltaTracker, boolean, Camera, Matrix4f, Matrix4f, Matrix4f,
     GpuBufferSlice, Vector4f, boolean)` — confirmed and already fixed in `mc1.21.11`'s
     `MixinLevelRenderer`. This differs from mc26.1's signature on three points, not the zero this
     README originally assumed: a plain `Camera` where 26.1.2 has `CameraRenderState`, three
     separate `Matrix4f` parameters where 26.1.2 has collapsed them into one `Matrix4fc`, and no
     trailing `ChunkSectionsToRender` at all (26.1.2 added one).

   `VersionCompat`'s blend-call and backend-name assumptions, and the Fabric API
   `fabric-key-binding-api-v1`/`KeyBindingHelper` rename (→ `fabric-key-mapping-api-v1`/
   `KeyMappingHelper` by 26.1.2), were checked the same way and turned out correct — see the
   "Confirmed" notes in `mc1.21.11/VersionCompat.java`.

**Net effect:** 1.21.11 needs its own fork of `shared/gui` (a `Screen#render`/`GuiGraphics`/
`renderContent`-based `ShaderPackScreen` and `ShaderPackListWidget`, not just a `VersionCompat`/
mixin tweak) *and* a fix for the Loom access-widener bug before it can rejoin `include(...)`. Whoever
picks this back up should start with the Loom bug (it blocks even compiling to test the GUI fork) —
try dropping the jar-in-jar `include()` for just `fabric-lifecycle-events-v1` (keeping it
`implementation`-only) as an untested next thing to try, and check whether that shifts the runtime
dependency (Fabric API becoming a real requirement for that one module, unlike every other version
here) is acceptable.

### Roadmap towards 1.16.5

The goal is Fabric support from 1.16.5 up through 26.2, matching Iris' range — a large, multi-step
walk backwards through Minecraft's rendering-API history, one version-cut at a time. The 1.21.11
work above is the one data point confirmed against real mappings so far; treat everything below as
an unconfirmed starting guess for where the *next* breakpoints after 1.21.11 likely are, given how
wrong the pre-1.21.11 guesses above turned out to be until checked. Use the same technique that
worked here — Mojang's official `client_mappings` file per version, fetched straight from the
version manifest at `piston-meta.mojang.com`, grepped for the exact classes/methods `shared/`
touches — rather than web search, which was the actual source of every wrong guess corrected above:

- **1.20.6 or thereabouts:** `DeltaTracker` likely replaced a plain `float partialTick` parameter
  somewhere in this range — unconfirmed, check both sides of it directly.
- **1.19.4:** `GuiGraphics` itself was introduced around here, replacing raw `PoseStack`-based
  drawing on `Screen`. Below this, expect a third, more different GUI shape, not just renamed
  methods on a similar class.
- **1.16.5:** Fabric's practical floor (Fabric Loader/API support goes back this far).

## License

LGPL-3.0-only (see `LICENSE`), matching the license Iris itself uses.
