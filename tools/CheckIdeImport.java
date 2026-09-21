import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.gradle.tooling.GradleConnector;

/** Replays JDT LS annotation-processor model discovery without launching an editor or Minecraft. */
class CheckIdeImport {
    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3 || (args.length == 3 && !args[2].equals("--parallel"))) {
            throw new IllegalArgumentException("Usage: CheckIdeImport.java PROJECT_DIR JDT_APT_INIT_SCRIPT [--parallel]");
        }
        var arguments = new ArrayList<>(List.of("--offline", "--init-script", new File(args[1]).getAbsolutePath()));
        if (args.length == 3) arguments.add(args[2]);
        try (var connection = GradleConnector.newConnector().forProjectDirectory(new File(args[0])).connect()) {
            Map<?, ?> model = connection.model(Map.class).withArguments(arguments)
                .setJavaHome(new File(System.getProperty("java.home"))).get();
            if (model.isEmpty()) throw new AssertionError("IDE model has no projects");
            for (var entry : model.entrySet()) {
                System.out.println("Imported: " + entry.getKey());
            }
            System.out.println("JDT annotation-processor model: " + model.size() + " projects imported successfully");
        }
    }
}
