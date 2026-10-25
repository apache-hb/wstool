package wstool.npm.importer.cli;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.apache.commons.io.file.PathUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import wstool.npm.importer.NpmImportResult;
import wstool.npm.importer.NpmImporter;
import wstool.npm.importer.NpmPackageIndexWriter;

@Command(
        name = "wstool-npm-import",
        description = "Import an npm package and its dependencies into the current package index.",
        mixinStandardHelpOptions = true,
        version = "1.0.0")
public final class NpmImportMain implements Callable<Integer> {
    private static final Logger logger = LogManager.getLogger(NpmImportMain.class);

    @Option(names = "--npm", description = "npm executable to run.", defaultValue = "npm")
    private String npmExecutable;

    @Option(
            names = "--registry",
            description = "npm registry URL.",
            defaultValue = "https://registry.npmjs.org/")
    private String registry;

    @Parameters(arity = "1", paramLabel = "PACKAGE[:VERSION]")
    private String packageSpecification;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new NpmImportMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path buildDirectory = workingDirectory.resolve("build");
        Files.createDirectories(buildDirectory);
        Path outputDirectory = Files.createTempDirectory(buildDirectory, "wstool-npm-import-");
        try {
            NpmImportResult result =
                    new NpmImporter(npmExecutable, registry)
                            .importPackages(List.of(packageSpecification), outputDirectory);
            List<Path> configs =
                    new NpmPackageIndexWriter(
                                    HttpClient.newBuilder()
                                            .followRedirects(HttpClient.Redirect.NORMAL)
                                            .build())
                            .write(result.packages(), workingDirectory);
            logger.info("Wrote {} package configs to {}", configs.size(), workingDirectory);
            for (var npmPackage : result.packages()) {
                System.out.println(
                        npmPackage.name()
                                + "@"
                                + npmPackage.version()
                                + "\t"
                                + npmPackage.tarballUrl()
                                + "\t"
                                + npmPackage.integrity());
            }
            logger.info("Resolved {} npm packages", result.packages().size());
            return 0;
        } finally {
            PathUtils.deleteDirectory(outputDirectory);
        }
    }
}
