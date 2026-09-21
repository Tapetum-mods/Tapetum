// Historical decoder tests, excluded from the standalone engine build.
package dev.tapetum.shaders.shaderpack.glsl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SodiumTerrainInputsTest {
    @Test
    void ftransformUsesPackedPositionsAndRealMatrices() {
        String source = GbufferVertexAdapter.adaptForSodiumTerrain(
            "#version 330 core\nvoid main() { gl_Position = ftransform(); }");
        assertTrue(source.contains("uniform mat4 tapetum_ProjectionMatrix;"));
        assertTrue(source.contains("uniform mat4 tapetum_ModelViewMatrix;"));
        assertTrue(source.contains("in uvec2 tapetum_PackedPosition;"));
        assertTrue(source.contains("vec4(tapetum_terrainPosition(), 1.0)"));
        assertFalse(source.contains("in vec3 tapetum_Position;"));
    }

    @Test
    void sharedInputIsDeclaredOnceAndBeforeFunctions() {
        String source = GbufferVertexAdapter.adaptForSodiumTerrain("""
            #version 330 core
            void main() { gl_Position = gl_Vertex + gl_MultiTexCoord1; }
            """);
        assertEquals(1, source.lines().filter(s -> s.contains("in uvec4 tapetum_PackedLight;")).count());
        assertTrue(source.indexOf("in uvec4 tapetum_PackedLight;") < source.indexOf("vec3 tapetum_terrainPosition()"));
        assertFalse(source.contains("tapetum_TexCoordShrink"));
    }

    @Test
    void modernAndLegacyAliasesUseSameDecode() {
        String source = GbufferVertexAdapter.adaptForSodiumTerrain("""
            #version 330 core
            void main() {
                gl_Position = vec4(vaPosition, 1.0) + gl_Vertex;
                vec2 uv = vaUV0 + gl_MultiTexCoord0.xy;
                vec4 color = vaColor + gl_Color;
            }
            """);
        assertEquals(1, source.lines().filter(s -> s.contains("in uvec2 tapetum_PackedPosition;")).count());
        assertEquals(1, source.lines().filter(s -> s.contains("in vec4 tapetum_Color;")).count());
        assertEquals(1, source.lines().filter(s -> s.contains("in uvec2 tapetum_PackedUV;")).count());
        assertFalse(source.contains("vaPosition"));
        assertFalse(source.contains("gl_MultiTexCoord0"));
    }

    @Test
    void independentCoreShaderIsNotReplacedWithFakeInputs() {
        String source = "#version 330 core\nvoid main() { gl_Position = vec4(0.0); }";
        assertEquals(source, GbufferVertexAdapter.adaptForSodiumTerrain(source));
    }

    @Test
    void packExtendedInputsRemainExplicitNotSynthesized() {
        String source = GbufferVertexAdapter.adaptForSodiumTerrain("""
            #version 330 core
            in vec4 at_tangent;
            in vec4 mc_Entity;
            void main() { gl_Position = gl_Vertex; }
            """);
        assertTrue(source.contains("in vec4 at_tangent;"));
        assertTrue(source.contains("in vec4 mc_Entity;"));
        assertFalse(source.contains("at_tangent ="));
        assertFalse(source.contains("mc_Entity ="));
    }

    @Test
    void doesNotRequireLayoutQualifiersFromNewerGlsl() {
        String source = GbufferVertexAdapter.adaptForSodiumTerrain(
            "#version 150\nvoid main() { gl_Position = gl_Vertex + gl_Color + gl_MultiTexCoord0; }");
        assertFalse(source.contains("layout("));
        assertEquals(4, SodiumTerrainInputs.ATTRIBUTE_BINDINGS.values().stream().distinct().count());
    }
}
