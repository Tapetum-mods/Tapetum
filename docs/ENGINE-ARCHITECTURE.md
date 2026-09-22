# Tapetum Engine Architecture

Tapetum owns its native shader engine; existing license and attribution notices remain.

## Boundary

`ShaderEngine` is the only engine contract exposed to Tapetum's pipeline manager and UI. A backend
may reload the selected pack, synchronize its options, report failures and provide the active
`RenderingPipeline`. No UI or version module may call a third-party engine API directly.

## Native backend milestones

1. Own shaderpack lifecycle and option model.
2. Own GLSL preprocessing and program/link diagnostics.
3. Render terrain and entities through version-specific Minecraft hooks.
4. Provide real G-buffer targets, shadows, composite passes and uniforms.
5. Ship the native backend as the only production renderer after headless and in-game checks pass.

The native implementation must be based on Minecraft/Fabric APIs, GLSL specifications and
independent tests. It must not copy Iris source, bytecode, private implementation details or
branding. Compatibility with OptiFine-format shaderpacks is a format goal, not permission to copy
another engine's implementation.

## Versioning

Each Git version branch builds exactly one Minecraft target. `common/` contains game-independent
parsing, configuration and expressions. `fabric/` contains the renderer and that branch's
Minecraft adapter. Shared fixes must be ported deliberately across the affected branches.
