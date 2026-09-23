import dev.tapetum.shaders.shaderpack.ShaderpackManager;
import dev.tapetum.shaders.shaderpack.ShaderDimension;
import dev.tapetum.shaders.shaderpack.glsl.GlslCompatPatcher;
import java.nio.file.Path;
import java.util.Map;

/** Read-only inspection of real packs; does not create a window or initialize OpenGL. */
class ShaderOptionAudit {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected shaderpacks directory");
        var manager = new ShaderpackManager(Path.of(args[0]));
        manager.refresh();
        for (String name : manager.getAvailablePacks()) {
            try (var pack = manager.load(name).orElseThrow()) {
                var options = pack.getOptions();
                var change = options.entries().stream().filter(o -> o.values().size() > 1).findFirst();
                if (change.isPresent()) {
                    var option = change.get();
                    String value = option.values().stream().filter(v -> !v.equals(option.defaultValue())).findFirst().orElseThrow();
                    pack.setOptionValues(Map.of(option.name(), value));
                    pack.readCompilableProgramSource("final.fsh", GlslCompatPatcher.Stage.FRAGMENT,
                        ShaderDimension.OVERWORLD, null);
                }
                System.out.println(name + ": " + options.entries().size() + " editable define options; source preparation OK");
            }
        }
    }
}
