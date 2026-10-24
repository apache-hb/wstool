package wstool.npm.importer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import wstool.test.components.CliProcess;
import wstool.test.components.CliResult;
import wstool.test.components.NpmRegistryManager;
import wstool.test.components.TestResources;

@Testcontainers(disabledWithoutDocker = true)
class NpmImportMainTest {
    private static final CliProcess CLI_PROCESS =
            new CliProcess(NpmImportMain.class, "wstool.npm-import-cli.classpath", List.of());

    @Container private static final GenericContainer<?> REGISTRY = NpmRegistryManager.container();

    @TempDir static Path npmConfigDirectory;

    @TempDir Path tempDirectory;

    @BeforeAll
    static void publishPackageFixtures() throws Exception {
        NpmRegistryManager.publishPackage(REGISTRY, packageFixture("leaf"), npmConfigDirectory);
        NpmRegistryManager.publishPackage(REGISTRY, packageFixture("root"), npmConfigDirectory);
    }

    @Test
    void subprocessImportsPackagesFromNpmRepository() throws Exception {
        Path output = tempDirectory.resolve("imported");
        URI registry = NpmRegistryManager.registryUri(REGISTRY);

        CliResult result =
                runCli(
                        "--registry",
                        registry.toString(),
                        "--output",
                        output.toString(),
                        "@wstool-test/root:1.0.0");

        assertSuccess(result);
        assertTrue(Files.isRegularFile(output.resolve("package.json")), result.error());
        assertTrue(Files.isRegularFile(output.resolve("package-lock.json")), result.error());
        Set<String> resolvedPackages =
                result.output()
                        .lines()
                        .map(line -> line.split("\\t", -1))
                        .peek(fields -> assertEquals(3, fields.length, String.join("\t", fields)))
                        .map(fields -> fields[0])
                        .collect(Collectors.toSet());
        assertEquals(
                Set.of("@wstool-test/root@1.0.0", "@wstool-test/leaf@2.1.0"), resolvedPackages);
    }

    @Test
    void subprocessShowsHelpWithoutContactingRegistry() throws Exception {
        CliResult result = runCli("--help");

        assertSuccess(result);
        assertTrue(result.output().contains("--registry"));
        assertTrue(result.output().contains("--output"));
    }

    @Test
    void subprocessRejectsMissingOutputDirectory() throws Exception {
        CliResult result = runCli("@wstool-test/root:1.0.0");

        assertEquals(2, result.exitCode(), result.output() + result.error());
        assertTrue(result.error().contains("Missing required option"));
    }

    private CliResult runCli(String... arguments) throws Exception {
        return CLI_PROCESS.run(tempDirectory, tempDirectory, Map.of(), arguments);
    }

    private void assertSuccess(CliResult result) {
        assertEquals(0, result.exitCode(), result.output() + result.error());
    }

    private static Path packageFixture(String name) throws Exception {
        return TestResources.copyDirectory(
                NpmImportMainTest.class,
                "/npm-registry/packages/" + name,
                npmConfigDirectory.resolve("package-fixtures").resolve(name));
    }
}
