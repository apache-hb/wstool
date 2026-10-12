package wstool.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pkl.config.java.JavaType;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import wstool.Distribution.PackageSource;
import wstool.Package;
import wstool.Package.ExternalFiles;
import wstool.importer.CachedMavenProxy;
import wstool.npm.importer.CachedNpmProxy;
import wstool.simple.PklHelper;
import wstool.test.components.PackageGitManager;
import wstool.test.components.StorageServerManager;

/** Verifies imported Helidon artifacts and a REST application build offline through wstool. */
@Testcontainers(disabledWithoutDocker = true)
class HelidonWstoolEndToEndTest {
    private static final String BUCKET = "wstool-helidon-integration";
    private static final String NPM_BUCKET = "wstool-npm-integration";
    private static final String BUILD_IMAGE = "wstool-helidon-integration:latest";
    private static final String NPM_BUILD_IMAGE = "wstool-npm-integration:latest";
    private static final String HELIDON_COORDINATE = "io.helidon.webserver:helidon-webserver:4.5.4";

    @Container private static final GenericContainer<?> GARAGE = StorageServerManager.container();

    @Container private static final GiteaContainer GITEA = PackageGitManager.container();
    private static final StorageServerManager STORAGE = new StorageServerManager(GARAGE);

    @AutoClose private static S3Client helidonStorageClient;

    @AutoClose private static S3Client npmStorageClient;

    @TempDir Path tempDir;
    private S3Client activeStorageClient;
    private PackageGitManager gitManager;

    @BeforeEach
    void prepareGitManager() {
        gitManager = new PackageGitManager(tempDir, GITEA);
    }

    @BeforeAll
    static void initializeStorageServer() throws Exception {
        STORAGE.setup();
        helidonStorageClient = STORAGE.createBucket(BUCKET);
        npmStorageClient = STORAGE.createBucket(NPM_BUCKET);
    }

    @Test
    void importsHelidonAndBuildsAndRunsRestApiWithoutBuildContainerNetwork() throws Exception {
        activeStorageClient = helidonStorageClient;
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
                var packageConfig = pkl.evaluate(helidonPackage).as(Package.class);
                var externalFiles = (ExternalFiles) packageConfig.getImportOptions().getSource();
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
        currentProjectRepository = uploadRepository("helidon-project", appRepository);

        Path distribution = tempDir.resolve("distribution");
        Files.createDirectories(distribution);
        Files.copy(imported.resolve("packages.pkl"), distribution.resolve("packages.pkl"));
        Files.copy(
                resourcePath("helidon/distribution/distribution.pkl"),
                distribution.resolve("distribution.pkl"));
        URI distributionRepository = uploadRepository("helidon-distribution", distribution);

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
                distributionRepository.toString(),
                "--branch",
                "master");
        List<String> addArguments = new ArrayList<>(List.of("workspace", "add"));
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
        activeStorageClient = npmStorageClient;
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
            currentProjectRepository = uploadRepository("npm-project", appRepository);

            Path distribution = tempDir.resolve("npm-distribution");
            Files.createDirectories(distribution);
            Files.copy(imported.resolve("packages.pkl"), distribution.resolve("packages.pkl"));
            Files.copy(
                    resourcePath("npm/distribution/distribution.pkl"),
                    distribution.resolve("distribution.pkl"));
            URI distributionRepository = uploadRepository("npm-distribution", distribution);

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
                    distributionRepository.toString(),
                    "--branch",
                    "master");
            List<String> addArguments = new ArrayList<>(List.of("workspace", "add"));
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
        activeStorageClient = helidonStorageClient;
        Path packageRepository = tempDir.resolve("third-party-package");
        copyTree(resourcePath("third-party/package"), packageRepository);
        assertTrue(
                packageRepository.resolve("wstool/bin/local-build").toFile().setExecutable(true));
        URI packageRepositoryUri = uploadRepository("third-party-package", packageRepository);
        currentProjectRepository = packageRepositoryUri;
        thirdPartyLocalRepository = packageRepositoryUri;

        Path upstreamRepository = tempDir.resolve("third-party-upstream");
        copyTree(resourcePath("third-party/upstream"), upstreamRepository);
        thirdPartyUpstreamRepository = uploadRepository("third-party-upstream", upstreamRepository);

        Path distribution = tempDir.resolve("third-party-distribution");
        Files.createDirectories(distribution);
        Files.copy(
                resourcePath("third-party/distribution/distribution.pkl"),
                distribution.resolve("distribution.pkl"));
        URI distributionRepository = uploadRepository("third-party-distribution", distribution);

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
                distributionRepository.toString(),
                "--branch",
                "master");
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
        List<String> command = new ArrayList<>();
        command.add(requiredEnv("WSTOOL_CLI_LAUNCHER"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        var credentials =
                activeStorageClient
                        .serviceClientConfiguration()
                        .credentialsProvider()
                        .resolveIdentity()
                        .join();
        builder.environment().put("AWS_ACCESS_KEY_ID", credentials.accessKeyId());
        builder.environment().put("AWS_SECRET_ACCESS_KEY", credentials.secretAccessKey());
        builder.environment().put("AWS_REGION", "us-east-1");
        builder.environment().put("AWS_S3_ENDPOINT", s3Endpoint());
        builder.environment().put("WST_BUILD_IMAGE", BUILD_IMAGE);
        if (thirdPartyLocalRepository != null) {
            builder.environment()
                    .put("WSTOOL_THIRD_PARTY_LOCAL_REPO", thirdPartyLocalRepository.toString());
        }
        if (thirdPartyUpstreamRepository != null) {
            builder.environment()
                    .put(
                            "WSTOOL_THIRD_PARTY_UPSTREAM_REPO",
                            thirdPartyUpstreamRepository.toString());
        }
        if (dockerShimDirectory != null) {
            builder.environment()
                    .put(
                            "PATH",
                            dockerShimDirectory
                                    + File.pathSeparator
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
                .put("WSTOOL_HELIDON_PROJECT_REPO", currentProjectRepository.toString());
        builder.environment().put("WSTOOL_NPM_PROJECT_REPO", currentProjectRepository.toString());
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
        List<String> command = new ArrayList<>();
        command.add(requiredEnv("WSTOOL_IMPORT_LAUNCHER"));
        command.add(subcommand);
        command.addAll(List.of(arguments));
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(tempDir.toFile()).redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int status = process.waitFor();
        assertEquals(
                0, status, "wstool-import failed: " + String.join(" ", arguments) + "\n" + output);
        return status;
    }

    private URI currentProjectRepository;
    private URI thirdPartyLocalRepository;
    private URI thirdPartyUpstreamRepository;
    private Path dockerShimDirectory;

    private URI uploadRepository(String name, Path directory) throws Exception {
        String repositoryName = name + "-" + UUID.randomUUID().toString().replace("-", "");
        URI repository = gitManager.create(repositoryName);
        gitManager.uploadFiles(repositoryName, directory);
        return repository;
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
        return STORAGE.endpoint().toString();
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
                                    throw new UncheckedIOException(e);
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
}
