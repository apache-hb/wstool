package wstool.common.path;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import wstool.common.packages.NetworkIsolation;
import wstool.common.packages.PackageConfig;
import wstool.common.packages.SourceMount;
import wstool.common.workspace.LocalWorkspace;

@Singleton
public class DockerBuildExecutor {
    private static final Logger LOGGER = LogManager.getLogger(DockerBuildExecutor.class);
    private static final Path CONTAINER_WORKSPACE = Path.of("/wstool/workspace");
    private static final Path CONTAINER_HOME = Path.of("/wstool/home");

    private final DockerClient dockerClient;

    @Inject
    public DockerBuildExecutor(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    public int run(
            LocalWorkspace workspace,
            PackageConfig pkg,
            Path source,
            List<String> args,
            DockerBuildLimits limits)
            throws Exception {
        String image = workspace.buildImage();
        Path workspacePath = workspace.path().toAbsolutePath().normalize();
        Path containerSource = CONTAINER_WORKSPACE.resolve(workspacePath.relativize(source));
        String containerId =
                createAndStartContainer(image, pkg, workspacePath, source, containerSource, limits);

        try {
            if (pkg.networkIsolation() == NetworkIsolation.ISOLATED) {
                dockerClient
                        .disconnectFromNetworkCmd()
                        .withContainerId(containerId)
                        .withNetworkId("bridge")
                        .exec();
            }

            List<String> buildCommand = List.of("wstool", "package", "run");
            List<String> command = new ArrayList<>(buildCommand);
            command.addAll(args);
            return execute(
                    containerId,
                    command,
                    containerSource.toString(),
                    containerEnvironment(workspacePath),
                    currentUserId() + ":" + currentGroupId());
        } finally {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        }
    }

    private String createAndStartContainer(
            String image,
            PackageConfig pkg,
            Path workspacePath,
            Path source,
            Path containerSource,
            DockerBuildLimits limits)
            throws Exception {
        ensureImage(image);

        Path privateDirectory = workspacePath.resolve("private");
        Path containerHome = privateDirectory.resolve("build-home");
        Files.createDirectories(containerHome);

        List<Bind> binds = new ArrayList<>();
        binds.add(bind(workspacePath, CONTAINER_WORKSPACE, AccessMode.rw));
        binds.add(bind(privateDirectory, CONTAINER_WORKSPACE.resolve("private"), AccessMode.rw));
        binds.add(bind(containerHome, CONTAINER_HOME, AccessMode.rw));
        binds.add(
                bind(
                        source,
                        containerSource,
                        pkg.sourceMount() == SourceMount.READONLY ? AccessMode.ro : AccessMode.rw));
        binds.add(
                bind(
                        source,
                        Path.of("/wstool/source"),
                        pkg.sourceMount() == SourceMount.READONLY ? AccessMode.ro : AccessMode.rw));
        binds.add(
                bind(
                        workspacePath
                                .resolve("private/prefix")
                                .resolve(pkg.name().name())
                                .resolve(pkg.name().version()),
                        Path.of("/wstool/prefix"),
                        AccessMode.rw));
        binds.add(
                bind(
                        workspacePath
                                .resolve("private/build")
                                .resolve(pkg.name().name())
                                .resolve(pkg.name().version()),
                        Path.of("/wstool/private"),
                        AccessMode.rw));
        binds.add(
                bind(
                        workspacePath.resolve("private/prefix"),
                        Path.of("/wstool/dependencies"),
                        AccessMode.rw));
        Path pklCache = Path.of(System.getProperty("user.home")).resolve(".pkl");
        if (isDirectory(pklCache)) {
            binds.add(bind(pklCache, CONTAINER_HOME.resolve(".pkl"), AccessMode.rw));
        }

        HostConfig hostConfig =
                HostConfig.newHostConfig()
                        .withNetworkMode("bridge")
                        .withBinds(binds)
                        .withMemory(limits.memoryBytes())
                        .withMemorySwap(limits.memoryBytes())
                        .withStorageOpt(Map.of("size", limits.diskSize()))
                        .withTmpFs(
                                Map.of(
                                        "/tmp",
                                        "rw,nosuid,nodev,exec,size=" + limits.tmpfsSize()))
                        .withExtraHosts("host.docker.internal:host-gateway");
        CreateContainerResponse response =
                dockerClient
                        .createContainerCmd(image)
                        .withHostConfig(hostConfig)
                        .withWorkingDir(containerSource.toString())
                        .withEnv(containerEnvironment(workspacePath))
                        .withCmd("sleep", "infinity")
                        .exec();
        dockerClient.startContainerCmd(response.getId()).exec();
        LOGGER.info(
                "Started build container {} for package {} using image {}",
                response.getId(),
                pkg.name(),
                image);
        return response.getId();
    }

    private void ensureImage(String image) throws Exception {
        try {
            dockerClient.inspectImageCmd(image).exec();
        } catch (NotFoundException e) {
            LOGGER.info("Pulling Docker build image {}", image);
            dockerClient.pullImageCmd(image).start().awaitCompletion();
        }
    }

    private int execute(
            String containerId,
            List<String> command,
            String workingDirectory,
            List<String> env,
            String user)
            throws Exception {
        var execCommand =
                dockerClient
                        .execCreateCmd(containerId)
                        .withAttachStdout(true)
                        .withAttachStderr(true)
                        .withCmd(command.toArray(String[]::new));
        if (workingDirectory != null) {
            execCommand.withWorkingDir(workingDirectory);
        }
        if (!env.isEmpty()) {
            execCommand.withEnv(env);
        }
        if (user != null) {
            execCommand.withUser(user);
        }
        ExecCreateCmdResponse response = execCommand.exec();
        dockerClient
                .execStartCmd(response.getId())
                .exec(new ExecStartResultCallback(System.out, System.err))
                .awaitCompletion();
        Long exitCode = dockerClient.inspectExecCmd(response.getId()).exec().getExitCodeLong();
        return exitCode == null ? 1 : exitCode.intValue();
    }

    private Bind bind(Path source, Path destination, AccessMode accessMode) {
        return new Bind(
                source.toAbsolutePath().toString(), new Volume(destination.toString()), accessMode);
    }

    private List<String> containerEnvironment(Path workspacePath) throws Exception {
        List<String> environment = new ArrayList<>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (List.of(
                            "DOCKER_HOST",
                            "DOCKER_TLS_VERIFY",
                            "DOCKER_CERT_PATH",
                            "DOCKER_CONTEXT",
                            "HOME",
                            "HOSTNAME",
                            "JAVA_HOME",
                            "JAVA_TOOL_OPTIONS",
                            "PATH",
                            "PWD",
                            "OLDPWD")
                    .contains(entry.getKey())) {
                continue;
            }
            environment.add(containerEnvironment(entry.getKey(), entry.getValue()));
        }
        environment.add("HOME=" + CONTAINER_HOME);
        List<Path> prefixes = dependencyPrefixes(workspacePath.resolve("private/prefix"));
        List<String> executablePaths = new ArrayList<>();
        List<String> libraryPaths = new ArrayList<>();
        Path javaHome = null;
        for (Path prefix : prefixes) {
            Path containerPrefix = CONTAINER_WORKSPACE.resolve(workspacePath.relativize(prefix));
            for (String executableDirectory : List.of("bin", "usr/bin", "sbin", "usr/sbin")) {
                addIfDirectory(
                        executablePaths,
                        prefix.resolve(executableDirectory),
                        containerPrefix.resolve(executableDirectory));
            }
            for (String libraryDirectory : List.of("lib", "lib64", "usr/lib", "usr/lib64")) {
                addLibraryDirectories(
                        libraryPaths,
                        prefix.resolve(libraryDirectory),
                        containerPrefix.resolve(libraryDirectory));
            }
            if (javaHome == null) {
                List<Path> javaBins =
                        List.of(
                                Path.of("bin/java"),
                                Path.of("usr/bin/java"),
                                Path.of("usr/lib/jvm/default-java/bin/java"));
                for (Path javaBin : javaBins) {
                    Path java = prefix.resolve(javaBin);
                    if (isExecutable(java)) {
                        javaHome = containerPrefix.resolve(javaBin).getParent().getParent();
                        break;
                    }
                }
            }
        }
        if (javaHome == null) {
            throw new IllegalStateException(
                    "A build dependency must provide a Java runtime for containerized package builds");
        }
        environment.add("JAVA_HOME=" + javaHome);
        environment.add(
                "PATH="
                        + String.join(File.pathSeparator, executablePaths)
                        + ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        if (!libraryPaths.isEmpty()) {
            environment.add("LD_LIBRARY_PATH=" + String.join(File.pathSeparator, libraryPaths));
        }
        environment.add("JAVA_TOOL_OPTIONS=-Duser.home=" + CONTAINER_HOME);
        return environment;
    }

    private List<Path> dependencyPrefixes(Path prefixRoot) throws Exception {
        List<Path> prefixes = new ArrayList<>();
        for (Path packageDirectory : childDirectories(prefixRoot)) {
            prefixes.addAll(childDirectories(packageDirectory));
        }
        return prefixes;
    }

    private void addIfDirectory(List<String> paths, Path directory, Path containerDirectory) {
        if (isDirectory(directory)) {
            paths.add(containerDirectory.toString());
        }
    }

    private void addLibraryDirectories(List<String> paths, Path directory, Path containerDirectory)
            throws Exception {
        if (!isDirectory(directory)) {
            return;
        }
        paths.add(containerDirectory.toString());
        for (Path entry : childDirectories(directory)) {
            paths.add(containerDirectory.resolve(entry.getFileName()).toString());
        }
    }

    private List<Path> childDirectories(Path directory) throws Exception {
        try (var entries = Files.list(directory)) {
            return entries.filter(this::isDirectory).sorted().toList();
        } catch (NoSuchFileException exception) {
            return List.of();
        }
    }

    private boolean isDirectory(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class).isDirectory();
        } catch (NoSuchFileException exception) {
            return false;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect directory " + path, exception);
        }
    }

    private boolean isExecutable(Path path) throws Exception {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            return permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                    || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    || permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    private String containerEnvironment(String name, String value) throws Exception {
        if (!name.equals("AWS_S3_ENDPOINT") || value.isBlank()) {
            return name + "=" + value;
        }

        URI endpoint = URI.create(value);
        String host = endpoint.getHost();
        if (host == null
                || !(host.equalsIgnoreCase("localhost")
                        || host.equals("::1")
                        || host.equals("[::1]")
                        || host.matches("127(?:\\.\\d{1,3}){3}"))) {
            return name + "=" + value;
        }

        URI containerEndpoint =
                new URI(
                        endpoint.getScheme(),
                        endpoint.getUserInfo(),
                        "host.docker.internal",
                        endpoint.getPort(),
                        endpoint.getPath(),
                        endpoint.getQuery(),
                        endpoint.getFragment());
        return name + "=" + containerEndpoint;
    }

    private String currentUserId() throws Exception {
        return currentId("-u");
    }

    private String currentGroupId() throws Exception {
        return currentId("-g");
    }

    private String currentId(String option) throws Exception {
        String key = option.equals("-u") ? "Uid:" : "Gid:";
        Path processStatus = Path.of("/proc/self/status");
        for (String line : Files.readAllLines(processStatus, StandardCharsets.UTF_8)) {
            if (line.startsWith(key)) {
                String[] ids = line.substring(key.length()).trim().split("\\s+");
                return ids[0];
            }
        }
        String environmentName = option.equals("-u") ? "UID" : "GID";
        return System.getenv().getOrDefault(environmentName, "1000");
    }
}
