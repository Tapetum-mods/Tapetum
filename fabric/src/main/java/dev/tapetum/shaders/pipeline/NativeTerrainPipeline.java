package dev.tapetum.shaders.pipeline;

import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.tapetum.shaders.compat.GlStateManager;
import dev.tapetum.shaders.mixin.AccessorGlBuffer;
import dev.tapetum.shaders.mixin.AccessorGlRenderPass;
import dev.tapetum.shaders.pipeline.backend.gl.GlProgram;
import dev.tapetum.shaders.pipeline.backend.gl.GlShaderCompileException;
import dev.tapetum.shaders.pipeline.backend.gl.NativeTerrainMesh;
import dev.tapetum.shaders.shaderpack.GbufferProgram;
import dev.tapetum.shaders.shaderpack.ShaderDimension;
import dev.tapetum.shaders.shaderpack.ShaderPack;
import dev.tapetum.shaders.shaderpack.SimpleTerrainSources;
import dev.tapetum.shaders.shaderpack.glsl.GlslIncludeException;
import dev.tapetum.shaders.shaderpack.glsl.NativeTerrainVertexAdapter;
import dev.tapetum.shaders.shaderpack.glsl.ShaderMacros;
import dev.tapetum.shaders.uniform.FrameState;
import java.io.IOException;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/** Geometry rendering for packs whose entire active input contract can be supplied on 26.1.2. */
public final class NativeTerrainPipeline implements RenderingPipeline {
    private static final Map<String, Integer> ATTRIBUTES = Map.of(
        "tapetum_Position", GL20.GL_FLOAT_VEC3, "tapetum_Color", GL20.GL_FLOAT_VEC4,
        "tapetum_UV0", GL20.GL_FLOAT_VEC2, "tapetum_UV1", GL20.GL_FLOAT_VEC2);
    private static final Map<String, Integer> UNIFORMS = Map.ofEntries(
        Map.entry("tapetum_ProjectionMatrix", GL20.GL_FLOAT_MAT4),
        Map.entry("tapetum_NormalMatrix", GL20.GL_FLOAT_MAT3),
        Map.entry("tapetum_TextureMatrix[0]", GL20.GL_FLOAT_MAT4),
        Map.entry("gbufferModelView", GL20.GL_FLOAT_MAT4),
        Map.entry("gbufferModelViewInverse", GL20.GL_FLOAT_MAT4),
        Map.entry("gbufferProjection", GL20.GL_FLOAT_MAT4),
        Map.entry("gbufferProjectionInverse", GL20.GL_FLOAT_MAT4),
        Map.entry("cameraPosition", GL20.GL_FLOAT_VEC3),
        Map.entry("texture", GL20.GL_SAMPLER_2D),
        Map.entry("tapetum_texture", GL20.GL_SAMPLER_2D),
        Map.entry("gtexture", GL20.GL_SAMPLER_2D),
        Map.entry("lightmap", GL20.GL_SAMPLER_2D));

    private final Map<GbufferProgram, GlProgram> programs;
    private final NativeTerrainMesh mesh = new NativeTerrainMesh();
    private boolean closed;

    private NativeTerrainPipeline(Map<GbufferProgram, GlProgram> programs) {
        this.programs = Map.copyOf(programs);
    }

    public static NativeTerrainPipeline load(ShaderPack pack, ShaderMacros macros, ShaderDimension dimension)
            throws IOException, GlslIncludeException, GlShaderCompileException {
        var sources = SimpleTerrainSources.read(pack, macros, dimension);
        var compiled = new EnumMap<GbufferProgram, GlProgram>(GbufferProgram.class);
        var resolved = new EnumMap<GbufferProgram, GlProgram>(GbufferProgram.class);
        try {
            for (var entry : sources.entrySet()) {
                var source = entry.getValue();
                GlProgram program = compiled.get(source.program());
                if (program == null) {
                    program = GlProgram.link(pack.getName() + "/" + source.program().programName(),
                        NativeTerrainVertexAdapter.adapt(source.vertex()), source.fragment(), NativeTerrainMesh.ATTRIBUTES);
                    compiled.put(source.program(), program);
                    validateInputs(program);
                }
                resolved.put(entry.getKey(), program);
            }
            return new NativeTerrainPipeline(resolved);
        } catch (IOException | RuntimeException error) {
            compiled.values().forEach(GlProgram::close);
            throw error;
        }
    }

    public static void validateInputs(GlProgram program) throws IOException {
        for (boolean attributes : new boolean[] {true, false}) {
            var expected = attributes ? ATTRIBUTES : UNIFORMS;
            for (var input : program.activeInputs(attributes)) {
                if (!attributes && Set.of("TapetumChunkSection", "TapetumGlobals")
                        .contains(program.uniformBlock(input.name()))) continue;
                int maxSize = input.name().equals("tapetum_TextureMatrix[0]") ? 2 : 1;
                if (!Objects.equals(expected.get(input.name()), input.type()) || input.size() > maxSize) {
                    throw new IOException("Native terrain input not implemented: " + input.name()
                        + " (type " + input.type() + ", size " + input.size() + ")");
                }
                if (attributes && program.attributeLocation(input.name()) != NativeTerrainMesh.ATTRIBUTES.get(input.name())) {
                    throw new IOException("Native terrain attribute location mismatch: " + input.name());
                }
            }
        }
    }

    public boolean draw(AccessorGlRenderPass pass, RenderPipeline pipeline, int baseVertex, int firstIndex,
            int count, VertexFormat.IndexType indexType, int instances) {
        if (closed || pipeline.getVertexFormat() != DefaultVertexFormat.BLOCK
                || pipeline.getVertexFormat().getVertexSize() != 28
                || pipeline.getVertexFormatMode() != VertexFormat.Mode.QUADS
                || instances != 1 || indexType == null) return false;
        GbufferProgram role = pipeline == ChunkSectionLayer.SOLID.pipeline() ? GbufferProgram.TERRAIN_SOLID
            : pipeline == ChunkSectionLayer.CUTOUT.pipeline() ? GbufferProgram.TERRAIN_CUTOUT
            : pipeline == ChunkSectionLayer.TRANSLUCENT.pipeline() ? GbufferProgram.WATER : null;
        GlProgram program = role == null ? null : programs.get(role);
        if (program == null) return false;
        var vertices = pass.tapetum$vertexBuffers()[0];
        var indices = pass.tapetum$indexBuffer();
        if (!(vertices instanceof GlBuffer) || !(indices instanceof GlBuffer)
                || vertices.isClosed() || indices.isClosed()) return false;
        int nativeProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            program.use();
            inheritInputs(program, nativeProgram);
            program.setUniform("tapetum_ProjectionMatrix", FrameState.projection());
            program.setUniform("tapetum_NormalMatrix", new Matrix3f(FrameState.modelView()).invert().transpose());
            program.setUniform("tapetum_TextureMatrix[0]", new Matrix4f());
            program.setUniform("tapetum_TextureMatrix[1]", new Matrix4f().scaling(1f / 256f).translate(8, 8, 0));
            program.setUniform("gbufferModelView", FrameState.modelView());
            program.setUniform("gbufferModelViewInverse", FrameState.modelViewInverse());
            program.setUniform("gbufferProjection", FrameState.projection());
            program.setUniform("gbufferProjectionInverse", FrameState.projectionInverse());
            var camera = FrameState.cameraPosition();
            program.setUniform("cameraPosition", (float) camera.x, (float) camera.y, (float) camera.z);
            mesh.draw(((AccessorGlBuffer) vertices).tapetum$handle(), ((AccessorGlBuffer) indices).tapetum$handle(),
                indexType == VertexFormat.IndexType.SHORT ? GL11.GL_UNSIGNED_SHORT : GL11.GL_UNSIGNED_INT,
                firstIndex, count, baseVertex);
        } finally {
            GlStateManager._glUseProgram(nativeProgram);
        }
        return true;
    }

    public static void inheritInputs(GlProgram program, int nativeProgram) {
        program.inheritUniformBlock(nativeProgram, "ChunkSection", "TapetumChunkSection");
        program.inheritUniformBlock(nativeProgram, "Globals", "TapetumGlobals");
        for (String name : new String[] {"texture", "tapetum_texture", "gtexture"}) {
            program.inheritSampler(nativeProgram, "Sampler0", name);
        }
        program.inheritSampler(nativeProgram, "Sampler2", "lightmap");
    }

    @Override public void beginLevelRendering() { }
    @Override public void finalizeLevelRendering() { }
    @Override public boolean isShaderPackActive() { return !closed; }
    @Override public void destroy() {
        if (closed) return;
        closed = true;
        new HashSet<>(programs.values()).forEach(GlProgram::close);
        mesh.close();
    }
}
