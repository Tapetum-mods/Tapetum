# Native terrain fixture

Private development fixture, not a replacement for any third-party shaderpack and not included
in the mod JAR. This terrain-only pack deliberately tints block surfaces green while sampling the
actual block atlas and lightmap. The tint makes terrain-program substitution observable.

Expected on the 1.16.5 native path: correctly positioned, textured terrain, alpha-tested leaves,
normal depth occlusion and the same tint on opaque/translucent terrain. Entities, sky and hands
remain vanilla. Changing camera position must not detach terrain from the world. Disabling the
pack restores vanilla drawing; repeated reloads must not leak programs/VAOs/index buffers.

The native path currently accepts only single-color terrain programs with the implemented vertex
and uniform inputs. Extended material attributes, custom textures, other geometry roles, G-buffers,
shadows and screen-space chains are not part of this fixture's coverage. Compilation is not visual
acceptance. The developer must not launch Minecraft automatically to test this pack.
