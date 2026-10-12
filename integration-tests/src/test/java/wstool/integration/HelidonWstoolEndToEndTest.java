package wstool.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pkl.config.java.JavaType;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import wstool.Distribution.PackageSource;
import wstool.importer.CachedMavenProxy;
import wstool.npm.importer.CachedNpmProxy;
import wstool.simple.PklHelper;

/** Verifies imported Helidon artifacts and a REST application build offline through wstool. */
@Testcontainers(disabledWithoutDocker = true)
class HelidonWstoolEndToEndTest {
    private static final String ACCESS_KEY = "GK0123456789abcdef01234567";
    private static final String SECRET_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String BUCKET = "wstool-helidon-integration";
    private static final String BUILD_IMAGE = "wstool-helidon-integration:latest";
    private static final String NPM_BUILD_IMAGE = "wstool-npm-integration:latest";
    private static final String HELIDON_COORDINATE = "io.helidon.webserver:helidon-webserver:4.5.4";

    @Container
    private static final GenericContainer<?> garage =
            new GenericContainer<>(DockerImageName.parse("dxflrs/garage:v2.4.1"))
                    .withExposedPorts(3900)
                    .withTmpFs(Map.of("/meta", "rw", "/data", "rw"))
                    .withCopyToContainer(
                            MountableFile.forClasspathResource("garage.toml"), "/etc/garage.toml")
                    .withCommand("/garage", "-c", "/etc/garage.toml", "server")
                    .waitingFor(Wait.forLogMessage(".*S3 API server listening.*\\n", 1));

    @TempDir Path tempDir;
    private String originalUserHome;

    @BeforeEach
    void useLocallyGeneratedPklPackage() throws Exception {
        originalUserHome = System.getProperty("user.home");
        pklHomeOverride = tempDir.resolve("pkl-home");
        Path packageCache =
                pklHomeOverride.resolve(".pkl/cache/package-2/pkl.konna.cloud/pkl/wstool@1.0.2");
        Files.createDirectories(packageCache);
        Path generatedPackage = Path.of("../config/build/generated/pkl/packages");
        Files.copy(
                generatedPackage.resolve("wstool@1.0.2"),
                packageCache.resolve("wstool@1.0.2.json"));
        Files.copy(
                generatedPackage.resolve("wstool@1.0.2.zip"),
                packageCache.resolve("wstool@1.0.2.zip"));
        System.setProperty("user.home", pklHomeOverride.toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    @BeforeAll
    static void initializeGarage() throws Exception {
        String nodeId =
                garageCommand("node", "id").lines().findFirst().orElseThrow().split("@", 2)[0];
        garageCommand("layout", "assign", "-z", "test-zone", "-c", "1G", nodeId);
        garageCommand("layout", "apply", "--version", "1");
        garageCommand("key", "import", "--yes", "-n", "wstool-helidon", ACCESS_KEY, SECRET_KEY);
        garageCommand("bucket", "create", BUCKET);
        garageCommand(
                "bucket", "allow", "--read", "--write", "--owner", "--key", ACCESS_KEY, BUCKET);
        garageCommand("bucket", "create", "wstool-npm-integration");
        garageCommand(
                "bucket",
                "allow",
                "--read",
                "--write",
                "--owner",
                "--key",
                ACCESS_KEY,
                "wstool-npm-integration");
    }

    @Test
    void importsHelidonAndBuildsAndRunsRestApiWithoutBuildContainerNetwork() throws Exception {
        new ImageFromDockerfile(BUILD_IMAGE, false)
                .withFileFromPath("Dockerfile", resourcePath("helidon/Dockerfile"))
                .get();

        Path imported = tempDir.resolve("maven-import");
        Path proxyCache = Path.of("build/integration-maven-proxy-cache").toAbsolutePath();
        int importedPackages;
        try (var proxy = new CachedMavenProxy(proxyCache)) {
            int status =
                    runImporterCli(
                            "maven",
                            "--output",
                            imported.toString(),
                            "--repository",
                            proxy.url(),
                            "--external-files-url",
                            Path.of("build/integration-maven-local-repository")
                                    .toAbsolutePath()
                                    .toUri()
                                    .toString(),
                            "--local-repository",
                            Path.of("build/integration-maven-local-repository")
                                    .toAbsolutePath()
                                    .toString(),
                            HELIDON_COORDINATE);
            assertEquals(0, status, "wstool-import failed to resolve Helidon");
            importedPackages = readPackageSources(imported.resolve("packages.pkl")).size();
            assertTrue(
                    importedPackages > 1,
                    "Helidon transitive Maven dependencies were not imported");
            Path helidonPackage =
                    imported.resolve(
                            "package-tree/packages/io_helidon_webserver_helidon-webserver/4.x/wstool.pkl");
            try (var pkl = new PklHelper(Map.of())) {
                var packageConfig = pkl.evaluate(helidonPackage).as(wstool.Package.class);
                var externalFiles =
                        (wstool.Package.ExternalFiles) packageConfig.getImportOptions().getSource();
                assertTrue(
                        externalFiles
                                .getFiles()
                                .containsKey(
                                        "repository/io/helidon/webserver/helidon-webserver-project/4.5.4/helidon-webserver-project-4.5.4.pom"),
                        "Helidon parent POM was not included as an external file");
            }
        }

        Path appRepository = tempDir.resolve("helidon-project");
        copyTree(resourcePath("helidon/project"), appRepository);
        currentProjectRepository = appRepository;
        initializeGitRepository(appRepository);

        Path distribution = tempDir.resolve("distribution");
        Files.createDirectories(distribution);
        Files.copy(imported.resolve("packages.pkl"), distribution.resolve("packages.pkl"));
        Files.copy(
                resourcePath("helidon/distribution/world.pkl"), distribution.resolve("world.pkl"));
        initializeGitRepository(distribution);

        Path workspaces = tempDir.resolve("workspaces");
        Files.createDirectories(workspaces);
        runCli(workspaces, "workspace", "create", "--name", "helidon-rest");
        Path workspace = workspaces.resolve("helidon-rest");
        runCli(
                workspace,
                "workspace",
                "setup",
                "--distro",
                "helidon-offline-integration",
                "--from",
                distribution.toString(),
                "--branch",
                "main");
        List<String> addArguments = new java.util.ArrayList<>(List.of("workspace", "add"));
        List<ImportedPackage> mavenPackages =
                importedPackages(imported.resolve("package-tree/packages"));
        for (ImportedPackage dependency : mavenPackages) {
            addArguments.add("--package");
            addArguments.add(dependency.name());
        }
        addArguments.add("--package");
        addArguments.add("helidon-rest");
        runCli(workspace, addArguments.toArray(String[]::new));
        for (ImportedPackage dependency : mavenPackages) {
            runCli(workspace, "build", dependency.name() + "@" + dependency.version());
        }
        runCli(workspace, "build", "helidon-rest@1.0.0");

        Path installDirectory =
                workspace.resolve("private/prefix/helidon-rest/gradle-build/install");
        Path application;
        try (var paths = Files.walk(installDirectory)) {
            application =
                    paths.filter(Files::isExecutable)
                            .filter(path -> path.getFileName().toString().equals("helidon-rest"))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new AssertionError(
                                                    "Gradle did not install the Helidon application"));
        }
        assertTrue(
                Files.isExecutable(application), "Gradle did not install the Helidon application");
        verifyRestApi(application);
    }

    @Test
    void importsNpmPackagesAndBuildsProjectWithoutNpmRegistryAccess() throws Exception {
        new ImageFromDockerfile(NPM_BUILD_IMAGE, false)
                .withFileFromPath("Dockerfile", resourcePath("npm/Dockerfile"))
                .get();

        Path imported = tempDir.resolve("npm-import");
        Path proxyCache = Path.of("build/integration-npm-registry-cache").toAbsolutePath();
        try (var proxy = new CachedNpmProxy(proxyCache)) {
            int status =
                    runImporterCli(
                            "npm",
                            "--output",
                            imported.toString(),
                            "--registry",
                            proxy.url(),
                            "--cache",
                            Path.of("build/integration-npm-local-cache")
                                    .toAbsolutePath()
                                    .toString(),
                            "is-number@7.0.0");
            assertEquals(0, status, "wstool-import npm failed to resolve package dependencies");
            assertEquals(0, proxy.tarballRequests(), "npm import should resolve metadata only");

            Path appRepository = tempDir.resolve("npm-project");
            copyTree(resourcePath("npm/project"), appRepository);
            Files.copy(
                    imported.resolve("npm-dependencies.pkl"),
                    appRepository.resolve("npm-dependencies.pkl"));
            currentProjectRepository = appRepository;
            initializeGitRepository(appRepository);

            Path distribution = tempDir.resolve("npm-distribution");
            Files.createDirectories(distribution);
            Files.copy(imported.resolve("packages.pkl"), distribution.resolve("packages.pkl"));
            Files.copy(
                    resourcePath("npm/distribution/world.pkl"), distribution.resolve("world.pkl"));
            initializeGitRepository(distribution);

            Path workspaces = tempDir.resolve("npm-workspaces");
            Files.createDirectories(workspaces);
            runCli(workspaces, "workspace", "create", "--name", "npm-offline");
            Path workspace = workspaces.resolve("npm-offline");
            runCli(
                    workspace,
                    "workspace",
                    "setup",
                    "--distro",
                    "npm-offline-integration",
                    "--from",
                    distribution.toString(),
                    "--branch",
                    "main");
            List<String> addArguments = new java.util.ArrayList<>(List.of("workspace", "add"));
            List<ImportedPackage> npmPackages =
                    importedNpmPackages(imported.resolve("package-tree/packages"));
            for (ImportedPackage dependency : npmPackages) {
                addArguments.add("--package");
                addArguments.add(dependency.name());
            }
            addArguments.add("--package");
            addArguments.add("wstool-npm-import");
            addArguments.add("--package");
            addArguments.add("npm-demo");
            runCli(workspace, addArguments.toArray(String[]::new));

            ImportedPackage isNumber =
                    npmPackages.stream()
                            .filter(pkg -> pkg.name().equals("npm_is-number"))
                            .findFirst()
                            .orElseThrow();
            Path importedIsNumber = workspace.resolve("src").resolve(isNumber.name());
            assertTrue(
                    Files.isRegularFile(importedIsNumber.resolve("package.json")),
                    "npm tarball contents should be placed at the imported package source root");
            assertFalse(
                    Files.exists(importedIsNumber.resolve("package/package.json")),
                    "npm tarball's package/ wrapper directory should be stripped");

            runCli(workspace, "build", "wstool-npm-import@1.0.0");
            for (ImportedPackage dependency : npmPackages) {
                runCli(workspace, "build", dependency.name() + "@" + dependency.version());
            }
            runCli(workspace, "build", "npm-demo@1.0.0");

            Path builtProject =
                    workspace.resolve(
                            "private/prefix/npm-demo/lib/node_modules/npm-demo/dist/result.txt");
            assertEquals("true", Files.readString(builtProject));
            assertTrue(proxy.tarballRequests() > 0, "Build should fetch the external npm tarball");
        }
    }

    @Test
    void overlaysAndPatchesThirdPartySourceThenBuildsWithLocalPackageScript() throws Exception {
        Path packageRepository = tempDir.resolve("third-party-package");
        copyTree(resourcePath("third-party/package"), packageRepository);
        assertTrue(
                packageRepository.resolve("wstool/bin/local-build").toFile().setExecutable(true));
        initializeGitRepository(packageRepository);
        currentProjectRepository = packageRepository;
        thirdPartyLocalRepository = packageRepository;

        Path upstreamRepository = tempDir.resolve("third-party-upstream");
        copyTree(resourcePath("third-party/upstream"), upstreamRepository);
        initializeGitRepository(upstreamRepository);
        thirdPartyUpstreamRepository = upstreamRepository;

        Path distribution = tempDir.resolve("third-party-distribution");
        Files.createDirectories(distribution);
        Files.copy(
                resourcePath("third-party/distribution/world.pkl"),
                distribution.resolve("world.pkl"));
        initializeGitRepository(distribution);

        Path workspaces = tempDir.resolve("third-party-workspaces");
        Files.createDirectories(workspaces);
        runCli(workspaces, "workspace", "create", "--name", "third-party-workspace");
        Path workspace = workspaces.resolve("third-party-workspace");
        runCli(
                workspace,
                "workspace",
                "setup",
                "--distro",
                "third-party-integration",
                "--from",
                distribution.toUri().toString(),
                "--branch",
                "main");
        runCli(workspace, "workspace", "add", "--package", "third-party");

        Path importedSource = workspace.resolve("src/third-party");
        assertEquals(
                "patched upstream source\n",
                Files.readString(importedSource.resolve("src/value.txt")));
        assertEquals(
                "local package overlay\n",
                Files.readString(importedSource.resolve("src/local-overlay.txt")));
        assertFalse(Files.exists(importedSource.resolve(".git")));

        Path fakeDocker = tempDir.resolve("fake-docker-bin");
        Files.createDirectories(fakeDocker);
        Path dockerExecutable = fakeDocker.resolve("docker");
        Files.copy(resourcePath("third-party/fake-docker"), dockerExecutable);
        assertTrue(dockerExecutable.toFile().setExecutable(true));
        dockerShimDirectory = fakeDocker;
        try {
            runCli(workspace, "build", "third-party@1.0.0");
        } finally {
            dockerShimDirectory = null;
        }

        assertEquals(
                "built by the local package script\n",
                Files.readString(workspace.resolve("private/prefix/third-party/result.txt")));
    }

    private void verifyRestApi(Path application) throws Exception {
        int port = 18080;
        ProcessBuilder serverBuilder =
                new ProcessBuilder(application.toString())
                        .directory(application.getParent().getParent().toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(tempDir.resolve("helidon-server.log").toFile());
        serverBuilder.environment().put("PORT", Integer.toString(port));
        Process server = serverBuilder.start();
        try {
            HttpClient client =
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> hello = requestWhenReady(client, port, "/api/hello", server);
            HttpResponse<String> status = requestWhenReady(client, port, "/api/status", server);
            assertEquals(200, hello.statusCode());
            assertEquals("{\"message\":\"hello\"}", hello.body());
            assertEquals(200, status.statusCode());
            assertEquals("{\"status\":\"ok\"}", status.body());
        } finally {
            server.destroy();
            if (!server.waitFor(10, TimeUnit.SECONDS)) {
                server.destroyForcibly();
            }
        }
    }

    private HttpResponse<String> requestWhenReady(
            HttpClient client, int port, String path, Process server) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            if (!server.isAlive()) {
                throw new IllegalStateException("Helidon server exited before becoming ready");
            }
            try {
                return client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                lastFailure = e;
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("Helidon REST endpoint did not start", lastFailure);
    }

    private void runCli(Path directory, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(requiredEnv("WSTOOL_CLI_LAUNCHER"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().put("AWS_ACCESS_KEY_ID", ACCESS_KEY);
        builder.environment().put("AWS_SECRET_ACCESS_KEY", SECRET_KEY);
        builder.environment().put("AWS_REGION", "us-east-1");
        builder.environment().put("AWS_S3_ENDPOINT", s3Endpoint());
        builder.environment().put("WST_BUILD_IMAGE", BUILD_IMAGE);
        if (pklHomeOverride != null) {
            builder.environment().put("HOME", pklHomeOverride.toString());
            builder.environment().put("JAVA_TOOL_OPTIONS", "-Duser.home=" + pklHomeOverride);
        }
        if (thirdPartyLocalRepository != null) {
            builder.environment()
                    .put(
                            "WSTOOL_THIRD_PARTY_LOCAL_REPO",
                            thirdPartyLocalRepository.toUri().toString());
        }
        if (thirdPartyUpstreamRepository != null) {
            builder.environment()
                    .put(
                            "WSTOOL_THIRD_PARTY_UPSTREAM_REPO",
                            thirdPartyUpstreamRepository.toUri().toString());
        }
        if (dockerShimDirectory != null) {
            builder.environment()
                    .put(
                            "PATH",
                            dockerShimDirectory
                                    + java.io.File.pathSeparator
                                    + System.getenv().getOrDefault("PATH", ""));
        }
        builder.environment().put("WSTOOL_CLI_ARCHIVE", requiredEnv("WSTOOL_CLI_ARCHIVE"));
        builder.environment().put("WSTOOL_CLI_CHECKSUM", sha256(archivePath("WSTOOL_CLI_ARCHIVE")));
        builder.environment()
                .put(
                        "WSTOOL_GRADLE_PACKAGE_URI",
                        archivePath("WSTOOL_GRADLE_PACKAGE").toUri().toString());
        builder.environment()
                .put(
                        "WSTOOL_GRADLE_PACKAGE_CHECKSUM",
                        sha256(archivePath("WSTOOL_GRADLE_PACKAGE")));
        builder.environment()
                .put(
                        "WSTOOL_GRADLE_RUNNER_PACKAGE_URI",
                        archivePath("WSTOOL_GRADLE_RUNNER_PACKAGE").toUri().toString());
        builder.environment()
                .put(
                        "WSTOOL_GRADLE_RUNNER_PACKAGE_CHECKSUM",
                        sha256(archivePath("WSTOOL_GRADLE_RUNNER_PACKAGE")));
        builder.environment()
                .put(
                        "WSTOOL_MAVEN_TOOL_PACKAGE_URI",
                        archivePath("WSTOOL_MAVEN_TOOL_PACKAGE").toUri().toString());
        builder.environment()
                .put(
                        "WSTOOL_MAVEN_TOOL_PACKAGE_CHECKSUM",
                        sha256(archivePath("WSTOOL_MAVEN_TOOL_PACKAGE")));
        builder.environment()
                .put("WSTOOL_HELIDON_PROJECT_REPO", currentProjectRepository.toUri().toString());
        builder.environment()
                .put("WSTOOL_NPM_PROJECT_REPO", currentProjectRepository.toUri().toString());
        builder.environment()
                .put(
                        "WSTOOL_NPM_IMPORT_PACKAGE_URI",
                        archivePath("WSTOOL_NPM_IMPORT_PACKAGE").toUri().toString());
        builder.environment()
                .put(
                        "WSTOOL_NPM_IMPORT_PACKAGE_CHECKSUM",
                        sha256(archivePath("WSTOOL_NPM_IMPORT_PACKAGE")));
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(
                0,
                process.waitFor(),
                "wstool failed: " + String.join(" ", arguments) + "\n" + output);
    }

    private int runImporterCli(String subcommand, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(requiredEnv("WSTOOL_IMPORT_LAUNCHER"));
        command.add(subcommand);
        command.addAll(List.of(arguments));
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(tempDir.toFile()).redirectErrorStream(true);
        if (pklHomeOverride != null) {
            builder.environment().put("HOME", pklHomeOverride.toString());
            builder.environment().put("JAVA_TOOL_OPTIONS", "-Duser.home=" + pklHomeOverride);
        }
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int status = process.waitFor();
        assertEquals(
                0, status, "wstool-import failed: " + String.join(" ", arguments) + "\n" + output);
        return status;
    }

    private Path currentProjectRepository;
    private Path thirdPartyLocalRepository;
    private Path thirdPartyUpstreamRepository;
    private Path dockerShimDirectory;
    private Path pklHomeOverride;

    private void initializeGitRepository(Path repository) throws Exception {
        run(repository, "git", "init", "--initial-branch=main");
        run(repository, "git", "config", "user.name", "wstool integration test");
        run(repository, "git", "config", "user.email", "wstool-test@example.invalid");
        run(repository, "git", "add", ".");
        run(repository, "git", "commit", "--message", "Helidon offline integration fixture");
    }

    private void run(Path directory, String... command) throws Exception {
        Process process =
                new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), String.join(" ", command) + " failed:\n" + output);
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Path archivePath(String environment) {
        String value = requiredEnv(environment);
        return value.startsWith("file:") ? Path.of(URI.create(value)) : Path.of(value);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing integration test setting " + name);
        }
        return value;
    }

    private static String s3Endpoint() {
        return "http://" + garage.getHost() + ":" + garage.getMappedPort(3900);
    }

    private static Path resourcePath(String name) throws Exception {
        var resource = HelidonWstoolEndToEndTest.class.getClassLoader().getResource(name);
        if (resource == null) {
            throw new IOException("Test resource not found: " + name);
        }
        return Path.of(resource.toURI());
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }

    private List<PackageSource> readPackageSources(Path path) throws Exception {
        try (var pkl = new PklHelper(Map.of())) {
            return pkl.evaluate(path).get("packages").as(JavaType.listOf(PackageSource.class));
        }
    }

    private List<ImportedPackage> importedPackages(Path packagesRoot) throws IOException {
        try (var names = Files.list(packagesRoot)) {
            return names.filter(Files::isDirectory)
                    .flatMap(
                            packageDirectory -> {
                                try (var versions = Files.list(packageDirectory)) {
                                    return versions
                                            .filter(Files::isDirectory)
                                            .map(
                                                    versionDirectory ->
                                                            new ImportedPackage(
                                                                    packageDirectory
                                                                            .getFileName()
                                                                            .toString(),
                                                                    versionDirectory
                                                                            .getFileName()
                                                                            .toString()))
                                            .toList()
                                            .stream();
                                } catch (IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            })
                    .toList();
        }
    }

    private List<ImportedPackage> importedNpmPackages(Path packagesRoot) throws IOException {
        try (var paths = Files.walk(packagesRoot)) {
            return paths.filter(Files::isDirectory)
                    .filter(directory -> Files.isRegularFile(directory.resolve("wstool.pkl")))
                    .map(
                            directory -> {
                                Path relative = packagesRoot.relativize(directory);
                                Path npmName = relative.getParent();
                                String name =
                                        "npm_"
                                                + npmName.toString()
                                                        .replace(
                                                                directory
                                                                        .getFileSystem()
                                                                        .getSeparator(),
                                                                "/");
                                return new ImportedPackage(name, relative.getFileName().toString());
                            })
                    .toList();
        }
    }

    private record ImportedPackage(String name, String version) {}

    private static String garageCommand(String... arguments) throws Exception {
        List<String> command =
                new java.util.ArrayList<>(List.of("/garage", "-c", "/etc/garage.toml"));
        command.addAll(List.of(arguments));
        var result = garage.execInContainer(command.toArray(String[]::new));
        if (result.getExitCode() != 0) {
            throw new IOException(
                    "Garage command failed: " + result.getStdout() + result.getStderr());
        }
        return result.getStdout();
    }
}
