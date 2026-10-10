package wstool.simple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import wstool.Distribution;
import wstool.Distribution.Archive;
import wstool.Package;
import wstool.simple.container.RunContainerBuildInput;
import wstool.simple.model.PackageMajorVersion;

/** Runs one package build in Docker and publishes its installed prefix to object storage. */
public class ContainerBuildHelper {
    private final S3ArtifactCache artifacts;
    private final WorkspaceHelper workspace;
    private final DependencyResolver dependencies;
    private final PklHelper pkl;
    private final Distribution distribution;
    private final GitHelper git;
    private final StorageHelper storage;

    public ContainerBuildHelper(
            S3ArtifactCache artifacts,
            WorkspaceHelper workspace,
            DependencyResolver dependencies,
            PklHelper pkl,
            Distribution distribution,
            GitHelper git,
            StorageHelper storage) {
        this.artifacts = artifacts;
        this.workspace = workspace;
        this.dependencies = dependencies;
        this.pkl = pkl;
        this.distribution = distribution;
        this.git = git;
        this.storage = storage;
    }

    public void runContainerBuild(RunContainerBuildInput input) {
        Path root = input.workspace().toAbsolutePath().normalize();
        PackageMajorVersion version = input.name();
        LocalPackage target =
                workspace.sourcePackages(root).stream()
                        .filter(pkg -> pkg.version().equals(version))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Package is not in the workspace: " + version));
        Path source = target.source().toAbsolutePath().normalize();
        Path packageRoot = source;
        Package config = new PackageHelper(pkl).getPackageConfig(packageRoot.resolve("wstool.pkl"));
        Path output = target.prefix().toAbsolutePath().normalize();
        Path privateDirectory =
                workspace.workspaceBuildFolder(root, version.name()).toAbsolutePath().normalize();
        Path containerWorkspace = privateDirectory.resolve("container-workspace");

        try {
            List<LocalPackage> buildPackages =
                    List.copyOf(
                            dependencies
                                    .resolvePackages(
                                            workspace,
                                            root,
                                            version.name() + "@" + version.version(),
                                            "build",
                                            distribution,
                                            git)
                                    .values());
            for (LocalPackage dependency : buildPackages) {
                if (!isArchivePackage(dependency.version())) {
                    artifacts.download(dependency.version(), dependency.prefix());
                }
            }
            deleteTree(output);
            Files.createDirectories(output);
            Files.createDirectories(privateDirectory);
            prepareContainerWorkspace(containerWorkspace, target, buildPackages);

            String tool = config.getBuildOptions().getTool();
            Path localTool = packageRoot.resolve("wstool/bin").resolve(tool);
            if (!Files.isExecutable(localTool)) {
                localTool =
                        buildPackages.stream()
                                .map(dependency -> dependency.prefix().resolve("bin").resolve(tool))
                                .filter(Files::isExecutable)
                                .findFirst()
                                .orElse(localTool);
            }
            if (!Files.isExecutable(localTool)) {
                throw new IllegalStateException(
                        "Build tool is missing from the package source and build dependencies: "
                                + tool);
            }

            List<String> command = new ArrayList<>(List.of("docker", "run", "--rm"));
            String isolation = config.getBuildOptions().getNetworkIsolation().toString();
            if ("isolated".equals(isolation)) {
                command.addAll(List.of("--network", "none"));
            } else if (!"open".equals(isolation)) {
                throw new IllegalArgumentException(
                        "Unknown build network isolation mode: " + isolation);
            }
            addBuildUser(command, source);
            String sourceMount =
                    "readwrite".equals(config.getBuildOptions().getSourceMount().toString())
                            ? "rw"
                            : "ro";
            command.addAll(
                    List.of(
                            "--volume",
                            containerWorkspace + ":/wstool/workspace:ro",
                            "--volume",
                            source
                                    + ":/wstool/workspace/src/"
                                    + version.name()
                                    + ":"
                                    + sourceMount));
            command.addAll(List.of("--volume", localTool + ":/wstool/tools/" + tool + ":ro"));
            Path pklCache = Path.of(System.getProperty("user.home"), ".pkl", "cache");
            if (Files.isDirectory(pklCache)) {
                command.addAll(
                        List.of("--volume", pklCache + ":/tmp/wstool-cli-home/.pkl/cache:ro"));
            }
            for (LocalPackage dependency : buildPackages) {
                command.addAll(
                        List.of(
                                "--volume",
                                dependency.prefix()
                                        + ":/wstool/workspace/private/prefix/"
                                        + dependency.version().name()
                                        + ":ro",
                                "--volume",
                                dependency.source()
                                        + ":/wstool/workspace/private/cache/package-sources/"
                                        + dependency.version().name()
                                        + "/"
                                        + dependency.version().version()
                                        + ":ro"));
            }
            command.addAll(
                    List.of(
                            "--volume",
                            output
                                    + ":/wstool/workspace/private/prefix/"
                                    + version.name()
                                    + ":rw"));
            command.addAll(List.of("--workdir", "/wstool/workspace/src/" + version.name()));
            command.addAll(List.of("--env", "WST_SRCDIR=/wstool/workspace/src/" + version.name()));
            command.addAll(List.of("--env", "WST_PRIVATE=/tmp/wstool-private"));
            command.addAll(
                    List.of(
                            "--env",
                            "WST_BUILD_IMAGE=" + config.getBuildOptions().getDockerImage()));
            command.addAll(
                    List.of(
                            "--env",
                            "WST_PREFIX=/wstool/workspace/private/prefix/" + version.name()));
            command.addAll(List.of("--env", "HOME=/tmp/wstool-cli-home"));
            command.addAll(List.of("--env", "JAVA_OPTS=-Duser.home=/tmp/wstool-cli-home"));
            List<String> dependencyBins =
                    new ArrayList<>(
                            buildPackages.stream()
                                    .map(
                                            dependency ->
                                                    "/wstool/workspace/private/prefix/"
                                                            + dependency.version().name()
                                                            + "/bin")
                                    .toList());
            dependencyBins.addAll(
                    List.of(
                            "/usr/local/sbin",
                            "/usr/local/bin",
                            "/usr/sbin",
                            "/usr/bin",
                            "/sbin",
                            "/bin"));
            String containerPath = String.join(":", dependencyBins);
            command.addAll(List.of("--env", "PATH=" + containerPath));
            Map<String, String> extraEnvironment = config.getBuildOptions().getBuildEnvironment();
            if (extraEnvironment != null) {
                extraEnvironment.forEach(
                        (name, value) -> command.addAll(List.of("--env", name + "=" + value)));
            }
            command.addAll(List.of("--entrypoint", "/wstool/tools/" + tool));
            command.add(config.getBuildOptions().getDockerImage());
            command.add("release");

            Process process =
                    new ProcessBuilder(command).directory(root.toFile()).inheritIO().start();
            int status = process.waitFor();
            if (status != 0) {
                throw new IllegalStateException(
                        "Container build failed with status " + status + " for " + version);
            }
            artifacts.upload(version, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while building " + version, e);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to run container build for " + version, e);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Unable to cache build artifacts for " + version, e);
        }
    }

    private boolean isArchivePackage(PackageMajorVersion version) {
        return distribution.getPackages().stream()
                .filter(pkg -> pkg.getName().equals(version.name()))
                .filter(pkg -> pkg.getVersion().equals(version.version()))
                .anyMatch(Archive.class::isInstance);
    }

    private void prepareContainerWorkspace(
            Path containerWorkspace, LocalPackage target, List<LocalPackage> buildPackages)
            throws Exception {
        deleteTree(containerWorkspace);
        Files.createDirectories(containerWorkspace.resolve("src").resolve(target.version().name()));
        Files.createDirectories(
                containerWorkspace.resolve("private/prefix").resolve(target.version().name()));
        Files.createDirectories(containerWorkspace.resolve("private/build"));
        Files.createDirectories(containerWorkspace.resolve("private/cache/package-sources"));

        Path database = containerWorkspace.resolve("wstool.db");
        storage.createWorkspaceStorage(database);
        storage.addPackageToWorkspaceStorage(
                database,
                target.version().name(),
                target.version().version(),
                "/wstool/workspace/src/" + target.version().name(),
                "/wstool/workspace/private/prefix/" + target.version().name(),
                "/wstool/workspace/private/build/" + target.version().name(),
                true);

        for (LocalPackage dependency : buildPackages) {
            String name = dependency.version().name();
            String version = dependency.version().version();
            Files.createDirectories(containerWorkspace.resolve("private/prefix").resolve(name));
            Files.createDirectories(
                    containerWorkspace
                            .resolve("private/cache/package-sources")
                            .resolve(name)
                            .resolve(version));
            storage.addPackageToWorkspaceStorage(
                    database,
                    name,
                    version,
                    "/wstool/workspace/private/cache/package-sources/" + name + "/" + version,
                    "/wstool/workspace/private/prefix/" + name,
                    "/wstool/workspace/private/build/" + name,
                    false);
        }
    }

    private void addBuildUser(List<String> command, Path source) {
        try {
            Object uid = Files.getAttribute(source, "unix:uid");
            Object gid = Files.getAttribute(source, "unix:gid");
            command.addAll(List.of("--user", uid + ":" + gid));
        } catch (IOException | UnsupportedOperationException ignored) {
            // On filesystems without Unix ownership, use Docker's default container user.
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
