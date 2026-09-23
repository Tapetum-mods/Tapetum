package dev.tapetum.shaders.shaderpack.glsl;

/** Bridges section-local vertices to the camera-relative transform used by Minecraft 26.1.2. */
public final class NativeTerrainVertexAdapter {
    private NativeTerrainVertexAdapter() { }

    public static String adapt(String adaptedGbufferSource) {
        String source = adaptedGbufferSource
            .replace("uniform mat4 tapetum_ModelViewMatrix;", "")
            .replace("uniform mat4 tapetum_ProjectionMatrix;", "")
            .replace("uniform mat4 tapetum_ModelViewProjectionMatrix;", "");
        return new FixedFunctionRewriter(source)
            .rewrite("tapetum_ModelViewProjectionMatrix",
                "(tapetum_ProjectionMatrix * tapetum_TerrainModelView())", null)
            .rewrite("tapetum_ModelViewMatrix", "tapetum_TerrainModelView()", null)
            .require("main", """
                uniform mat4 tapetum_ProjectionMatrix;
                layout(std140) uniform TapetumChunkSection {
                    mat4 tapetum_ChunkModelView;
                    float tapetum_ChunkVisibility;
                    ivec2 tapetum_TextureSize;
                    ivec3 tapetum_ChunkPosition;
                };
                layout(std140) uniform TapetumGlobals {
                    ivec3 tapetum_CameraBlockPos;
                    vec3 tapetum_CameraOffset;
                    vec2 tapetum_ScreenSize;
                    float tapetum_GlintAlpha;
                    float tapetum_GameTime;
                    int tapetum_MenuBlurRadius;
                    int tapetum_UseRgss;
                };
                mat4 tapetum_TerrainModelView() {
                    // Subtract integers before conversion to preserve precision far from spawn.
                    vec3 offset = vec3(tapetum_ChunkPosition - tapetum_CameraBlockPos) + tapetum_CameraOffset;
                    mat4 translation = mat4(1.0);
                    translation[3] = vec4(offset, 1.0);
                    return tapetum_ChunkModelView * translation;
                }
                """)
            .apply().finish("native terrain transforms");
    }
}
