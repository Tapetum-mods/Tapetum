# Embedded Iris Rendering Engine

Tapetum uses unmodified official Iris 1.11.4 Fabric artifacts as nested, replaceable JARs.
Iris remains identified as Iris in Fabric's mod list. It is not represented as Tapetum-authored code.
The player installs Tapetum and Sodium; no separate Iris installation is needed.

Iris authors include coderbot, IMS212, Justsnoopy30, FoundationGames and the contributors listed in
the upstream sources. Project: https://github.com/IrisShaders/Iris . License: LGPL-3.0-only.
The LGPL text and its incorporated GPL text are in this directory's `licenses` folder, and in
`META-INF/licenses/iris/` of the distributed Tapetum JAR. Iris's own notices/resources remain intact.

## Pinned Artifacts

- Minecraft 26.1.2: Modrinth version sZbVsl2Q, iris-fabric-1.11.4+mc26.1.2.jar.
  SHA-512: d99b309d282edb1266dfbdfeda792beba1f808334489604818d6b298c328393dcd7eb5b28a56cea14c9cf8e4eb9c088cb2e80b228b3427a836d154a368b1bcb9
  Sources: Iris commit ceea597944ac0613597eb2e01471fccc0b9318ca (26.1 branch at release).
- Minecraft 26.2: Modrinth version gxZWWnKH, iris-fabric-1.11.4+mc26.2.jar.
  SHA-512: dee955580976aebc92373f5c2d88398ad1019b043c6254a41fe7ab8a6ca3a5ff29d58df2b22ec43a31198beb769e814658eef06fbf980066a2e2c216eeafd32a
  Sources: Iris commit daf5a8bf3a4a303f2b29b23b992723eb206ff545 (26.2 branch at release).

The matching source archive, including upstream build scripts, is shipped in each Tapetum JAR's
`META-INF/sources/`. Tapetum's own Java sources and build scripts remain in this project. Downstream
distribution must retain license/attribution notices and provide the required corresponding sources.
No restriction on modifying/relinking the library or reverse engineering for debugging modifications
is added. The nested Iris JAR can be replaced for development; update Tapetum's explicit compatibility
guard and test the replacement version. Sodium is not bundled.

Iris includes ANTLR, glsl-transformer and JCPP as its own nested dependencies; their artifacts and
notices are retained unchanged. This integration does not remove hardware limitations or guarantee
that every third-party shaderpack is supported.

## Tapetum Runtime Adaptations

The nested binaries are byte-for-byte unmodified. Tapetum applies its own LGPL-3.0-only mixins
at runtime: redirect the O key to its picker, expose Iris's native pack-options view, and reject
unsupported required features before Iris recursively reloads while still parsing a ZIP.
Their sources are in shared/src/main/java/dev/tapetum/shaders/mixin and the version modules.
These adaptations must accompany corresponding-source distributions of the combined application.
