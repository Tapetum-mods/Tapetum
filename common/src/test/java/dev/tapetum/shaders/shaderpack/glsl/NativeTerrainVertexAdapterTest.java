package dev.tapetum.shaders.shaderpack.glsl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeTerrainVertexAdapterTest {
    @Test void transformsSectionLocalPositionsUsingNativeBlocks() {
        String source = NativeTerrainVertexAdapter.adapt(GbufferVertexAdapter.adapt(
            "#version 150\nvoid main() { gl_Position = ftransform(); }"));
        assertTrue(source.startsWith("#version 150\n"));
        assertTrue(source.contains("uniform TapetumChunkSection"));
        assertTrue(source.contains("uniform TapetumGlobals"));
        assertTrue(source.contains("vec3(tapetum_ChunkPosition - tapetum_CameraBlockPos)"));
        assertTrue(source.contains("tapetum_TerrainModelView() * vec4(tapetum_Position, 1.0)"));
        assertFalse(source.contains("uniform mat4 tapetum_ModelViewMatrix"));
    }

    @Test void projectionIsDeclaredOnceWhenBothMatrixSpellingsAreUsed() {
        String source = NativeTerrainVertexAdapter.adapt(GbufferVertexAdapter.adapt("""
            #version 330 core
            out vec4 alternate;
            void main() {
                alternate = gl_ModelViewProjectionMatrix * gl_Vertex;
                gl_Position = ftransform();
            }
            """));
        assertEquals(1, source.lines().filter(line -> line.trim().equals("uniform mat4 tapetum_ProjectionMatrix;")).count());
        assertFalse(source.contains("tapetum_ModelViewProjectionMatrix"));
    }

    @Test void doesNotInventMissingNormalsOrMaterials() {
        String source = NativeTerrainVertexAdapter.adapt(GbufferVertexAdapter.adapt("""
            #version 150
            in vec4 mc_Entity;
            out vec3 normal;
            void main() { normal = gl_Normal; gl_Position = ftransform() + mc_Entity; }
            """));
        assertTrue(source.contains("in vec3 tapetum_Normal;"));
        assertTrue(source.contains("in vec4 mc_Entity;"));
    }
}
