package wstool.importer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "maven",
        description = "Import Maven artifacts and their compile/runtime dependencies.",
        mixinStandardHelpOptions = true)
public class MavenImportCommand implements Callable<Integer> {
    @Option(
            names = {"--output", "-o"},
            required = true,
            description = "Output directory for the metadata bundle and generated Pkl entries.")
    private Path output;

    @Option(
            names = "--repository",
            defaultValue = "https://repo.maven.apache.org/maven2",
            description = "Maven repository URL.")
    private String repository;

    @Option(
            names = "--local-repository",
            description = "Resolver cache directory (defaults to ~/.m2/repository).")
    private Path localRepository;

    @Option(
            names = "--base-url",
            description = "Public URL prefix where the generated metadata ZIP will be hosted.")
    private String baseUrl;

    @Option(
            names = "--external-files-url",
            description = "Maven repository URL used for ExternalFiles (defaults to --repository).")
    private String externalFilesUrl;

    @Parameters(arity = "1..*", paramLabel = "GROUP:ARTIFACT:VERSION")
    private List<String> coordinates = new ArrayList<>();

    @Override
    public Integer call() throws Exception {
        List<String> allCoordinates =
                ImportRoots.merge(ImportRoots.read(output, "maven-import-roots.json"), coordinates);
        Path cache =
                localRepository == null
                        ? Path.of(System.getProperty("user.home"), ".m2", "repository")
                        : localRepository;
        List<MavenCoordinate> roots = allCoordinates.stream().map(MavenCoordinate::parse).toList();
        var resolver = new MavenDependencyResolver(repository, cache);
        var allArtifacts =
                new LinkedHashMap<MavenCoordinate, MavenDependencyResolver.ResolvedArtifact>();
        for (MavenCoordinate root : roots) {
            allArtifacts.putAll(resolver.resolve(root));
        }
        String filesUrl = externalFilesUrl == null ? repository : externalFilesUrl;
        Path bundle = new PackageBundleWriter().write(allArtifacts, output, filesUrl, baseUrl);
        ImportRoots.write(output, "maven-import-roots.json", allCoordinates);
        System.out.println("Imported " + allArtifacts.size() + " Maven artifacts.");
        System.out.println("Metadata bundle: " + bundle.toAbsolutePath());
        System.out.println(
                "Distribution entries: " + output.resolve("packages.pkl").toAbsolutePath());
        return 0;
    }
}
