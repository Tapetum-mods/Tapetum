# Native Rendering Roadmap

## Direction (2026-09-21)

Own the shaderpack runtime; do not ship or require Iris or Sodium. Continue using Minecraft,
Fabric, LWJGL and OpenGL rather than claiming to implement the game or graphics driver ourselves.
The project is open source under its existing license. No public upload is performed by a build.

## Implemented Foundation

- Independent compilation and packaging for 26.1.2, 26.2 and an experimental OpenGL port for 26.3.
- Native pack selection, O/K controls and one vanilla Video Settings entry.
- Pack loading, include processing, compatibility rewriting and full-screen program chains.
- Shared-state preservation and stop-on-error behavior for the experimental post-processing path.
- Headless checks for dependency absence, packaging, UI injection points and key registration.
- Vanilla dimension program selection, world-owned target recreation and camera-history resets.
- Finite inverse validation and headless render-hook/GL-bridge linkage checks.

Retired external-renderer integrations and their dependent tests have been removed from the tree.
They remain recoverable from Git history, but are neither shipped nor counted as passing native tests.

## Required for Faithful World Rendering

The 1.16.5 branch now has a first draw replacement for simple terrain-only packs, with the native
block atlas, lightmap, model-view/projection matrices and the verified 32-byte block vertex layout.
It rejects unsupported active attributes/uniforms instead of supplying fake material data. A private
fixture and GPU regression tests are included on that branch; GPU/world appearance is unverified.

The 26.1 branch now also has a version-specific terrain draw hook for Minecraft 26.1.2. It uses
the native 28-byte block layout, existing index buffers, section/global UBO ranges and sampler
bindings. Hidden OpenGL tests on Apple M1 verify terrain pixels, alpha discard, depth occlusion,
large-coordinate transforms and 16/32-bit indices against Minecraft's own GLSL block declarations.
This is restricted to terrain-only packs without unsupported active inputs or pack properties.
It does not provide the normals/materials, MRT or shadow passes needed by Complementary.
The 26.2 and 26.3 branches have not received this version-specific hook.

1. Connect actual terrain draw calls to Tapetum geometry programs. Supply positions, lightmap UVs,
   normals, material IDs, mid-UVs and tangents from the real mesh, with tested vertex layouts.
2. Allocate distinct G-buffer attachments and obey format, clear, blending and depth ownership rules.
   Copying the finished vanilla color to every attachment is not equivalent.
3. Render geometry into shadow depth/color targets from the light view. Neutral shadow textures
   and a computed shadow matrix alone do not produce shadows.
4. Route opaque/translucent terrain, entities, block entities, sky, weather, particles and hands
   through their appropriate programs, preserving Minecraft's framegraph and GL state.
5. Implement authored pack options/profiles, conditional passes, custom dimension mappings, custom textures,
   feature negotiation and temporal-buffer lifetime across reloads/resizes/world transitions.
6. Compare native output against reference captures with identical world/camera/time/options, on
   every active version and supported GPU. Add a minimal private pack alongside real-world packs.

No row above is marked complete by dependency removal or a successful GLSL compile. Some packs
require graphics features unavailable on a given driver; reject unsupported requirements with a
useful error rather than substituting a different visual effect. The source hosting site does not
determine compatibility: program features, authored settings and device capabilities do.

## Verification Boundary

The user prohibits desktop control and launching Minecraft. On 2026-09-23 the user explicitly
authorized the hidden-window OpenGL test for this change. No game or other application was controlled.
In-world visual acceptance must be performed by the user; do not report it as done.
