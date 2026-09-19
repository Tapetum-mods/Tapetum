# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Fabric shaderpack loader in the spirit of Iris: it discovers OptiFine/Iris-format shaderpacks,
rewrites their GLSL for a core profile, and runs their screen-space program chain. Published as an
alpha on Modrinth, so breaking changes reach real users.

Sodium is a **required** dependency, not optional — terrain shading can only be built on its chunk
renderer.

## Commands

```bash
./gradlew clean build           # build both version modules + run tests
./gradlew :common:test          # tests only (all of them live in common/)
./gradlew :common:test --tests '*GlslCompatPatcherTest*'   # a single test class
./gradlew :mc26.1:compileJava   # fastest check that a shared/ change compiles
```

**Always `clean build`, never bare `build`.** Gradle's test scanner picks up stray `« Foo 2.class »`
duplicates and fails with `wrong name` errors that read like code regressions. Clear them with:

```bash
find . \( -path "*/build/*" -o -path "*/bin/*" \) -name "* [0-9].class" -delete
```

Installing for a manual test — the jar has to reach a launcher profile, there is no `runClient`
workflow in practice:

```bash
cp mc26.1/build/libs/tapetum-shaders-0.1.0+mc26.1.2.jar \
   ~/Library/Application\ Support/ModrinthApp/profiles/Tapetum\ Shaders\ \(1\)/mods/
```

## Module layout, and why

| Module | Contains | Compiles to |
|---|---|---|
| `common/` | **No Minecraft dependency.** All GLSL parsing, config, expression evaluation. **Every test lives here.** | Java 21 |
| `shared/` | Minecraft-dependent code. Not a module — each `mcXX` adds it as a source directory. | per module |
| `mc26.1/`, `mc26.2/` | Only `VersionCompat` + `MixinLevelRenderer`; everything else comes from `shared/`. | Java 25 |

One jar cannot span Minecraft versions: the rendering internals a shader loader hooks are renamed
between them. **Put logic in `common/` whenever it does not need Minecraft** — that is what makes it
testable without a game, and it is why there are ~200 tests despite no GPU in the loop.

`mc1.21.11/` exists on disk but is deliberately excluded from `settings.gradle.kts`, and its build
script is named `build.gradle.kts.parked` so IDEs do not configure it. A Fabric Loom access-widener
bug blocks it; see the README.

### Three Java versions, deliberately

Gradle daemon on 21 (pinned in `gradle/gradle-daemon-jvm.properties`), `common` targets 21, each
`mcXX` targets 25. Conflating them produces "Unsupported class file major version" errors in IDE
tooling while the CLI builds fine.

**Fabric Loom is pinned per module**, not at the root — Loom releases are not uniformly compatible
with every Minecraft version this targets.

## The render pipeline

`PipelineManager` builds a `CompositeChainPipeline` from every screen-space program a pack ships
(`deferred*` → `composite*` → `final`), or falls back to `VanillaRenderingPipeline` and logs why.

Per pass, `PipelineManager.buildPipeline` assembles:

- **fragment** — `ShaderPack.readCompilableProgramSource` (include expansion + `GlslCompatPatcher`)
- **vertex** — the pack's own `.vsh`, run through `FullScreenVertexAdapter`. Packs ship one for every
  pass and it does real work (BSL computes its sun vectors there); generating a replacement zeroes
  those varyings and blacks the frame. `GlslStageLinkage` is only the fallback now.
- **draw buffers** — `DrawBuffers.parse`, order preserved: fragment output *i* goes to the *i*th name
- **formats** — `ColorTextureFormat.parse`, read from the *patched* source since the patcher comments
  those declarations out
- **custom uniforms** — `CustomUniforms`, parsed from the **raw** properties text

`RenderTargets` double-buffers each `colortex` (a pass samples what it writes; that is undefined in
OpenGL) and honours the pack's declared formats — signed and floating-point ones matter, `RGBA8`
silently clamps normals and HDR.

### Uniforms

`CompositeChainPipeline.bindStandardUniforms` supplies ~49 names. Pack-defined custom uniforms are
uploaded **last** so they shadow loader-supplied ones — that is OptiFine's semantics, and packs rely
on it.

Samplers are bound **depth first**, then colortex, then the pre-1.17 aliases (`gaux1` is `colortex4`,
not `colortex1`), then `noisetex`. Depth gates the sky/terrain branch in every pack, so it must never
lose the texture-unit race.

Texture units past 12 are bound through raw LWJGL rather than `GlStateManager`: 12 is the depth of
vanilla's cache, not an OpenGL limit, and indexing past it throws on the render thread.

## Diagnosing a wrong image

The pipeline logs a **per-pass uniform audit** every time a pack activates: active / not supplied /
ignored, read from `GL_ACTIVE_UNIFORMS`. It costs no GPU stall.

**Believe the audit over any reading of pack source.** Static scans over-count badly — a declaration
inside a disabled `#ifdef` is not an active uniform, and reasoning from the text produced two wrong
diagnoses before the audit settled both.

A heavier per-pass pixel trace is available by setting `diagnosticTrace=true` in
`config/tapetumshaders.properties`, or `-Dtapetum.trace=true`. It stalls the GPU per sample, so it is
off by default.

`glslangValidator` is necessary but **never sufficient** — the Apple M1 driver has rejected sources
it accepted. The game log is the authority.

## Shaderpacks are untrusted input

Packs are downloaded from the internet. `GlslIncludeResolver` confines `#include` to the pack's own
`shaders/` directory and caps total expansion; `ShaderpackManager.load` rejects names containing path
separators. Both holes were real and reproduced before being closed. Keep the regression tests.

## Verifying Minecraft APIs

Mojang **no longer publishes `client_mappings` for 26.x** — `downloads` holds only `client` and
`server`. Use the Loom-remapped jar instead, which is exactly what the modules compile against:

```bash
javap -p -classpath ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/\
minecraft-merged-deobf/26.1.2/minecraft-merged-deobf-26.1.2.jar <class>
```

Several APIs moved in 26.x: `Level.getTimeOfDay()` and `getMoonPhase()` are gone, replaced by
`EnvironmentAttributes` read through the camera's probe, and the camera accessor itself is spelled
differently in 26.1.2 (`getMainCamera()`) and 26.2 (`mainCamera()`) — hence `VersionCompat`.

## Not yet implemented

The `gbuffers_*` programs **are not drawn with yet**. Without them the `colortex` buffers hold a
copy of the finished frame rather than G-buffer data, so a pack's deferred lighting computes from
the wrong input. This is the one remaining reason the image is wrong; no uniform work will change it.

What is in place: `GbufferProgram` (OptiFine's fallback chain), `GbufferVertexAdapter` (the
fixed-function rewrite plus the core-profile spellings Iris introduced), and
`GbufferCompileAudit`, which compiles every program the pack ships on the real driver at pack load,
logs what linked, and deletes them again. It renders nothing and cannot take the frame down —
it exists because `glslangValidator` passing all 125 generated programs is necessary but not
sufficient. It also logs the union of uniforms those programs actually read, from
`GL_ACTIVE_UNIFORMS`, which is the work list for binding them.

Still missing: the extended Sodium vertex format (`at_midBlock` is a fifth attribute), terrain
program substitution through `ShaderChunkRenderer.compileProgram`, MRT terrain with shared depth,
and the non-terrain programs through `ShaderManager.getShader`.

Also absent: the real shadow map (targets are rigged with neutral contents), pack option screens,
Nether/End (the dimension is hardcoded to Overworld), and the 26.2 gbuffers back-end, which needs a
different architecture from 26.1.2.
