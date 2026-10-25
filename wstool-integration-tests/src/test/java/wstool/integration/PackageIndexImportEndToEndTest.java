package wstool.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import wstool.cli.Main;
import wstool.maven.importer.cli.MavenImportMain;
import wstool.npm.importer.cli.NpmImportMain;
import wstool.test.components.CliProcess;
import wstool.test.components.CliResult;
import wstool.test.components.NpmRegistryManager;
import wstool.test.components.PackageGitManager;
import wstool.test.components.TestResources;

@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class PackageIndexImportEndToEndTest {
    private static final Path OUTPUT_DIRECTORY =
            Path.of(System.getProperty("wstool.integration-tests.output-directory"))
                    .resolve("package-index-import");
    private static final Path NPM_CONFIG_DIRECTORY = OUTPUT_DIRECTORY.resolve("npm-config");
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("wstool.project-root"));
    private static final CliProcess WSTOOL =
            new CliProcess(Main.class, "wstool.cli.classpath", List.of());
    private static final CliProcess NPM_IMPORT =
            new CliProcess(NpmImportMain.class, "wstool.npm-import-cli.classpath", List.of());
    private static final CliProcess MAVEN_IMPORT =
            new CliProcess(MavenImportMain.class, "wstool.maven-import-cli.classpath", List.of());

    @Container
    private static final GenericContainer<?> NPM_REGISTRY = NpmRegistryManager.container();

    @Container private static final GiteaContainer GITEA = PackageGitManager.container();

    private static Path temporaryDirectory;

    @BeforeAll
    static void publishNpmPackages() throws Exception {
        if (Files.exists(OUTPUT_DIRECTORY)) {
            try (var paths = Files.walk(OUTPUT_DIRECTORY)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        temporaryDirectory = OUTPUT_DIRECTORY;
        Files.createDirectories(temporaryDirectory);
        NpmRegistryManager.publishPackage(
                NPM_REGISTRY,
                packageFixture("leaf", NPM_CONFIG_DIRECTORY),
                NPM_CONFIG_DIRECTORY.resolve("npm-config"));
        NpmRegistryManager.publishPackage(
                NPM_REGISTRY,
                packageFixture("root", NPM_CONFIG_DIRECTORY),
                NPM_CONFIG_DIRECTORY.resolve("npm-config"));
    }

    @Test
    void importsNpmAndMavenPackagesIntoOneIndexAndBuildsThemThroughWstoolCli() throws Exception {
        Path index = temporaryDirectory.resolve("package-index");
        Files.createDirectories(index);
        PackageGitManager git = new PackageGitManager(temporaryDirectory, GITEA);

        Path npmOutput = temporaryDirectory.resolve("npm-resolution");
        CliResult npmResult =
                NPM_IMPORT.run(
                        temporaryDirectory,
                        temporaryDirectory,
                        Map.of(),
                        "--registry",
                        NpmRegistryManager.registryUri(NPM_REGISTRY).toString(),
                        "--output",
                        npmOutput.toString(),
                        "--package-index",
                        index.toString(),
                        "@wstool-test/root:1.0.0");
        assertSuccess(npmResult);

        Path mavenRepository =
                TestResources.copyDirectory(
                        getClass(),
                        "/maven-repository",
                        temporaryDirectory.resolve("maven-repository"));
        Path mavenCache = temporaryDirectory.resolve("maven-cache");
        CliResult mavenResult =
                MAVEN_IMPORT.run(
                        temporaryDirectory,
                        temporaryDirectory,
                        Map.of(),
                        "--repository",
                        mavenRepository.toUri().toString(),
                        "--local-repository",
                        mavenCache.toString(),
                        "--package-index",
                        index.toString(),
                        "test.import-fixture:root:1.0");
        assertSuccess(mavenResult);

        assertTrue(
                Files.isRegularFile(
                        index.resolve("packages/npm/@wstool-test/root/1.0.0/wstool.pkl")));
        assertTrue(
                Files.isRegularFile(
                        index.resolve("packages/npm/@wstool-test/leaf/2.1.0/wstool.pkl")));
        assertTrue(
                Files.isRegularFile(
                        index.resolve("packages/maven/test/import-fixture/root/1.0/wstool.pkl")));
        assertTrue(
                Files.isRegularFile(
                        index.resolve("packages/maven/test/import-fixture/leaf/2.0/wstool.pkl")));
        URI npmImportRepository =
                git.create(
                        "wstool-npm-import",
                        PROJECT_ROOT.resolve("packages/wstool-npm-import"),
                        Map.of());
        URI indexRepository =
                git.create(
                        "package-index",
                        index,
                        Map.of(
                                Path.of("packages/wstool-maven-import"),
                                PROJECT_ROOT.resolve("packages/wstool-maven-import")));

        Path distribution = temporaryDirectory.resolve("distribution");
        TestResources.copyDirectory(getClass(), "/package-index-distribution", distribution);
        URI distributionRepository = git.create("distribution", distribution, Map.of());

        Map<String, String> environment =
                Map.of(
                        "PACKAGE_INDEX_REPOSITORY", indexRepository.toString(),
                        "WSTOOL_NPM_IMPORT_REPOSITORY", npmImportRepository.toString());
        CliResult workspaceResult =
                WSTOOL.run(
                        temporaryDirectory,
                        temporaryDirectory,
                        environment,
                        "workspace",
                        "create",
                        "--name",
                        "package-index-workspace",
                        "--distribution",
                        distributionRepository.toString());
        assertSuccess(workspaceResult);

        Path workspace = temporaryDirectory.resolve("package-index-workspace");
        Path packageIndexSource = workspace.resolve("src/package-index");
        addPackage(workspace, "wstool-npm-import", environment);
        addPackage(workspace, "wstool-maven-import", environment);
        runPackage(workspace.resolve("src/wstool-npm-import"), environment);
        runPackage(packageIndexSource.resolve("packages/wstool-maven-import"), environment);
        addPackage(workspace, "@wstool-test/root", environment);
        assertTrue(
                Files.isRegularFile(
                        packageIndexSource.resolve(
                                "packages/npm/@wstool-test/root/1.0.0/wstool.pkl")));
        addPackage(workspace, "@wstool-test/leaf", environment);
        addPackage(workspace, "test.import-fixture:root", environment);
        addPackage(workspace, "test.import-fixture:leaf", environment);

        runPackage(packageIndexSource.resolve("packages/npm/@wstool-test/leaf/2.1.0"), environment);
        runPackage(packageIndexSource.resolve("packages/npm/@wstool-test/root/1.0.0"), environment);
        runPackage(
                packageIndexSource.resolve("packages/maven/test/import-fixture/root/1.0"),
                environment);
        runPackage(
                packageIndexSource.resolve("packages/maven/test/import-fixture/leaf/2.0"),
                environment);

        assertTrue(
                Files.isRegularFile(
                        workspace.resolve(
                                "private/build-source/@wstool-test/root/1.0.0/index.js")));
        assertTrue(
                Files.isRegularFile(
                        workspace.resolve(
                                "private/build-source/@wstool-test/leaf/2.1.0/index.js")));
        assertTrue(
                Files.isRegularFile(
                        workspace.resolve(
                                "private/build-source/test.import-fixture:root/1.0/lib/root-1.0.jar")));
        assertTrue(
                Files.isRegularFile(
                        workspace.resolve(
                                "private/build-source/test.import-fixture:leaf/2.0/lib/leaf-2.0.jar")));
        assertTrue(
                Files.isRegularFile(
                        workspace.resolve(
                                "private/prefix/wstool-npm-import/1.0.0/bin/wstool-npm-import")));
        assertTrue(
                Files.isRegularFile(
                        workspace.resolve(
                                "private/prefix/wstool-maven-import/1.0.0/bin/maven-wstool-import")));
        assertTrue(
                Files.isRegularFile(
                        workspace.resolve(
                                "private/prefix/@wstool-test/root/1.0.0/share/wstool-npm/package.tgz")));
        assertTrue(
                Files.isRegularFile(
                        workspace.resolve(
                                "private/prefix/test.import-fixture:root/1.0/share/maven-artifacts/root-1.0.jar")));
        assertTrue(
                Files.isRegularFile(
                        workspace.resolve(
                                "private/prefix/test.import-fixture:leaf/2.0/share/maven-artifacts/leaf-2.0.jar")));
        assertEquals(
                "module.exports = require(\"@wstool-test/leaf\");\n",
                Files.readString(
                        workspace.resolve(
                                "private/build-source/@wstool-test/root/1.0.0/index.js")));
    }

    private static Path packageFixture(String name, Path directory) throws IOException {
        return TestResources.copyDirectory(
                PackageIndexImportEndToEndTest.class,
                "/npm-registry/packages/" + name,
                directory.resolve("packages").resolve(name));
    }

    private void addPackage(Path workspace, String name, Map<String, String> environment)
            throws Exception {
        CliResult result =
                WSTOOL.run(
                        temporaryDirectory,
                        workspace,
                        environment,
                        "workspace",
                        "add",
                        "--package",
                        name);
        assertSuccess(result);
    }

    private void runPackage(Path packageDirectory, Map<String, String> environment)
            throws Exception {
        CliResult result =
                WSTOOL.run(temporaryDirectory, packageDirectory, environment, "package", "run");
        assertSuccess(result);
    }

    private void assertSuccess(CliResult result) {
        assertEquals(0, result.exitCode(), result.output() + result.error());
    }
}
