package wstool.cli.commands.packages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.io.file.PathUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import wstool.cli.commands.CliHelper;
import wstool.test.components.CliResult;
import wstool.test.components.LocalFileHttpServer;
import wstool.test.components.PackageGitManager;
import wstool.test.components.TestResources;

@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class PackageRunIntegrationTest {
    @Container private static final GiteaContainer GITEA = PackageGitManager.container();

    private Path tmp;
    private PackageGitManager git;
    private CliHelper cli;
    private BuildToolchainTestSupport toolchain;
    private Map<String, String> recursiveEnvironment;

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        tmp =
                Path.of(
                                "build",
                                "package-run",
                                testInfo.getTestMethod().orElseThrow().getName()
                                        + "-"
                                        + testInfo.getDisplayName()
                                                .replaceAll("[^A-Za-z0-9.-]", "-"))
                        .toAbsolutePath();

        try {
            PathUtils.deleteDirectory(tmp);
        } catch (NoSuchFileException exception) {
            // No cleanup is needed for a new test workspace.
        }
        Files.createDirectories(tmp);
        git = new PackageGitManager(tmp, GITEA);
        cli = new CliHelper(tmp);
        toolchain = BuildToolchainTestSupport.create(tmp, git);
    }

    @AfterEach
    void tearDown() {
        if (toolchain != null) {
            toolchain.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"run", "build"})
    void runsBuildToolFromCurrentPackage(String buildMode) throws Exception {
        URI appRepository =
                createRepository(
                        "local-app",
                        CliHelper.resourceDirectory("/package-run/local/packages/local-app"));
        Map<String, String> environment =
                testEnvironment(Map.of("LOCAL_APP_REPOSITORY", appRepository.toString()));
        Path workspace = createWorkspace("local", "/package-run/local/distribution", environment);
        addPackage(workspace, "local-app", environment);
        Path packageDirectory = workspace.resolve("src/local-app");
        toolchain.prepare(workspace, cli, environment);

        CliResult result =
                runPackage(
                        packageDirectory, environment, buildMode, "verify", "argument with spaces");

        CliHelper.assertSuccess(result);
        Path prefix = workspace.resolve("private/prefix/local-app/1.0.0");
        assertEquals(
                "verify\nargument with spaces\n",
                Files.readString(prefix.resolve("arguments.txt")));
        assertEquals(
                expectedBuildPath(buildMode, workspace, packageDirectory) + "\n",
                Files.readString(prefix.resolve("source-dir.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"run", "build"})
    void runsBuildToolFromBuildDependency(String buildMode) throws Exception {
        URI appRepository =
                createRepository(
                        "dependency-app",
                        CliHelper.resourceDirectory(
                                "/package-run/dependency/packages/dependency-app"));
        URI toolRepository =
                createRepository(
                        "tool-provider",
                        CliHelper.resourceDirectory(
                                "/package-run/dependency/packages/tool-provider"));
        Map<String, String> environment =
                testEnvironment(
                        Map.of(
                                "DEPENDENCY_APP_REPOSITORY", appRepository.toString(),
                                "DEPENDENCY_TOOL_REPOSITORY", toolRepository.toString()));
        Path workspace =
                createWorkspace("dependency", "/package-run/dependency/distribution", environment);
        addPackage(workspace, "dependency-app", environment);
        addPackage(workspace, "tool-provider", environment);
        toolchain.prepare(workspace, cli, environment);
        cli.checkCli(
                workspace.resolve("src/tool-provider"),
                environment,
                "package",
                buildMode,
                "release");

        CliResult result =
                runPackage(
                        workspace.resolve("src/dependency-app"),
                        environment,
                        buildMode,
                        "compile",
                        "dependency argument");

        CliHelper.assertSuccess(result);
        assertEquals(
                "provided by dependency: compile dependency argument\n",
                Files.readString(
                        workspace.resolve("private/prefix/dependency-app/1.0.0/result.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"run", "build"})
    void buildToolCanUseToolFromItsBuildDependency(String buildMode) throws Exception {
        URI appRepository =
                createRepository(
                        "transitive-app",
                        CliHelper.resourceDirectory(
                                "/package-run/transitive/packages/transitive-app"));
        URI ownerRepository =
                createRepository(
                        "tool-owner",
                        CliHelper.resourceDirectory("/package-run/transitive/packages/tool-owner"));
        URI helperRepository =
                createRepository(
                        "tool-helper",
                        CliHelper.resourceDirectory(
                                "/package-run/transitive/packages/tool-helper"));

        Map<String, String> environment =
                testEnvironment(
                        Map.of(
                                "TRANSITIVE_APP_REPOSITORY", appRepository.toString(),
                                "TOOL_OWNER_REPOSITORY", ownerRepository.toString(),
                                "TOOL_HELPER_REPOSITORY", helperRepository.toString()));

        Path workspace =
                createWorkspace("transitive", "/package-run/transitive/distribution", environment);
        addPackage(workspace, "transitive-app", environment);
        addPackage(workspace, "tool-owner", environment);
        addPackage(workspace, "tool-helper", environment);
        toolchain.prepare(workspace, cli, environment);

        cli.checkCli(
                workspace.resolve("src/tool-helper"), environment, "package", buildMode, "release");
        cli.checkCli(
                workspace.resolve("src/tool-owner"), environment, "package", buildMode, "release");

        CliResult result =
                runPackage(
                        workspace.resolve("src/transitive-app"), environment, buildMode, "release");

        CliHelper.assertSuccess(result);
        assertEquals(
                "owner received: helper from dependency\n",
                Files.readString(
                        workspace.resolve("private/prefix/transitive-app/1.0.0/result.txt")));
    }

    @Test
    void buildsRecursiveDependenciesBeforeTheirDependents() throws Exception {
        Path buildOrder = tmp.resolve("build-order.txt");
        Path workspace = createRecursiveWorkspace(buildOrder, null, true);

        CliResult result =
                cli.runCli(
                        workspace.resolve("src/root"),
                        recursiveEnvironment,
                        "workspace",
                        "execute-recursive",
                        "--",
                        "./wstool/bin/build.sh");

        CliHelper.assertSuccess(result);
        assertEquals(List.of("leaf", "middle", "root"), Files.readAllLines(buildOrder));
    }

    @Test
    void stopsRecursiveBuildAfterDependencyFails() throws Exception {
        Path buildOrder = tmp.resolve("build-order.txt");
        Path workspace = createRecursiveWorkspace(buildOrder, "middle", true);

        CliResult result =
                cli.runCli(
                        workspace.resolve("src/root"),
                        recursiveEnvironment,
                        "workspace",
                        "execute-recursive",
                        "--",
                        "./wstool/bin/build.sh");

        assertEquals(1, result.exitCode(), result.output() + result.error());
        assertEquals(List.of("leaf", "middle"), Files.readAllLines(buildOrder));
    }

    @Test
    void skipsMissingDependenciesWithoutCloningThem() throws Exception {
        Path buildOrder = tmp.resolve("build-order.txt");
        Path workspace = createRecursiveWorkspace(buildOrder, null, false);

        CliResult result =
                cli.runCli(
                        workspace.resolve("src/root"),
                        recursiveEnvironment,
                        "workspace",
                        "execute-recursive",
                        "--",
                        "./wstool/bin/build.sh");

        CliHelper.assertSuccess(result);
        assertEquals(List.of("root"), Files.readAllLines(buildOrder));
        assertFalse(Files.exists(workspace.resolve("src/middle")));
        assertFalse(Files.exists(workspace.resolve("src/leaf")));
    }

    @Test
    void packageBuildRespectsNetworkIsolationAndSourceMountOptions() throws Exception {
        URI openRepository =
                createRepository(
                        "open-options",
                        CliHelper.resourceDirectory(
                                "/package-run/container-options/packages/open-options"));
        URI isolatedRepository =
                createRepository(
                        "isolated-options",
                        CliHelper.resourceDirectory(
                                "/package-run/container-options/packages/isolated-options"));
        URI distributionRepository =
                createRepository(
                        "container-options-distribution",
                        CliHelper.resourceDirectory("/package-run/container-options/distribution"));
        Path networkContent =
                CliHelper.resourceDirectory("/package-run/container-options/network.txt");

        try (LocalFileHttpServer server =
                new LocalFileHttpServer(
                        Map.of("/network", networkContent), new InetSocketAddress("0.0.0.0", 0))) {
            Map<String, String> environment =
                    testEnvironment(
                            Map.of(
                                    "OPEN_OPTIONS_REPOSITORY", openRepository.toString(),
                                    "ISOLATED_OPTIONS_REPOSITORY", isolatedRepository.toString(),
                                    "WSTOOL_TEST_NETWORK_URL",
                                            "http://host.docker.internal:"
                                                    + server.port()
                                                    + "/network"));
            Path workspace =
                    createWorkspace(
                            "container-options",
                            "/package-run/container-options/distribution",
                            environment);
            addPackage(workspace, "open-options", environment);
            addPackage(workspace, "isolated-options", environment);
            toolchain.prepare(workspace, cli, environment);

            CliResult openResult =
                    cli.runCli(
                            workspace.resolve("src/open-options"), environment, "package", "build");
            CliHelper.assertSuccess(openResult);
            assertTrue(
                    Files.isExecutable(
                            workspace.resolve("private/prefix/wstool/1.0.0/bin/wstool")));
            assertEquals(
                    "host network reachable\n",
                    Files.readString(
                            workspace.resolve("private/prefix/open-options/1.0.0/network.txt")));
            assertEquals(
                    "write-enabled\n",
                    Files.readString(workspace.resolve("src/open-options/source-write.txt")));

            CliResult isolatedResult =
                    cli.runCli(
                            workspace.resolve("src/isolated-options"),
                            environment,
                            "package",
                            "build");
            CliHelper.assertSuccess(isolatedResult);
            assertEquals(
                    "network-isolated\n",
                    Files.readString(
                            workspace.resolve(
                                    "private/prefix/isolated-options/1.0.0/network.txt")));
            assertFalse(
                    Files.exists(workspace.resolve("src/isolated-options/should-not-write.txt")));
            assertEquals(1, server.requestCount("/network"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"run", "build"})
    void cliBuildsPackageUsingHttpExternalSourcesAndLocalPatch(String buildMode) throws Exception {
        Path fixtureDirectory =
                TestResources.copyDirectory(
                        getClass(),
                        "/external-source-fixtures",
                        tmp.resolve("external-source-fixtures"));
        Path packageFixture = fixtureDirectory.resolve("e2e-package");
        Map<String, Path> servedFiles =
                Map.of(
                        "/project.tar.gz", fixtureDirectory.resolve("e2e-upstream/project.tar.gz"),
                        "/version.txt", fixtureDirectory.resolve("e2e-files/version.txt"));
        try (LocalFileHttpServer server = new LocalFileHttpServer(servedFiles)) {
            URI httpBase = server.baseUri();
            URI packageRepository = createRepository("external-source-e2e", packageFixture);
            Map<String, String> environment =
                    testEnvironment(
                            Map.of(
                                    "EXTERNAL_SOURCE_REPOSITORY", packageRepository.toString(),
                                    "WSTOOL_TEST_HTTP_BASE", httpBase.toString()));
            Path workspace =
                    createWorkspace(
                            "external-source-e2e",
                            "/package-run/external-source/distribution",
                            environment);
            addPackage(workspace, "external-source-e2e", environment);
            Path packageDirectory = workspace.resolve("src/external-source-e2e");
            toolchain.prepare(workspace, cli, environment);

            CliResult result =
                    runPackage(packageDirectory, environment, buildMode, "wstool/bin/build.sh");

            CliHelper.assertSuccess(result);
            Path intermediate = workspace.resolve("private/build-source/external-source-e2e/1.0");
            Path prefix = workspace.resolve("private/prefix/external-source-e2e/1.0");
            assertEquals(
                    "Hello from the patched third-party source archive.\n"
                            + "Overlay from the workspace package.\n"
                            + "Release version 2.7.3\n",
                    Files.readString(prefix.resolve("package-output.txt")));
            assertEquals(
                    expectedBuildPath(buildMode, workspace, intermediate) + "\n",
                    Files.readString(prefix.resolve("build-directory.txt")));
            assertEquals(
                    "Hello from the patched third-party source archive.\n",
                    Files.readString(intermediate.resolve("src/message.txt")));
            assertTrue(Files.isRegularFile(intermediate.resolve("README.txt")));
            assertEquals(
                    "Overlay from the workspace package.\n",
                    Files.readString(packageDirectory.resolve("src/package-overlay.txt")));
            assertFalse(Files.exists(packageDirectory.resolve("src/message.txt")));
            assertFalse(Files.exists(packageDirectory.resolve("data/version.txt")));
            assertTrue(server.requestCount("/project.tar.gz") <= 1);
            assertTrue(server.requestCount("/version.txt") <= 1);
        }
    }

    private Path createWorkspace(
            String name, String distributionFixture, Map<String, String> environment)
            throws Exception {
        URI distribution =
                createRepository("distribution", CliHelper.resourceDirectory(distributionFixture));
        CliResult result =
                cli.runCli(
                        tmp,
                        environment,
                        "workspace",
                        "create",
                        "--name",
                        name,
                        "--distribution",
                        distribution.toString());
        CliHelper.assertSuccess(result);
        return tmp.resolve(name);
    }

    private void addPackage(Path workspace, String packageName, Map<String, String> environment)
            throws Exception {
        CliHelper.assertSuccess(
                cli.runCli(workspace, environment, "workspace", "add", "--package", packageName));
    }

    private Path createRecursiveWorkspace(
            Path buildOrder, String failPackage, boolean addDependencies) throws Exception {
        URI leafRepository =
                createRepository(
                        "recursive-leaf",
                        CliHelper.resourceDirectory("/package-run/recursive/packages/leaf"));
        URI middleRepository =
                createRepository(
                        "recursive-middle",
                        CliHelper.resourceDirectory("/package-run/recursive/packages/middle"));
        URI rootRepository =
                createRepository(
                        "recursive-root",
                        CliHelper.resourceDirectory("/package-run/recursive/packages/root"));
        recursiveEnvironment =
                Map.of(
                        "RECURSIVE_LEAF_REPOSITORY", leafRepository.toString(),
                        "RECURSIVE_MIDDLE_REPOSITORY", middleRepository.toString(),
                        "RECURSIVE_ROOT_REPOSITORY", rootRepository.toString(),
                        "WST_BUILD_ORDER", buildOrder.toString(),
                        "WST_FAIL_PACKAGE", failPackage == null ? "" : failPackage);
        Path workspace =
                createWorkspace(
                        "recursive", "/package-run/recursive/distribution", recursiveEnvironment);
        addPackage(workspace, "root", recursiveEnvironment);
        if (addDependencies) {
            addPackage(workspace, "middle", recursiveEnvironment);
            addPackage(workspace, "leaf", recursiveEnvironment);
        }
        return workspace;
    }

    private CliResult runPackage(
            Path packageDirectory,
            Map<String, String> environment,
            String buildMode,
            String... targetArguments)
            throws Exception {
        List<String> arguments = new ArrayList<>(List.of("package", buildMode));
        arguments.addAll(List.of(targetArguments));
        return cli.runCli(packageDirectory, environment, arguments.toArray(String[]::new));
    }

    private Map<String, String> testEnvironment(Map<String, String> testEnvironment) {
        Map<String, String> environment = new LinkedHashMap<>(toolchain.environment());
        environment.putAll(testEnvironment);
        return Map.copyOf(environment);
    }

    private String expectedBuildPath(String buildMode, Path workspace, Path hostPath) {
        if (buildMode.equals("build")) {
            return Path.of("/wstool/workspace").resolve(workspace.relativize(hostPath)).toString();
        }
        return hostPath.toAbsolutePath().toString();
    }

    private URI createRepository(String name, Path contents) throws Exception {
        String repository = name + "-" + UUID.randomUUID().toString().replace("-", "");
        URI uri = git.create(repository);
        git.uploadFiles(repository, contents);
        return uri;
    }
}
