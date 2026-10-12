package wstool.npm.importer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import wstool.importer.ImportRoots;

@Command(
        name = "npm",
        description = "Import npm packages and their dependencies into wstool packages.",
        mixinStandardHelpOptions = true)
public class NpmImportCommand implements Callable<Integer> {
    @Option(
            names = {"--output", "-o"},
            required = true,
            description = "Output directory for package files and generated Pkl modules.")
    private Path output;

    @Option(
            names = "--registry",
            defaultValue = "https://registry.npmjs.org/",
            description = "npm registry URL.")
    private String registry;

    @Option(names = "--cache", description = "npm cache directory (defaults to ~/.npm).")
    private Path cache;

    @Option(names = "--npm", defaultValue = "npm", description = "npm executable to run.")
    private String npm;

    @Option(
            names = "--base-url",
            description = "Public URL prefix where the metadata ZIP will be hosted.")
    private String baseUrl;

    @Parameters(arity = "1..*", paramLabel = "PACKAGE[@VERSION]")
    private List<String> packages = new ArrayList<>();

    @Override
    public Integer call() throws Exception {
        List<String> allPackages =
                ImportRoots.merge(ImportRoots.read(output, "npm-import-roots.json"), packages);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Path resolvedCache =
                cache == null ? Path.of(System.getProperty("user.home"), ".npm") : cache;
        Path temporary =
                Files.createTempDirectory(output.toAbsolutePath().getParent(), ".npm-import-");
        try {
            var resolved =
                    new NpmDependencyResolver(npm)
                            .resolve(allPackages, registry, resolvedCache, temporary);
            Path bundle = new NpmPackageBundleWriter().write(resolved, output, baseUrl);
            ImportRoots.write(output, "npm-import-roots.json", allPackages);
            System.out.println("Imported " + resolved.packages().size() + " npm packages.");
            System.out.println("Metadata bundle: " + bundle.toAbsolutePath());
            System.out.println(
                    "Distribution entries: " + output.resolve("packages.pkl").toAbsolutePath());
            System.out.println(
                    "Root dependencies: "
                            + output.resolve("npm-dependencies.pkl").toAbsolutePath());
            return 0;
        } finally {
            deleteTree(temporary);
        }
    }

    private void deleteTree(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
