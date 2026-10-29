package wstool.cli.commands.packages;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import wstool.cli.commands.CliHelper;
import wstool.common.path.DownloadManager;
import wstool.common.path.DownloadRequest;
import wstool.test.components.CliResult;
import wstool.test.components.LocalFileHttpServer;
import wstool.test.components.PackageGitManager;

@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class GmpMpfrBuildIntegrationTest {
    private static final String GMP_SHA256 =
            "a3c2b80201b89e68616f4ad30bc66aee4927c3ce50e33929ca819d5c43538898";
    private static final String MPFR_SHA256 =
            "277807353a6726978996945af13e52829e3abd7a9a5b7fb2793894e18f1fcbb2";
    private static final List<String> UBUNTU_BUILD_PACKAGES =
            List.of(
                    "binutils-common",
                    "binutils-x86-64-linux-gnu",
                    "gcc-15-base",
                    "cpp-15-x86-64-linux-gnu",
                    "gcc-15-x86-64-linux-gnu",
                    "libgcc-15-dev",
                    "libc6-dev",
                    "linux-libc-dev",
                    "libc-dev-bin",
                    "rpcsvc-proto",
                    "libgcc-s1",
                    "libisl23",
                    "libmpc3",
                    "libmpfr6",
                    "libbinutils",
                    "libctf0",
                    "libctf-nobfd0",
                    "libsframe3",
                    "libjansson4",
                    "libgprofng0",
                    "libcc1-0",
                    "make",
                    "m4",
                    "libc6");

    @Container private static final GiteaContainer GITEA = PackageGitManager.container();

    private Path tmp;
    private PackageGitManager git;
    private CliHelper cli;
    private BuildToolchainTestSupport toolchain;
    private LocalFileHttpServer sourceServer;
    private Map<String, String> environment;

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        tmp =
                Path.of(
                                "build",
                                "gmp-mpfr-build",
                                testInfo.getTestMethod().orElseThrow().getName())
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
        environment = new LinkedHashMap<>(toolchain.environment());

        DownloadManager downloads =
                new DownloadManager(
                        HttpClient.newBuilder()
                                .followRedirects(HttpClient.Redirect.NORMAL)
                                .build());
        Path cache =
                Path.of(System.getProperty("user.home"), ".cache", "wstool", "gmp-mpfr-test");
        Map<String, Path> files = new LinkedHashMap<>();
        Path gmp =
                downloads.get(
                        DownloadRequest.builder(cache.resolve("sources"), URI.create("https://ftp.gnu.org/gnu/gmp/gmp-6.3.0.tar.xz"))
                                .cacheName("gmp-6.3.0.tar.xz")
                                .checksum(GMP_SHA256)
                                .build());
        Path mpfr =
                downloads.get(
                        DownloadRequest.builder(cache.resolve("sources"), URI.create("https://ftp.gnu.org/gnu/mpfr/mpfr-4.2.1.tar.xz"))
                                .cacheName("mpfr-4.2.1.tar.xz")
                                .checksum(MPFR_SHA256)
                                .build());
        files.put("/sources/gmp-6.3.0.tar.xz", gmp);
        files.put("/sources/mpfr-4.2.1.tar.xz", mpfr);

        UbuntuDebCache ubuntu = new UbuntuDebCache(downloads);
        for (String packageName : UBUNTU_BUILD_PACKAGES) {
            UbuntuDebFile artifact = ubuntu.getDeb(packageName);
            files.put("/debs/" + packageName + ".deb", artifact.file());
            environment.put(
                    "WSTOOL_DEB_SHA256_"
                            + packageName.toUpperCase().replaceAll("[^A-Z0-9]", "_"),
                    artifact.checksum());
        }
        sourceServer = new LocalFileHttpServer(files);
        environment.put("WSTOOL_GMP_MPFR_SOURCE_URL", sourceServer.baseUri().toString());
        environment.put("WSTOOL_GMP_MPFR_DEBS_URL", sourceServer.baseUri().toString());

        makeBuildScriptsExecutable(
                CliHelper.resourceDirectory("/package-run/gmp-mpfr/packages").toAbsolutePath());

        URI buildToolsRepository =
                createRepository(
                        "gmp-mpfr-build-tools",
                        CliHelper.resourceDirectory(
                                "/package-run/gmp-mpfr/packages/ubuntu-build-tools"));
        URI ubuntuDebsRepository =
                createRepository(
                        "gmp-mpfr-ubuntu-debs",
                        CliHelper.resourceDirectory("/package-run/gmp-mpfr/packages/ubuntu-debs"));
        URI ubuntuDebInstallerRepository =
                createRepository(
                        "gmp-mpfr-deb-installer",
                        CliHelper.resourceDirectory(
                                "/package-run/gmp-mpfr/packages/ubuntu-deb-installer"));
        URI gmpRepository =
                createRepository(
                        "gmp", CliHelper.resourceDirectory("/package-run/gmp-mpfr/packages/gmp"));
        URI mpfrRepository =
                createRepository(
                        "mpfr", CliHelper.resourceDirectory("/package-run/gmp-mpfr/packages/mpfr"));
        environment.put("GMP_MPFR_BUILD_TOOLS_REPOSITORY", buildToolsRepository.toString());
        environment.put("GMP_MPFR_UBUNTU_DEBS_REPOSITORY", ubuntuDebsRepository.toString());
        environment.put(
                "GMP_MPFR_DEB_INSTALLER_REPOSITORY", ubuntuDebInstallerRepository.toString());
        environment.put("GMP_REPOSITORY", gmpRepository.toString());
        environment.put("MPFR_REPOSITORY", mpfrRepository.toString());
    }

    @AfterEach
    void tearDown() {
        if (sourceServer != null) {
            sourceServer.close();
        }
        if (toolchain != null) {
            toolchain.close();
        }
    }

    @Test
    void buildsGmpAndMpfrInDependencyOrderInUbuntuContainers() throws Exception {
        URI distributionRepository =
                createRepository(
                        "gmp-mpfr-distribution",
                        CliHelper.resourceDirectory("/package-run/gmp-mpfr/distribution"));
        CliResult create =
                cli.runCli(
                        tmp,
                        environment,
                        "workspace",
                        "create",
                        "--name",
                        "gmp-mpfr",
                        "--distribution",
                        distributionRepository.toString());
        CliHelper.assertSuccess(create);
        Path workspace = tmp.resolve("gmp-mpfr");
        for (String packageName : List.of("ubuntu-deb-" + UBUNTU_BUILD_PACKAGES.getFirst())) {
            CliHelper.assertSuccess(
                    cli.runCli(workspace, environment, "workspace", "add", "--package", packageName));
        }
        for (String packageName :
                List.of("ubuntu-deb-installer", "ubuntu-build-tools", "gmp", "mpfr")) {
            CliHelper.assertSuccess(
                    cli.runCli(workspace, environment, "workspace", "add", "--package", packageName));
        }
        toolchain.prepare(workspace, cli, environment);

        CliHelper.assertSuccess(
                cli.runCli(
                        workspace.resolve("src/ubuntu-deb-installer"),
                        environment,
                        "package",
                        "run"));

        for (String packageName : UBUNTU_BUILD_PACKAGES) {
            CliHelper.assertSuccess(
                    cli.runCli(
                            workspace.resolve("src/gmp-mpfr-ubuntu-debs/packages")
                                    .resolve(packageName),
                            environment,
                            "package",
                            "run"));
        }

        CliHelper.assertSuccess(
                cli.runCli(
                        workspace.resolve("src/ubuntu-build-tools"),
                        environment,
                        "package",
                        "build"));
        CliHelper.assertSuccess(
                cli.runCli(workspace.resolve("src/gmp"), environment, "package", "build"));
        CliHelper.assertSuccess(
                cli.runCli(
                        workspace.resolve("src/mpfr"),
                        environment,
                        "package",
                        "build",
                        "--memory",
                        "1G",
                        "--disk-size",
                        "2G",
                        "--tmpfs-size",
                        "512m"));
        Path prefix = workspace.resolve("private/prefix");
        assertTrue(Files.isRegularFile(prefix.resolve("gmp/6.3.0/lib/libgmp.so")));
        assertTrue(Files.isRegularFile(prefix.resolve("mpfr/4.2.1/lib/libmpfr.so")));
        assertTrue(Files.isRegularFile(prefix.resolve("mpfr/4.2.1/include/mpfr.h")));
    }

    private URI createRepository(String name, Path contents) throws Exception {
        String repository = name + "-" + UUID.randomUUID().toString().replace("-", "");
        URI uri = git.create(repository);
        git.uploadFiles(repository, contents);
        return uri;
    }

    private void makeBuildScriptsExecutable(Path directory) throws Exception {
        try (var entries = Files.walk(directory)) {
            for (Path entry : entries.filter(Files::isRegularFile).toList()) {
                if ((entry.getParent().endsWith("wstool/bin")
                                || entry.getParent().endsWith("configuration/bin"))
                        && !entry.toFile().setExecutable(true, false)) {
                    throw new IllegalStateException(
                            "Could not make build script executable: " + entry);
                }
            }
        }
    }
}
