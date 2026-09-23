package dev.tapetum.shaders.pipeline;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.tapetum.shaders.compat.LegacyMatrices;
import dev.tapetum.shaders.pipeline.backend.gl.*;
import dev.tapetum.shaders.shaderpack.*;
import dev.tapetum.shaders.shaderpack.glsl.*;
import dev.tapetum.shaders.uniform.FrameState;
import java.io.IOException;
import java.util.*;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/** First native terrain path: single-color, terrain-only packs with fully supplied inputs. */
public final class LegacyTerrainPipeline implements RenderingPipeline {
    private static final Map<String, Integer> UNIFORMS = Map.ofEntries(
        Map.entry("tapetum_ModelViewMatrix", GL20.GL_FLOAT_MAT4),
        Map.entry("tapetum_ProjectionMatrix", GL20.GL_FLOAT_MAT4),
        Map.entry("tapetum_ModelViewProjectionMatrix", GL20.GL_FLOAT_MAT4),
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
    private static final Map<String, Integer> ATTRIBUTES = Map.of(
        "tapetum_Position", GL20.GL_FLOAT_VEC3, "tapetum_Color", GL20.GL_FLOAT_VEC4,
        "tapetum_UV0", GL20.GL_FLOAT_VEC2, "tapetum_UV1", GL20.GL_FLOAT_VEC2,
        "tapetum_Normal", GL20.GL_FLOAT_VEC3);

    private final Map<GbufferProgram, GlProgram> programs;
    private final LegacyTerrainMesh mesh = new LegacyTerrainMesh();
    private GbufferProgram layer;
    private boolean closed;

    private LegacyTerrainPipeline(Map<GbufferProgram, GlProgram> programs) {
        this.programs = Map.copyOf(programs);
    }

    public static LegacyTerrainPipeline load(ShaderPack pack, ShaderMacros macros, ShaderDimension dimension)
            throws IOException, GlslIncludeException, GlShaderCompileException {
        var sources = SimpleTerrainSources.read(pack, macros, dimension);
        var compiled = new EnumMap<GbufferProgram, GlProgram>(GbufferProgram.class);
        var resolved = new EnumMap<GbufferProgram, GlProgram>(GbufferProgram.class);
        try {
            for (var entry : sources.entrySet()) {
                var source = entry.getValue();
                GlProgram program = compiled.get(source.program());
                if (program == null) {
                    program = GlProgram.link(pack.getName() + "/" + source.program().programName(), source.vertex(), source.fragment(),
                        LegacyTerrainMesh.ATTRIBUTES);
                    compiled.put(source.program(), program);
                    validateInputs(program);
                }
                resolved.put(entry.getKey(), program);
            }
            return new LegacyTerrainPipeline(resolved);
        } catch (IOException | RuntimeException error) {
            compiled.values().forEach(GlProgram::close);
            throw error;
        }
    }

    public static void validateInputs(GlProgram program) throws IOException {
        for (boolean attributes : new boolean[] {true, false}) {
            var expected = attributes ? ATTRIBUTES : UNIFORMS;
            for (var input : program.activeInputs(attributes)) {
                int maxSize = input.name().equals("tapetum_TextureMatrix[0]") ? 2 : 1;
                if (!Objects.equals(expected.get(input.name()), input.type()) || input.size() > maxSize) {
                    throw new IOException("Native terrain input not implemented: " + input.name()
                        + " (type " + input.type() + ", size " + input.size() + ")");
                }
                if (attributes && program.attributeLocation(input.name()) != LegacyTerrainMesh.ATTRIBUTES.get(input.name())) {
                    throw new IOException("Native terrain attribute location mismatch: " + input.name());
                }
            }
        }
    }

    public void enterLayer(RenderType type) {
        if (layer != null) throw new IllegalStateException("Nested terrain layer");
        layer = type == RenderType.solid() ? GbufferProgram.TERRAIN_SOLID
            : type == RenderType.cutout() || type == RenderType.cutoutMipped() || type == RenderType.tripwire()
                ? GbufferProgram.TERRAIN_CUTOUT
            : type == RenderType.translucent() ? GbufferProgram.WATER : null;
    }

    public void leaveLayer() { layer = null; }

    /** Returns false before any draw when the vanilla format/layer is not owned by this path. */
    public boolean draw(int vbo, int vertices, VertexFormat format, com.mojang.math.Matrix4f modelView, int mode) {
        GlProgram program = layer == null ? null : programs.get(layer);
        if (closed || program == null || format != DefaultVertexFormat.BLOCK || format.getVertexSize() != 32
                || mode != GL11.GL_QUADS || vbo <= 0 || vertices == 0) return false;
        QuadIndices.indexCount(vertices);
        Matrix4f model = LegacyMatrices.convert(modelView);
        Matrix3f normal = new Matrix3f(model).invert().transpose();
        if (!model.isFinite() || !normal.isFinite()) return false;
        try (GlRenderState state = GlRenderState.capture()) {
            int atlas = boundTexture(0);
            int light = boundTexture(2);
            program.use();
            program.bindSampler("texture", 0, atlas);
            program.bindSampler("tapetum_texture", 0, atlas);
            program.bindSampler("gtexture", 0, atlas);
            program.bindSampler("lightmap", 2, light);
            program.setUniform("tapetum_ModelViewMatrix", model);
            program.setUniform("tapetum_ProjectionMatrix", FrameState.projection());
            program.setUniform("tapetum_ModelViewProjectionMatrix", new Matrix4f(FrameState.projection()).mul(model));
            program.setUniform("tapetum_NormalMatrix", normal);
            program.setUniform("tapetum_TextureMatrix[0]", new Matrix4f());
            program.setUniform("tapetum_TextureMatrix[1]", new Matrix4f().scaling(1f / 256f).translate(8, 8, 0));
            program.setUniform("gbufferModelView", FrameState.modelView());
            program.setUniform("gbufferModelViewInverse", FrameState.modelViewInverse());
            program.setUniform("gbufferProjection", FrameState.projection());
            program.setUniform("gbufferProjectionInverse", FrameState.projectionInverse());
            var camera = FrameState.cameraPosition();
            program.setUniform("cameraPosition", (float) camera.x, (float) camera.y, (float) camera.z);
            mesh.draw(vbo, vertices);
        }
        return true;
    }

    private static int boundTexture(int unit) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
        return GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    }

    @Override public void beginLevelRendering() { layer = null; }
    @Override public void finalizeLevelRendering() { layer = null; }
    @Override public boolean isShaderPackActive() { return !closed; }
    @Override public void destroy() {
        if (closed) return;
        closed = true;
        layer = null;
        new HashSet<>(programs.values()).forEach(GlProgram::close);
        mesh.close();
    }
}
