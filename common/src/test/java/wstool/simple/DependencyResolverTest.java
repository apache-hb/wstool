package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import dev.parsick.testcontainers.gitserver.gitea.GiteaVersions;
import io.gitea.ApiClient;
import io.gitea.api.RepositoryApi;
import io.gitea.model.CreateRepoOption;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import wstool.simple.git.ImmutableGitRepositoryLocation;
import wstool.simple.git.ImmutableGitRunInput;
import wstool.simple.workspace.ImmutableAddPackageToWorkspaceInput;
import wstool.simple.workspace.ImmutableCreateWorkspaceInput;

@Testcontainers(disabledWithoutDocker = true)
class DependencyResolverTest {
    private static final String BUCKET = "dependency-resolver-test";
    private static final String ACCESS_KEY = "GK0123456789abcdef01234567";
    private static final String SECRET_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final List<String> PACKAGE_NAMES =
            List.of(
                    "app",
                    "compiler",
                    "compiler-common",
                    "compiler-runtime",
                    "compiler-build-only",
                    "builder-script",
                    "builder-helper",
                    "public-api",
                    "shared",
                    "runtime-lib",
                    "test-framework",
                    "test-support",
                    "not-selected",
                    "alternate",
                    "alternate-runtime");
    private static final List<String> CLONED_PACKAGE_NAMES =
            List.of(
                    "app",
                    "compiler-runtime",
                    "builder-script",
                    "public-api",
                    "test-framework",
                    "alternate");

    @Container
    private static final GiteaContainer gitServer =
            new GiteaContainer(GiteaVersions.V1_25_4.getDockerImageName())
                    .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    private static final GenericContainer<?> garage =
            new GenericContainer<>(DockerImageName.parse("dxflrs/garage:v2.4.1"))
                    .withExposedPorts(3900)
                    .withTmpFs(Map.of("/meta", "rw", "/data", "rw"))
                    .withCopyToContainer(
                            MountableFile.forClasspathResource("garage/garage.toml"),
                            "/etc/garage.toml")
                    .withCommand("/garage", "-c", "/etc/garage.toml", "server")
                    .waitingFor(Wait.forLogMessage(".*S3 API server listening.*\\n", 1))
                    .withStartupTimeout(Duration.ofMinutes(2));

    @TempDir Path tempDir;

    private RepositoryApi giteaApi;

    @AutoClose private S3Client s3;

    @AutoClose private PklHelper pkl;
    private WorkspaceHelper workspaceHelper;
    private CacheHelper cache;
    private StorageHelper storage;
    private String repositorySuffix;

    @BeforeAll
    static void initializeGarage() throws Exception {
        String nodeId = runGarage("node", "id").lines().findFirst().orElseThrow().split("@", 2)[0];
        runGarage("layout", "assign", "-z", "test-zone", "-c", "1G", nodeId);
        runGarage("layout", "apply", "--version", "1");
        runGarage(
                "key", "import", "--yes", "-n", "dependency-resolver-test", ACCESS_KEY, SECRET_KEY);
        runGarage("bucket", "create", BUCKET);
        runGarage("bucket", "allow", "--read", "--write", "--owner", "--key", ACCESS_KEY, BUCKET);
    }

    @BeforeEach
    void setUp() {
        ApiClient apiClient =
                new ApiClient()
                        .setBasePath(
                                "http://"
                                        + gitServer.getHost()
                                        + ":"
                                        + gitServer.getMappedPort(3000)
                                        + "/api/v1");
        apiClient.setUsername(gitServer.getUserName());
        apiClient.setPassword(gitServer.getUserPassword());
        giteaApi = new RepositoryApi(apiClient);

        String gitEndpoint = "http://" + gitServer.getHost() + ":" + gitServer.getMappedPort(3000);
        repositorySuffix = UUID.randomUUID().toString().replace("-", "");
        pkl =
                new PklHelper(
                        Map.of(
                                "GIT_ENDPOINT", gitEndpoint,
                                "GIT_USERNAME", gitServer.getUserName(),
                                "REPOSITORY_SUFFIX", repositorySuffix));
        storage = new StorageHelper();
        workspaceHelper =
                new WorkspaceHelper(new GitHelper(false), storage, new DistributionHelper(pkl));

        s3 =
                S3Client.builder()
                        .endpointOverride(
                                URI.create(
                                        "http://"
                                                + garage.getHost()
                                                + ":"
                                                + garage.getMappedPort(3900)))
                        .region(Region.of("garage"))
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                        .serviceConfiguration(
                                S3Configuration.builder()
                                        .pathStyleAccessEnabled(true)
                                        .chunkedEncodingEnabled(false)
                                        .build())
                        .build();
        cache = new CacheHelper(s3, new ArchiveHelper());
    }

    @Test
    void assemblesDevelopmentSysrootFromLocallyClonedAndCachedPackages() throws Exception {
        Path workspace = createWorkspace();
        DependencyResolver resolver = new DependencyResolver();

        Path sysroot = resolver.createClosure(workspaceHelper, workspace, "app", "development");

        for (String packageName :
                List.of(
                        "compiler",
                        "compiler-common",
                        "compiler-runtime",
                        "builder-script",
                        "builder-helper",
                        "public-api",
                        "shared",
                        "runtime-lib",
                        "test-framework",
                        "test-support")) {
            assertTrue(
                    Files.isSymbolicLink(sysroot.resolve("include").resolve(packageName + ".h")),
                    "Missing sysroot link for " + packageName);
            assertEquals(
                    "header from package\n",
                    Files.readString(sysroot.resolve("include").resolve(packageName + ".h")));
        }

        assertFalse(Files.exists(sysroot.resolve("include/not-selected.h")));
        assertFalse(Files.exists(sysroot.resolve("include/app.h")));
        Path compilerLink = sysroot.resolve("bin/compiler");
        assertTrue(Files.isSymbolicLink(compilerLink));
        assertEquals(
                workspaceHelper.artifactPackages(workspace).stream()
                        .filter(pkg -> pkg.version().name().equals("compiler"))
                        .findFirst()
                        .orElseThrow()
                        .prefix()
                        .resolve("bin/compiler")
                        .toAbsolutePath(),
                Files.readSymbolicLink(compilerLink));
        Path localPackageLink = sysroot.resolve("include/public-api.h");
        assertEquals(
                workspaceHelper.sourcePackages(workspace).stream()
                        .filter(pkg -> pkg.version().name().equals("public-api"))
                        .findFirst()
                        .orElseThrow()
                        .prefix()
                        .resolve("include/public-api.h")
                        .toAbsolutePath(),
                Files.readSymbolicLink(localPackageLink));
        assertTrue(Files.isSymbolicLink(sysroot.resolve("lib/libshared.so")));
        assertEquals("shared library\n", Files.readString(sysroot.resolve("lib/libshared.so")));
    }

    @Test
    void buildAndRuntimeSelectorsCollectTheirDocumentedGraphs() throws Exception {
        Path workspace = createWorkspace();
        DependencyResolver resolver = new DependencyResolver();

        Path build = resolver.createClosure(workspaceHelper, workspace, "app", "build");
        assertTrue(Files.exists(build.resolve("bin/compiler")));
        assertTrue(Files.exists(build.resolve("include/compiler-runtime.h")));
        assertTrue(Files.exists(build.resolve("include/builder-helper.h")));
        assertFalse(Files.exists(build.resolve("include/public-api.h")));
        assertFalse(Files.exists(build.resolve("include/test-framework.h")));

        Path runtime = resolver.createClosure(workspaceHelper, workspace, "app", "runtime");
        assertTrue(Files.exists(runtime.resolve("include/public-api.h")));
        assertTrue(Files.exists(runtime.resolve("include/runtime-lib.h")));
        assertTrue(Files.exists(runtime.resolve("include/shared.h")));
        assertFalse(Files.exists(runtime.resolve("bin/compiler")));
        assertFalse(Files.exists(runtime.resolve("include/test-framework.h")));
    }

    @Test
    void qualifiedSelectorChangesTheTraversalRoot() throws Exception {
        Path workspace = createWorkspace();
        DependencyResolver resolver = new DependencyResolver();

        Path sysroot =
                resolver.createClosure(workspaceHelper, workspace, "app", "alternate@2.0/runtime");

        assertTrue(Files.exists(sysroot.resolve("include/alternate-runtime.h")));
        assertFalse(Files.exists(sysroot.resolve("include/public-api.h")));
        assertTrue(sysroot.startsWith(workspace.resolve(".wstool/sysroots/alternate@2.0")));
    }

    private Path createWorkspace() throws Exception {
        String distributionRepositoryName = "distribution-" + repositorySuffix;
        URI distributionRepository =
                uploadRepository("dependency-resolver/distribution", distributionRepositoryName);
        for (String packageName : CLONED_PACKAGE_NAMES) {
            uploadRepository(
                    "dependency-resolver/" + packageName, packageName + "-" + repositorySuffix);
        }

        Path parent = tempDir.resolve("workspaces");
        Path workspace = parent.resolve("dependency-resolver");
        workspaceHelper.createWorkspace(
                ImmutableCreateWorkspaceInput.builder()
                        .parent(parent)
                        .name("dependency-resolver")
                        .distribution(
                                ImmutableGitRepositoryLocation.builder()
                                        .repository(distributionRepository)
                                        .branch("main")
                                        .build())
                        .build());

        for (String packageName : CLONED_PACKAGE_NAMES) {
            workspaceHelper.addPackageToWorkspace(
                    ImmutableAddPackageToWorkspaceInput.builder()
                            .workspace(workspace)
                            .name(packageName)
                            .version(packageVersion(packageName))
                            .build());
        }

        for (String packageName : PACKAGE_NAMES) {
            String version = packageVersion(packageName);
            Path artifact = createArtifact(packageName, version);
            String artifactKey = artifactKey(packageName, version);
            cache.uploadFolder(BUCKET, artifactKey, artifact);

            Path cachedPackage =
                    workspaceHelper.workspaceCacheFolder(workspace, packageName).resolve(version);
            Files.createDirectories(cachedPackage.getParent());
            cache.downloadFolder(BUCKET, artifactKey, cachedPackage);

            boolean cloned = CLONED_PACKAGE_NAMES.contains(packageName);
            Path source =
                    cloned
                            ? workspaceHelper.workspacePackageFolder(workspace, packageName)
                            : cachedPackage.resolve("source");
            Path prefix = cachedPackage.resolve("prefix");
            storage.addPackageToWorkspaceStorage(
                    workspace.resolve("wstool.db"),
                    packageName,
                    version,
                    source.toString(),
                    prefix.toString(),
                    workspaceHelper.workspaceBuildFolder(workspace, packageName).toString(),
                    cloned);
        }
        return workspace;
    }

    private Path createArtifact(String packageName, String version) throws Exception {
        Path artifact = tempDir.resolve("artifacts").resolve(repositorySuffix).resolve(packageName);
        Path source = artifact.resolve("source");
        Path prefix = artifact.resolve("prefix");
        Files.createDirectories(source);
        Files.copy(
                resourcePath("dependency-resolver/" + packageName + "/wstool.pkl"),
                source.resolve("wstool.pkl"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectories(prefix.resolve("include"));
        Files.copy(
                resourcePath("dependency-resolver/artifacts/include/package.h"),
                prefix.resolve("include").resolve(packageName + ".h"),
                StandardCopyOption.REPLACE_EXISTING);
        if (packageName.equals("compiler")) {
            Files.createDirectories(prefix.resolve("bin"));
            Files.copy(
                    resourcePath("dependency-resolver/artifacts/bin/compiler"),
                    prefix.resolve("bin/compiler"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        if (packageName.equals("shared")) {
            Files.createDirectories(prefix.resolve("lib"));
            Files.copy(
                    resourcePath("dependency-resolver/shared/libshared-real.so"),
                    prefix.resolve("lib/libshared-real.so"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.createSymbolicLink(
                    prefix.resolve("lib/libshared.so"), Path.of("libshared-real.so"));
        }
        return artifact;
    }

    private String packageVersion(String packageName) {
        return "alternate".equals(packageName) ? "2.0" : "1.0";
    }

    private String artifactKey(String packageName, String version) {
        return "dependency-resolver/" + repositorySuffix + "/" + packageName + "/" + version;
    }

    private URI uploadRepository(String fixture, String repositoryName) throws Exception {
        giteaApi.createCurrentUserRepo(new CreateRepoOption().name(repositoryName)._private(false));

        Path uploadDirectory = tempDir.resolve("uploads").resolve(repositoryName);
        copyDirectory(resourcePath(fixture), uploadDirectory);
        runGit(uploadDirectory, "init", "--initial-branch=main");
        runGit(uploadDirectory, "config", "user.name", gitServer.getUserName());
        runGit(uploadDirectory, "config", "user.email", "dependency-resolver@example.test");
        runGit(uploadDirectory, "add", "--all");
        runGit(uploadDirectory, "commit", "--message", "Add " + repositoryName + " fixture");
        runGit(uploadDirectory, "remote", "add", "origin", gitRepoUri(repositoryName).toString());
        runGit(uploadDirectory, "push", "--set-upstream", "origin", "main");
        return gitRepoUri(repositoryName);
    }

    private String gitHost() {
        return gitServer.getHost() + ":" + gitServer.getMappedPort(3000);
    }

    private URI gitRepoUri(String repository) {
        return URI.create(
                "http://" + gitHost() + "/" + gitServer.getUserName() + "/" + repository + ".git");
    }

    private static Path resourcePath(String name) throws Exception {
        URL resource = DependencyResolverTest.class.getClassLoader().getResource(name);
        if (resource == null) {
            throw new IOException("Test resource not found: " + name);
        }
        return Path.of(resource.toURI());
    }

    private static void copyDirectory(Path source, Path destination) throws IOException {
        try (Stream<Path> entries = Files.walk(source)) {
            for (Path entry : entries.toList()) {
                Path target = destination.resolve(source.relativize(entry));
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void runGit(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        new GitHelper(false)
                .run(
                        ImmutableGitRunInput.builder()
                                .directory(directory)
                                .arguments(command)
                                .interactive(false)
                                .username(gitServer.getUserName())
                                .password(gitServer.getUserPassword())
                                .build());
    }

    private static String runGarage(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("/garage");
        command.add("-c");
        command.add("/etc/garage.toml");
        command.addAll(List.of(arguments));
        var result = garage.execInContainer(command.toArray(String[]::new));
        if (result.getExitCode() != 0) {
            throw new IOException(
                    "Garage command failed: " + result.getStdout() + result.getStderr());
        }
        return result.getStdout();
    }
}
