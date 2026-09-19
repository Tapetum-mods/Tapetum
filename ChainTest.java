import dev.tapetum.shaders.shaderpack.*;
import java.nio.file.*;

public class ChainTest {
    public static void main(String[] a) throws Exception {
        ShaderpackManager mgr = new ShaderpackManager(Path.of(a[0]));
        mgr.refresh();
        for (String name : mgr.getAvailablePacks()) {
            try (ShaderPack pack = mgr.load(name).orElseThrow()) {
                var passes = ShaderProgramChain.discover(pack, ShaderDimension.OVERWORLD);
                System.out.printf("%-34s %2d passes: %s%n", name, passes.size(),
                    passes.stream().map(ShaderProgramChain.Pass::name).toList());
            }
        }
    }
}
