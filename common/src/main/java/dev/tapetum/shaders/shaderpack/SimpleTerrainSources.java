package dev.tapetum.shaders.shaderpack;

import dev.tapetum.shaders.shaderpack.glsl.*;
import java.io.IOException;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** CPU-side preparation for the first, deliberately restricted native terrain path. */
public final class SimpleTerrainSources {
    private static final List<GbufferProgram> ROLES = List.of(GbufferProgram.TERRAIN_SOLID,
        GbufferProgram.TERRAIN_CUTOUT, GbufferProgram.WATER);
    private static final Set<GbufferProgram> FILES = Set.of(GbufferProgram.TERRAIN,
        GbufferProgram.TERRAIN_SOLID, GbufferProgram.TERRAIN_CUTOUT, GbufferProgram.WATER);

    public record Source(GbufferProgram program, String vertex, String fragment) { }

    private SimpleTerrainSources() { }

    public static Map<GbufferProgram, Source> read(ShaderPack pack, ShaderMacros macros, ShaderDimension dimension)
            throws IOException, GlslIncludeException {
        for (var folder : List.of(pack.getShaderRoot(), pack.getShaderRoot().resolve(dimension.folderName()))) {
            if (!Files.isDirectory(folder)) continue;
            try (var files = Files.list(folder)) {
                for (var file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (name.matches(".*\\.(vsh|fsh|gsh|csh)") && FILES.stream().noneMatch(role ->
                            name.equals(role.vertexFile()) || name.equals(role.fragmentFile()))) {
                        throw new IOException("Native terrain-only path cannot run " + name);
                    }
                }
            }
        }
        for (GbufferProgram role : FILES) {
            if (pack.locateProgram(role.vertexFile(), dimension).isPresent()
                    != pack.locateProgram(role.fragmentFile(), dimension).isPresent()) {
                throw new IOException("Incomplete native terrain program: " + role.programName());
            }
        }
        if (!pack.getPropertiesText().isBlank() || Files.exists(pack.getShaderRoot().resolve("block.properties"))) {
            throw new IOException("Native terrain-only path does not yet support pack properties or material mappings");
        }
        var prepared = new EnumMap<GbufferProgram, Source>(GbufferProgram.class);
        var resolved = new EnumMap<GbufferProgram, Source>(GbufferProgram.class);
        for (GbufferProgram role : ROLES) {
            var actual = role.resolve(candidate -> FILES.contains(candidate)
                && pack.locateProgram(candidate.vertexFile(), dimension).isPresent()
                && pack.locateProgram(candidate.fragmentFile(), dimension).isPresent());
            if (actual.isEmpty()) continue;
            Source source = prepared.get(actual.get());
            if (source == null) {
                GbufferProgram program = actual.get();
                String fragment = pack.readCompilableProgramSource(program.fragmentFile(),
                    GlslCompatPatcher.Stage.FRAGMENT, dimension, macros).orElseThrow();
                if (!DrawBuffers.parse(fragment).equals(List.of(0)) || DrawBuffers.requiredBufferCount(fragment) != 1
                        || !ColorTextureFormat.parse(fragment).isEmpty()) {
                    throw new IOException("Native terrain-only path needs the default single color target: " + program.programName());
                }
                String vertex = pack.readCompilableProgramSource(program.vertexFile(),
                    GlslCompatPatcher.Stage.VERTEX, dimension, macros).orElseThrow();
                vertex = GlslStageLinkage.alignVersions(GbufferVertexAdapter.adapt(vertex), fragment, program.programName());
                source = new Source(program, vertex, fragment);
                prepared.put(program, source);
            }
            resolved.put(role, source);
        }
        if (resolved.isEmpty()) throw new IOException("Pack has no supported native terrain program");
        return Map.copyOf(resolved);
    }
}
