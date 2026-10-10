package wstool.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class MavenDependencyResolverTest {
    @TempDir Path tempDir;

    private static final MavenCoordinate ROOT =
            new MavenCoordinate("org.hamcrest", "hamcrest-library", "1.3");

    @Test
    void resolvesRealTransitiveMavenArtifactsAndCachesRepositoryResponses() throws Exception {
        Path cache = Path.of("build/test-maven-central-cache").toAbsolutePath();
        try (var proxy = new CachedMavenProxy(cache)) {
            Path localRepository = tempDir.resolve("resolver-cache");
            var resolved = new MavenDependencyResolver(proxy.url(), localRepository).resolve(ROOT);

            assertTrue(resolved.containsKey(ROOT));
            assertTrue(
                    resolved.keySet().stream()
                            .anyMatch(coordinate -> coordinate.artifact().equals("hamcrest-core")),
                    resolved.keySet().toString());
            assertTrue(resolved.get(ROOT).pom() != null);

            Path output = tempDir.resolve("generated");
            Path bundle = new PackageBundleWriter().write(resolved, output, null, "ubuntu:24.04");
            assertTrue(Files.isRegularFile(bundle));
            String pkl = Files.readString(output.resolve("packages.pkl"));
            assertTrue(pkl.contains("name = \"org_hamcrest_hamcrest-library\""));
            assertFalse(pkl.contains("https://"));
            try (ZipFile zip = new ZipFile(bundle.toFile())) {
                var packageConfig =
                        zip.getInputStream(
                                zip.getEntry(
                                        "packages/org_hamcrest_hamcrest-library/1.3/wstool.pkl"));
                assertTrue(packageConfig != null);
                assertTrue(
                        new String(packageConfig.readAllBytes())
                                .contains(
                                        "package://pkl.konna.cloud/pkl/wstool@1.0.0#/package.pkl"));
                assertTrue(
                        zip.getEntry(
                                        "packages/org_hamcrest_hamcrest-library/1.3/repository/org/hamcrest/hamcrest-library/1.3/hamcrest-library-1.3.jar")
                                != null);
            }

            int requestsAfterInitialResolve = proxy.upstreamRequests();
            Path secondLocalRepository = tempDir.resolve("resolver-cache-second");
            var secondResolve =
                    new MavenDependencyResolver(proxy.url(), secondLocalRepository).resolve(ROOT);
            assertEquals(resolved.keySet(), secondResolve.keySet());
            assertEquals(
                    requestsAfterInitialResolve,
                    proxy.upstreamRequests(),
                    "A repeat resolution should be served entirely from the persistent proxy cache");

            Path cliOutput = tempDir.resolve("cli-output");
            int status =
                    new CommandLine(Main.class)
                            .execute(
                                    "--output",
                                    cliOutput.toString(),
                                    "--repository",
                                    proxy.url(),
                                    "--local-repository",
                                    tempDir.resolve("cli-local-cache").toString(),
                                    ROOT.key());
            assertEquals(0, status);
            assertTrue(Files.isRegularFile(cliOutput.resolve("packages.pkl")));
            assertEquals(requestsAfterInitialResolve, proxy.upstreamRequests());
        }
    }
}
