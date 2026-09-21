import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.model.eclipse.EclipseProject;

/** Exercises phased Eclipse model loading with the IDE's unmodified initialization scripts. */
public class CheckIdePhasedImport {
    public static void main(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: CheckIdePhasedImport PROJECT_DIR INIT_SCRIPT...");
        }
        var arguments = new ArrayList<>(List.of("--offline"));
        for (int i = 1; i < args.length; i++) {
            arguments.add("--init-script");
            arguments.add(new File(args[i]).getAbsolutePath());
        }
        var loaded = new AtomicReference<List<String>>();
        var finished = new AtomicReference<List<String>>();
        try (var connection = GradleConnector.newConnector().forProjectDirectory(new File(args[0])).connect()) {
            connection.action()
                .projectsLoaded(new LoadProjects(), loaded::set)
                .buildFinished(new LoadEclipseModels(), finished::set)
                .build().withArguments(arguments)
                .setJavaHome(new File(System.getProperty("java.home")))
                .setStandardOutput(System.out).setStandardError(System.err).run();
        }
        if (loaded.get() == null || loaded.get().isEmpty() || !loaded.get().equals(finished.get())) {
            throw new AssertionError("Phased import did not return matching project sets: "
                + loaded.get() + " / " + finished.get());
        }
        System.out.println("Phased Eclipse import: " + finished.get().size() + " projects imported successfully");
    }

    public static class LoadProjects implements BuildAction<List<String>> {
        private static final long serialVersionUID = 1L;

        @Override
        public List<String> execute(BuildController controller) {
            var paths = new ArrayList<String>();
            for (var project : controller.getBuildModel().getProjects()) paths.add(project.getPath());
            paths.sort(String::compareTo);
            return paths;
        }
    }

    public static class LoadEclipseModels implements BuildAction<List<String>> {
        private static final long serialVersionUID = 1L;

        @Override
        public List<String> execute(BuildController controller) {
            var paths = new ArrayList<String>();
            for (var project : controller.getBuildModel().getProjects()) {
                var model = controller.getModel(project, EclipseProject.class);
                for (var dependency : model.getClasspath()) {
                    if (!dependency.getFile().isFile()) {
                        throw new IllegalStateException("Missing Eclipse classpath entry: " + dependency.getFile());
                    }
                }
                System.out.println("Eclipse model: " + project.getPath() + " ("
                    + model.getClasspath().size() + " dependencies, "
                    + model.getSourceDirectories().size() + " source directories)");
                paths.add(project.getPath());
            }
            paths.sort(String::compareTo);
            return paths;
        }
    }
}
