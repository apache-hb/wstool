package wstool.maven.importer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wstool.test.components.CliProcess;
import wstool.test.components.CliResult;
import wstool.test.components.TestResources;

class MavenImportMainTest {
    private static final CliProcess CLI_PROCESS =
            new CliProcess(MavenImportMain.class, "wstool.maven-import-cli.classpath", List.of());

    @TempDir Path tempDirectory;

    @Test
    void subprocessImportsArtifactsFromLocalMavenRepository() throws Exception {
        Path repository =
                TestResources.copyDirectory(
                        getClass(), "/maven-repository", tempDirectory.resolve("maven-repository"));
        Path localRepository = tempDirectory.resolve("maven-cache");

        CliResult result =
                runCli(
                        "--repository",
                        repository.toUri().toString(),
                        "--local-repository",
                        localRepository.toString(),
                        "test.import-fixture:root:1.0");

        assertSuccess(result);
        List<String> outputLines = result.output().lines().toList();
        assertEquals(2, outputLines.size(), result.error());
        Set<String> artifactFileNames =
                outputLines.stream()
                        .map(line -> line.split("\\t", -1))
                        .peek(fields -> assertEquals(2, fields.length, String.join("\t", fields)))
                        .map(fields -> Path.of(fields[1]).getFileName().toString())
                        .collect(Collectors.toSet());
        assertEquals(Set.of("root-1.0.jar", "leaf-2.0.jar"), artifactFileNames);
        assertTrue(
                Files.isRegularFile(
                        localRepository.resolve("test/import-fixture/root/1.0/root-1.0.jar")));
        assertTrue(
                Files.isRegularFile(
                        localRepository.resolve("test/import-fixture/leaf/2.0/leaf-2.0.jar")));
        assertTrue(
                Files.isRegularFile(
                        tempDirectory.resolve("maven/test/import-fixture/root/1.0/wstool.pkl")));
        assertTrue(
                Files.isRegularFile(
                        tempDirectory.resolve("maven/test/import-fixture/leaf/2.0/wstool.pkl")));
    }

    @Test
    void subprocessShowsHelpWithoutResolvingArtifacts() throws Exception {
        CliResult result = runCli("--help");

        assertSuccess(result);
        assertTrue(result.output().contains("--repository"));
        assertTrue(result.output().contains("GROUP:ARTIFACT:VERSION"));
    }

    @Test
    void subprocessRejectsMissingCoordinates() throws Exception {
        CliResult result = runCli();

        assertEquals(2, result.exitCode(), result.output() + result.error());
        assertTrue(result.error().contains("Missing required parameter"));
    }

    private CliResult runCli(String... arguments) throws Exception {
        return CLI_PROCESS.run(tempDirectory, tempDirectory, Map.of(), arguments);
    }

    private void assertSuccess(CliResult result) {
        assertEquals(0, result.exitCode(), result.output() + result.error());
    }
}
