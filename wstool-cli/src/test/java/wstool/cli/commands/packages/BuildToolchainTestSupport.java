package wstool.cli.commands.packages;

import static java.util.Map.entry;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.io.file.PathUtils;
import wstool.cli.commands.CliHelper;
import wstool.common.path.DownloadManager;
import wstool.test.components.LocalFileHttpServer;
import wstool.test.components.PackageGitManager;

final class BuildToolchainTestSupport implements AutoCloseable {
    private static final List<String> TOOLCHAIN_PACKAGES =
            List.of(
                    "ubuntu-libpopt0",
                    "ubuntu-rsync",
                    "wstool-copy-source",
                    "ubuntu-busybox-static",
                    "corretto",
                    "wstool");

    private final LocalFileHttpServer server;
    private final Map<String, String> environment;
    private final Path prefixCache;

    private BuildToolchainTestSupport(
            LocalFileHttpServer server, Map<String, String> environment, Path prefixCache) {
        this.server = server;
        this.environment = environment;
        this.prefixCache = prefixCache;
    }

    static BuildToolchainTestSupport create(Path temp, PackageGitManager git) throws Exception {
        HttpClient httpClient =
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        DownloadManager downloadManager = new DownloadManager(httpClient);
        Path sourceCache =
                Path.of(System.getProperty("user.home"), ".cache", "wstool", "package-sources");
        CachedCorretto corretto = new CorrettoTestPackageSource(downloadManager).get();
        Path wstoolArchive = Path.of(System.getProperty("wstool.distribution.archive"));
        String wstoolChecksum = DownloadManager.sha256(wstoolArchive);
        UbuntuDebCache ubuntu = new UbuntuDebCache(downloadManager);
        UbuntuDebArtifact rsync = ubuntu.get("rsync");
        UbuntuDebArtifact libpopt = ubuntu.get("libpopt0");
        UbuntuDebArtifact busybox = ubuntu.get("busybox-static");

        Map<String, Path> files = new LinkedHashMap<>();
        files.put("/corretto.tar.gz", corretto.archive());
        files.put("/wstool.tar", wstoolArchive);
        addUbuntuFiles(files, "rsync", rsync);
        addUbuntuFiles(files, "libpopt0", libpopt);
        addUbuntuFiles(files, "busybox-static", busybox);
        LocalFileHttpServer server = new LocalFileHttpServer(files);

        Path checkout = sourceCache.resolve("wstool-copy-source");
        if (!hasGitCheckout(checkout)) {
            Files.createDirectories(sourceCache);
            deleteDirectoryIfPresent(checkout);
            Process process =
                    new ProcessBuilder(
                                    "git",
                                    "clone",
                                    "--depth",
                                    "1",
                                    "https://github.com/apache-hb/wstool-copy-source.git",
                                    checkout.toString())
                            .redirectErrorStream(true)
                            .start();
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) {
                server.close();
                throw new IOException("Could not clone wstool-copy-source: " + output);
            }
        }

        Map<Path, Path> additionalFolders =
                Map.ofEntries(
                        entry(
                                Path.of("."),
                                CliHelper.resourceDirectory(
                                        "/package-run/toolchain/packages/wstool-copy-source")),
                        entry(
                                Path.of("packages/corretto"),
                                CliHelper.resourceDirectory(
                                        "/package-run/toolchain/packages/corretto")),
                        entry(
                                Path.of("packages/wstool"),
                                CliHelper.resourceDirectory(
                                        "/package-run/toolchain/packages/wstool")),
                        entry(
                                Path.of("packages/ubuntu-rsync"),
                                CliHelper.resourceDirectory(
                                        "/package-run/toolchain/packages/ubuntu-rsync")),
                        entry(
                                Path.of("packages/ubuntu-libpopt0"),
                                CliHelper.resourceDirectory(
                                        "/package-run/toolchain/packages/ubuntu-libpopt0")),
                        entry(
                                Path.of("packages/ubuntu-busybox-static"),
                                CliHelper.resourceDirectory(
                                        "/package-run/toolchain/packages/ubuntu-busybox-static")));
        for (Path executable :
                List.of(
                        CliHelper.resourceDirectory(
                                        "/package-run/toolchain/packages/corretto/wstool/bin")
                                .resolve("install-runtime"),
                        CliHelper.resourceDirectory(
                                        "/package-run/toolchain/packages/wstool/wstool/bin")
                                .resolve("install-runtime"),
                        CliHelper.resourceDirectory(
                                        "/package-run/toolchain/packages/ubuntu-rsync/wstool/bin")
                                .resolve("install-source"),
                        CliHelper.resourceDirectory(
                                        "/package-run/toolchain/packages/ubuntu-libpopt0/wstool/bin")
                                .resolve("install-source"))) {
            if (!executable.toFile().setExecutable(true, false)) {
                throw new IOException(
                        "Could not make package build tool executable: " + executable);
            }
        }
        String repositoryName =
                "wstool-build-toolchain-" + UUID.randomUUID().toString().replace("-", "");
        Path repositoryContents = temp.resolve("wstool-build-toolchain-source");
        copyCheckoutContents(checkout, repositoryContents);
        var toolchainUri = git.create(repositoryName, repositoryContents, additionalFolders);

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("WSTOOL_BUILD_TOOLS_REPOSITORY", toolchainUri.toString());
        environment.put("WSTOOL_PACKAGE_CACHE_URL", server.baseUri().toString());
        environment.put("WSTOOL_CORRETTO_SHA256", corretto.checksum());
        environment.put("WSTOOL_CLI_SHA256", wstoolChecksum);
        addChecksums(environment, "RSYNC", rsync);
        addChecksums(environment, "LIBPOPT", libpopt);
        addChecksums(environment, "BUSYBOX", busybox);
        Path prefixCache =
                Path.of(System.getProperty("user.home"), ".cache", "wstool", "build-toolchain")
                        .resolve(corretto.checksum())
                        .resolve(wstoolChecksum)
                        .resolve(rsync.dataChecksum());
        return new BuildToolchainTestSupport(server, Map.copyOf(environment), prefixCache);
    }

    Map<String, String> environment() {
        return environment;
    }

    void prepare(Path workspace, CliHelper cli, Map<String, String> testEnvironment)
            throws Exception {
        Map<String, String> environment = new LinkedHashMap<>(this.environment);
        environment.putAll(testEnvironment);
        for (String packageName : TOOLCHAIN_PACKAGES) {
            CliHelper.assertSuccess(
                    cli.runCli(
                            workspace, environment, "workspace", "add", "--package", packageName));
        }

        try {
            Files.readString(prefixCache.resolve("complete"));
            restorePrefixes(workspace);
            return;
        } catch (NoSuchFileException exception) {
            // Build and cache the toolchain packages when no complete prefix cache exists.
        }

        for (String packageName : TOOLCHAIN_PACKAGES) {
            Path source =
                    packageName.equals("wstool-copy-source")
                            ? workspace.resolve("src/wstool-build-toolchain")
                            : workspace
                                    .resolve("src/wstool-build-toolchain/packages")
                                    .resolve(
                                    packageName);
            CliHelper.assertSuccess(cli.runCli(source, environment, "package", "run"));
            cachePrefix(workspace, packageName);
        }
        Files.createDirectories(prefixCache);
        Files.writeString(prefixCache.resolve("complete"), "complete\n");
    }

    @Override
    public void close() {
        server.close();
    }

    private static void addUbuntuFiles(
            Map<String, Path> files, String packageName, UbuntuDebArtifact artifact) {
        files.put("/ubuntu/" + packageName + "/data.tar.gz", artifact.dataArchive());
        files.put("/ubuntu/" + packageName + "/control", artifact.controlFile());
    }

    private static void copyCheckoutContents(Path checkout, Path destination) throws IOException {
        Files.createDirectories(destination);
        try (var paths = Files.walk(checkout)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Path relative = checkout.relativize(path);
                if (relative.getNameCount() > 0 && relative.getName(0).toString().equals(".git")) {
                    continue;
                }
                Path target = destination.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(
                        path,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static void addChecksums(
            Map<String, String> environment, String prefix, UbuntuDebArtifact artifact) {
        environment.put("WSTOOL_" + prefix + "_DATA_SHA256", artifact.dataChecksum());
        environment.put("WSTOOL_" + prefix + "_CONTROL_SHA256", artifact.controlChecksum());
    }

    private void cachePrefix(Path workspace, String packageName) throws Exception {
        String version = packageVersion(packageName);
        Path source = workspace.resolve("private/prefix").resolve(packageName).resolve(version);
        Path destination = prefixCache.resolve(packageName).resolve(version);
        deleteDirectoryIfPresent(destination);
        linkTree(source, destination);
    }

    private void restorePrefixes(Path workspace) throws Exception {
        for (String packageName : TOOLCHAIN_PACKAGES) {
            Path source = prefixCache.resolve(packageName).resolve(packageVersion(packageName));
            Path destination =
                    workspace
                            .resolve("private/prefix")
                            .resolve(packageName)
                            .resolve(packageVersion(packageName));
            deleteDirectoryIfPresent(destination);
            linkTree(source, destination);
        }
    }

    private static String packageVersion(String packageName) {
        return switch (packageName) {
            case "corretto" -> "25";
            case "wstool-copy-source" -> "1.x";
            default -> "1.0.0";
        };
    }

    private static void linkTree(Path source, Path destination) throws Exception {
        Files.createDirectories(destination);
        Files.walkFileTree(
                source,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory, BasicFileAttributes attrs) throws IOException {
                        Files.createDirectories(destination.resolve(source.relativize(directory)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Path target = destination.resolve(source.relativize(file));
                        if (attrs.isSymbolicLink()) {
                            Files.createSymbolicLink(target, Files.readSymbolicLink(file));
                        } else {
                            try {
                                Files.createLink(target, file);
                            } catch (IOException | UnsupportedOperationException exception) {
                                Files.copy(
                                        file,
                                        target,
                                        StandardCopyOption.COPY_ATTRIBUTES,
                                        StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private static boolean hasGitCheckout(Path checkout) throws IOException {
        try {
            Files.readAttributes(checkout.resolve(".git"), BasicFileAttributes.class);
            return true;
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    private static void deleteDirectoryIfPresent(Path directory) throws IOException {
        try {
            PathUtils.deleteDirectory(directory);
        } catch (NoSuchFileException exception) {
            // Another process removed the cached directory before cleanup.
        }
    }
}
