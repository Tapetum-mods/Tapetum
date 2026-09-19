![banner](https://cdn.modrinth.com/data/cached_images/27ae645c7dc03f035fe4444fe5c81700063576d0_0.webp)

# Tapetum Shaders

A Fabric shaderpack interface using the embedded [Iris](https://github.com/IrisShaders/Iris)
rendering engine and an externally installed Sodium. Iris is credited and retains its own mod ID.
The user authorized this integration on 2026-09-14; no separate Iris installation is needed.

## Links

* Visit [Modrinth](https://modrinth.com/mod/tapetum-shaders) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/tapetum-shaders) for downloads!
* No dedicated website yet — check back later.

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
