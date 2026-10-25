package wstool.npm.importer.cli;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
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
        description = "Resolve npm packages and their transitive dependencies.",
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

    @Option(
            names = {"--output", "-o"},
            description = "Directory for package.json and package-lock.json.",
            required = true)
    private Path outputDirectory;

    @Option(
            names = "--package-index",
            description = "Write per-package wstool.pkl files under this package index directory.")
    private Path packageIndexDirectory;

    @Parameters(arity = "1..*", paramLabel = "PACKAGE[:VERSION]")
    private List<String> packages;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new NpmImportMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        NpmImportResult result =
                new NpmImporter(npmExecutable, registry).importPackages(packages, outputDirectory);
        if (packageIndexDirectory != null) {
            List<Path> configs =
                    new NpmPackageIndexWriter(
                                    HttpClient.newBuilder()
                                            .followRedirects(HttpClient.Redirect.NORMAL)
                                            .build())
                            .write(result.packages(), packageIndexDirectory);
            logger.info("Wrote {} package configs to {}", configs.size(), packageIndexDirectory);
        }
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
        logger.info(
                "Resolved {} npm packages; lockfile written to {}",
                result.packages().size(),
                result.lockfile());
        return 0;
    }
}
