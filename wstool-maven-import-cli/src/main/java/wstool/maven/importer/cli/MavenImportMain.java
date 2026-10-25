package wstool.maven.importer.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import wstool.maven.importer.MavenArtifact;
import wstool.maven.importer.MavenPackageIndexWriter;
import wstool.maven.importer.cli.inject.DaggerMavenImportComponent;
import wstool.maven.importer.cli.inject.MavenImportComponent;

@Command(
        name = "wstool-maven-import",
        description =
                "Import a Maven artifact and its compile/runtime dependencies into the current package index.",
        mixinStandardHelpOptions = true,
        version = "1.0.0")
public final class MavenImportMain implements Callable<Integer> {
    private static final Logger logger = LogManager.getLogger(MavenImportMain.class);

    @Option(
            names = {"--repository", "-r"},
            description = "Maven repository URL.",
            defaultValue = "https://repo.maven.apache.org/maven2/")
    private String repository;

    @Option(
            names = {"--local-repository"},
            description = "Local artifact cache directory.")
    private Path localRepository = Path.of(System.getProperty("user.home"), ".m2", "repository");

    @Parameters(arity = "1", paramLabel = "GROUP:ARTIFACT:VERSION")
    private String coordinate;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MavenImportMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        MavenImportComponent component =
                DaggerMavenImportComponent.builder()
                        .repositoryUrl(repository)
                        .localRepository(localRepository)
                        .build();
        List<MavenArtifact> artifacts = component.mavenImporter().resolve(List.of(coordinate));
        Path packageIndexDirectory = Path.of("").toAbsolutePath().normalize();
        List<Path> configs =
                new MavenPackageIndexWriter()
                        .write(artifacts, repository, localRepository, packageIndexDirectory);
        logger.info("Wrote {} package configs to {}", configs.size(), packageIndexDirectory);
        for (MavenArtifact artifact : artifacts) {
            System.out.println(artifact.coordinate() + "\t" + artifact.file());
        }
        logger.info("Resolved {} Maven artifacts", artifacts.size());
        return 0;
    }
}
