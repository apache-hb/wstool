package wstool.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import dev.parsick.testcontainers.gitserver.gitea.GiteaVersions;
import io.gitea.ApiClient;
import io.gitea.api.RepositoryApi;
import io.gitea.model.CreateRepoOption;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
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
import wstool.simple.ArchiveHelper;
import wstool.simple.CacheHelper;
import wstool.simple.GitHelper;
import wstool.simple.StorageHelper;
import wstool.simple.git.ImmutableGitRunInput;

/** Exercises real source builds, workspace package resolution, S3 artifacts, and target runs. */
@Testcontainers(disabledWithoutDocker = true)
class WorkspaceCommandsEndToEndTest {
    private static final String ACCESS_KEY = "GK0123456789abcdef01234567";
    private static final String SECRET_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String BUCKET = "cli-toolchain";
    private static final List<String> PACKAGE_NAMES =
            List.of(
                    "cmake",
                    "ninja",
                    "zlib",
                    "expat",
                    "m4",
                    "bison",
                    "flex",
                    "binutils",
                    "gmp",
                    "mpfr",
                    "mpc",
                    "isl",
                    "gcc");
    private static final List<String> BUILD_ORDER =
            List.of(
                    "zlib",
                    "expat",
                    "cmake",
                    "ninja",
                    "m4",
                    "bison",
                    "flex",
                    "binutils",
                    "gmp",
                    "mpfr",
                    "mpc",
                    "isl",
                    "gcc");
    private static final Set<String> CACHED_PACKAGES =
            Set.of("zlib", "expat", "gmp", "mpfr", "mpc", "isl");
    private static final Map<String, String> PACKAGE_VERSIONS =
            Map.ofEntries(
                    Map.entry("cmake", "3.30.5"),
                    Map.entry("ninja", "1.12.1"),
                    Map.entry("zlib", "1.3.1"),
                    Map.entry("expat", "2.6.4"),
                    Map.entry("m4", "1.4.19"),
                    Map.entry("bison", "3.8.2"),
                    Map.entry("flex", "2.6.4"),
                    Map.entry("binutils", "2.43.1"),
                    Map.entry("gmp", "6.3.0"),
                    Map.entry("mpfr", "4.2.1"),
                    Map.entry("mpc", "1.3.1"),
                    Map.entry("isl", "0.26"),
                    Map.entry("gcc", "14.2.0"));

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
                            MountableFile.forClasspathResource("garage.toml"), "/etc/garage.toml")
                    .withCommand("/garage", "-c", "/etc/garage.toml", "server")
                    .waitingFor(Wait.forLogMessage(".*S3 API server listening.*\\n", 1))
                    .withStartupTimeout(Duration.ofMinutes(2));

    @TempDir Path tempDir;

    private RepositoryApi giteaApi;
    @AutoClose private S3Client s3;
    private CacheHelper cache;
    private String suffix;
    private Map<String, String> repositoryEnvironment = Map.of();

    @BeforeAll
    static void initializeGarage() throws Exception {
        String nodeId = runGarage("node", "id").lines().findFirst().orElseThrow().split("@", 2)[0];
        runGarage("layout", "assign", "-z", "test-zone", "-c", "1G", nodeId);
        runGarage("layout", "apply", "--version", "1");
        runGarage("key", "import", "--yes", "-n", "cli-toolchain", ACCESS_KEY, SECRET_KEY);
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
        suffix = UUID.randomUUID().toString().replace("-", "");
        s3 =
                S3Client.builder()
                        .endpointOverride(
                                URI.create(
                                        "http://"
                                                + garage.getHost()
                                                + ":"
                                                + garage.getMappedPort(3900)))
                        .region(Region.US_EAST_1)
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
    void buildsRealBootstrapDependenciesAndGccThroughWorkspaceCommands() throws Exception {
        Map<String, URI> repositories = uploadPackageRepositories();
        URI distribution = uploadDistribution(repositories);

        Path parent = tempDir.resolve("workspaces");
        Files.createDirectories(parent);
        runCli(parent, "workspace", "create", "--name", "toolchain");
        Path workspace = parent.resolve("toolchain");
        runCli(
                workspace,
                "workspace",
                "setup",
                "--distro",
                "bootstrap-test",
                "--from",
                distribution.toString(),
                "--branch",
                "main");
        List<String> addArguments = new ArrayList<>(List.of("workspace", "add"));
        for (String name : PACKAGE_NAMES) {
            addArguments.add("--package");
            addArguments.add(name);
        }
        runCli(workspace, addArguments.toArray(String[]::new));

        Properties sources = sourceProperties();
        for (String name : PACKAGE_NAMES) {
            downloadAndExtractSource(workspace.resolve("src").resolve(name), name, sources);
        }

        for (String name : BUILD_ORDER) {
            Path packageDirectory = workspace.resolve("src").resolve(name);
            runCli(packageDirectory, "package", "release");
            if (CACHED_PACKAGES.contains(name)) {
                cacheBuiltPackage(workspace, name);
            }
        }

        assertTrue(Files.isExecutable(workspace.resolve("private/prefix/cmake/bin/cmake")));
        assertTrue(Files.isExecutable(workspace.resolve("private/prefix/ninja/bin/ninja")));
        Path cmakeSysroot = workspace.resolve("private/build/cmake/sysroot");
        assertTrue(Files.isSymbolicLink(cmakeSysroot.resolve("include/zlib.h")));
        assertTrue(Files.isSymbolicLink(cmakeSysroot.resolve("include/expat.h")));

        Path gccPrefix = workspace.resolve("private/prefix/gcc");
        Path compiler = gccPrefix.resolve("bin/x86_64-linux-gnu-gcc");
        assertTrue(Files.isExecutable(compiler), "GCC source build did not install its compiler");
        Process version =
                new ProcessBuilder(compiler.toString(), "--version")
                        .redirectErrorStream(true)
                        .start();
        String versionOutput =
                new String(version.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, version.waitFor(), versionOutput);
        assertTrue(versionOutput.contains("14.2.0"), versionOutput);

        Path source = resourcePath("workspace-e2e/program.c");
        Path object = tempDir.resolve("program.o");
        Process compile =
                new ProcessBuilder(
                                compiler.toString(),
                                "-c",
                                source.toString(),
                                "-o",
                                object.toString())
                        .redirectErrorStream(true)
                        .start();
        String compileOutput =
                new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, compile.waitFor(), compileOutput);
        assertTrue(Files.size(object) > 0, "GCC produced an empty object file");

        Path gccSysroot = workspace.resolve("private/build/gcc/sysroot");
        assertTrue(Files.isSymbolicLink(gccSysroot.resolve("include/gmp.h")));
        assertTrue(Files.isSymbolicLink(gccSysroot.resolve("include/mpfr.h")));
        assertTrue(Files.isSymbolicLink(gccSysroot.resolve("include/mpc.h")));
        assertTrue(Files.isDirectory(gccSysroot.resolve("include/isl")));
        assertTrue(Files.isSymbolicLink(gccSysroot.resolve("include/isl/ctx.h")));
        assertTrue(Files.isSymbolicLink(gccSysroot.resolve("lib/libisl.a")));
        assertTrue(Files.isSymbolicLink(gccSysroot.resolve("bin/x86_64-linux-gnu-ld")));
        assertTrue(Files.isSymbolicLink(gccSysroot.resolve("bin/m4")));
        assertTrue(Files.isSymbolicLink(gccSysroot.resolve("bin/bison")));
        assertTrue(Files.isSymbolicLink(gccSysroot.resolve("bin/flex")));
    }

    private Map<String, URI> uploadPackageRepositories() throws Exception {
        Map<String, URI> repositories = new java.util.LinkedHashMap<>();
        for (String name : PACKAGE_NAMES) {
            repositories.put(name, uploadPackageRepository(name));
        }
        return repositories;
    }

    private URI uploadDistribution(Map<String, URI> packages) throws Exception {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String name : PACKAGE_NAMES) {
            environment.put(name.toUpperCase(Locale.ROOT) + "_REPO", packages.get(name).toString());
        }
        repositoryEnvironment = Map.copyOf(environment);
        return uploadRepositoryFromResources(
                "distribution-" + suffix, resourcePath("workspace-e2e/distribution"));
    }

    private URI uploadPackageRepository(String name) throws Exception {
        Path resources = resourcePath("workspace-e2e/packages/" + name);
        return uploadRepositoryFromResources(name + "-" + suffix, resources);
    }

    private URI uploadRepositoryFromResources(String repositoryName, Path resources)
            throws Exception {
        giteaApi.createCurrentUserRepo(new CreateRepoOption().name(repositoryName)._private(false));
        Path repository = tempDir.resolve("git").resolve(repositoryName);
        copyTree(resources, repository);
        URI uri = gitRepoUri(repositoryName);
        pushRepository(repository, repositoryName, uri);
        return uri;
    }

    private void pushRepository(Path repository, String name, URI uri) throws Exception {
        makeScriptsExecutable(repository);
        runGit(repository, "init", "--initial-branch=main");
        runGit(repository, "config", "user.name", gitServer.getUserName());
        runGit(repository, "config", "user.email", "cli-e2e@example.test");
        runGit(repository, "add", "--all");
        runGit(repository, "commit", "--message", "Add " + name);
        runGit(repository, "remote", "add", "origin", uri.toString());
        runGit(repository, "push", "--set-upstream", "origin", "main");
    }

    private void cacheBuiltPackage(Path workspace, String name) throws Exception {
        String version = PACKAGE_VERSIONS.get(name);
        Path packageDirectory = workspace.resolve("src").resolve(name);
        Path artifact = tempDir.resolve("artifacts").resolve(suffix).resolve(name);
        Path artifactSource = artifact.resolve("source");
        Files.createDirectories(artifactSource);
        Files.copy(packageDirectory.resolve("wstool.pkl"), artifactSource.resolve("wstool.pkl"));
        copyTree(workspace.resolve("private/prefix").resolve(name), artifact.resolve("prefix"));

        String key = "cli-e2e/" + suffix + "/" + name + "/" + version;
        cache.uploadFolder(BUCKET, key, artifact);
        Path cached = workspace.resolve("private/cache").resolve(name).resolve(version);
        Files.createDirectories(cached.getParent());
        cache.downloadFolder(BUCKET, key, cached);
        new StorageHelper()
                .addPackageToWorkspaceStorage(
                        workspace.resolve("wstool.db"),
                        name,
                        version,
                        cached.resolve("source").toString(),
                        cached.resolve("prefix").toString(),
                        workspace.resolve("private/build").resolve(name).toString(),
                        false);
    }

    private void downloadAndExtractSource(Path packageDirectory, String name, Properties sources)
            throws Exception {
        Path archive = getOrDownloadSourceArchive(name, sources);

        Path extracted = packageDirectory.resolve("source");
        Files.createDirectories(extracted);
        Process tar =
                new ProcessBuilder(
                                "tar",
                                "-xf",
                                archive.toString(),
                                "--strip-components=1",
                                "-C",
                                extracted.toString())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(tar.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, tar.waitFor(), "Could not extract " + name + " source archive: " + output);
    }

    private Path getOrDownloadSourceArchive(String name, Properties sources) throws Exception {
        String expectedHash = sources.getProperty(name + ".sha256");
        Path cacheDirectory =
                Path.of(System.getProperty("user.home"), ".gradle", "caches", "wstool")
                        .resolve("workspace-e2e/sources");
        Files.createDirectories(cacheDirectory);
        Path cachedArchive = cacheDirectory.resolve(name + "-" + expectedHash + ".archive");
        if (Files.isRegularFile(cachedArchive) && expectedHash.equals(sha256(cachedArchive))) {
            return cachedArchive;
        }

        Files.deleteIfExists(cachedArchive);
        Path downloadedArchive = Files.createTempFile(cacheDirectory, name + "-", ".part");
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(sources.getProperty(name + ".url")))
                            .GET()
                            .build();
            HttpResponse<Path> response =
                    HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build()
                            .send(request, HttpResponse.BodyHandlers.ofFile(downloadedArchive));
            assertEquals(
                    200,
                    response.statusCode(),
                    "Could not download upstream source archive for " + name);
            assertEquals(
                    expectedHash,
                    sha256(downloadedArchive),
                    "Upstream source checksum mismatch for " + name);
            try {
                Files.move(
                        downloadedArchive,
                        cachedArchive,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(downloadedArchive, cachedArchive, StandardCopyOption.REPLACE_EXISTING);
            }
            return cachedArchive;
        } finally {
            Files.deleteIfExists(downloadedArchive);
        }
    }

    private String sha256(Path archive) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(archive)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Properties sourceProperties() throws IOException {
        Properties properties = new Properties();
        try (var input =
                getClass()
                        .getClassLoader()
                        .getResourceAsStream("workspace-e2e/sources/sources.properties")) {
            if (input == null) {
                throw new IOException("Test source metadata is missing");
            }
            properties.load(input);
        }
        return properties;
    }

    private void copyTree(Path source, Path destination) throws IOException {
        try (var entries = Files.walk(source)) {
            for (Path entry : entries.toList()) {
                Path target = destination.resolve(source.relativize(entry));
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(entry, target);
                }
            }
        }
    }

    private void makeScriptsExecutable(Path repository) throws IOException {
        try (var entries = Files.walk(repository)) {
            for (Path entry :
                    entries.filter(path -> path.toString().contains("/wstool/bin/")).toList()) {
                Files.setPosixFilePermissions(
                        entry,
                        Set.of(
                                PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.OWNER_EXECUTE,
                                PosixFilePermission.GROUP_READ,
                                PosixFilePermission.GROUP_EXECUTE,
                                PosixFilePermission.OTHERS_READ,
                                PosixFilePermission.OTHERS_EXECUTE));
            }
        }
    }

    private Path resourcePath(String name) throws Exception {
        URL resource = getClass().getClassLoader().getResource(name);
        if (resource == null) {
            throw new IOException("Test resource not found: " + name);
        }
        return Path.of(resource.toURI());
    }

    private URI gitRepoUri(String repository) {
        return URI.create(
                "http://"
                        + gitServer.getHost()
                        + ":"
                        + gitServer.getMappedPort(3000)
                        + "/"
                        + gitServer.getUserName()
                        + "/"
                        + repository
                        + ".git");
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

    private void runCli(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Main.class.getName());
        command.addAll(List.of(arguments));
        ProcessBuilder processBuilder =
                new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        processBuilder.environment().putAll(repositoryEnvironment);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertEquals(0, exit, "CLI failed: " + String.join(" ", arguments) + "\n" + output);
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
