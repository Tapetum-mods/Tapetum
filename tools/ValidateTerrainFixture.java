import dev.tapetum.shaders.shaderpack.*;
import java.nio.file.*;
import java.util.*;

/** CPU-only GLSL syntax/link validation; never creates an OpenGL context or launches Minecraft. */
class ValidateTerrainFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected pack directory and glslangValidator path");
        Path packPath = Path.of(args[0]).toAbsolutePath();
        Path temporary = Files.createTempDirectory("tapetum-terrain-glsl-");
        try (var pack = new ShaderpackManager(packPath.getParent()).load(packPath.getFileName().toString()).orElseThrow()) {
            var sources = new HashSet<>(SimpleTerrainSources.read(pack, null, ShaderDimension.OVERWORLD).values());
            for (var source : sources) {
                Path vertex = temporary.resolve(source.program().programName() + ".vert");
                Path fragment = temporary.resolve(source.program().programName() + ".frag");
                Files.writeString(vertex, source.vertex());
                Files.writeString(fragment, source.fragment());
                var process = new ProcessBuilder(args[1], "-l", vertex.toString(), fragment.toString()).inheritIO().start();
                if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                    throw new IllegalStateException("GLSL validation timed out");
                }
                if (process.exitValue() != 0) throw new IllegalStateException("GLSL validation failed: " + source.program());
            }
            System.out.println(sources.size() + " terrain pair(s) linked by glslang; GPU/world appearance is NOT verified");
        } finally {
            try (var files = Files.walk(temporary)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
