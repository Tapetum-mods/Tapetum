import dev.tapetum.shaders.shaderpack.ShaderpackManager;
import java.nio.file.Path;

/** Reads local packs without opening a client, writing config or modifying the archives. */
class InspectPackOptions {
    public static void main(String[] args) throws Exception {
        for (String argument : args) {
            Path path = Path.of(argument).toAbsolutePath();
            try (var pack = new ShaderpackManager(path.getParent()).load(path.getFileName().toString()).orElseThrow()) {
                var options = pack.getOptions();
                var menu = pack.getMenu("en_us");
                System.out.printf("%s: %d options, %d root entries, %d complete profiles%n",
                    path.getFileName(), options.entries().size(), menu.entries("").size(), menu.profiles().size());
                System.out.println("  Root: " + menu.entries(""));
                System.out.println("  Profiles: " + menu.profiles());
                options.entries().stream().filter(option -> option.name().equals("shadowDistance"))
                    .forEach(option -> System.out.println("  shadowDistance: " + option.values()));
            }
        }
    }
}
