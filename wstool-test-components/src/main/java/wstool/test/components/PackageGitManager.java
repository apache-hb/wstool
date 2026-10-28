package wstool.test.components;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import dev.parsick.testcontainers.gitserver.gitea.GiteaVersions;
import io.gitea.ApiClient;
import io.gitea.api.RepositoryApi;
import io.gitea.model.CreateRepoOption;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import wstool.git.GitClient;
import wstool.git.model.GitRunInput;
import wstool.git.model.ImmutableGitRunInput;

public class PackageGitManager {
    private static final Logger LOGGER = LogManager.getLogger(PackageGitManager.class);

    public static GiteaContainer container() {
        GiteaContainer container = new GiteaContainer(GiteaVersions.V1_25_4.getDockerImageName());
        container.withStartupTimeout(Duration.ofMinutes(2));
        return TestContainerLimits.apply(container, 384L * 1024 * 1024, "2G");
    }

    private final Path tmpdir;
    private final GiteaContainer gitea;
    private final RepositoryApi api;
    private final GitClient git;

    public PackageGitManager(Path tmpdir, GiteaContainer gitea) {
        this.tmpdir = tmpdir;
        this.gitea = gitea;
        this.api = new RepositoryApi(createApiClient(gitea));
        this.git = new GitClient(false);
    }

    public URI create(String name, String folder) throws Exception {
        URI uri = create(name);
        init(name, folder);
        return uri;
    }

    public URI create(String name, Path folder, Map<Path, Path> additionalFolders)
            throws Exception {
        URI uri = create(name);
        String branch = "master";
        Path repository = tmpdir.resolve("git").resolve(name);
        Files.createDirectories(repository);
        configure(repository, branch);
        copyFolder(folder, repository);

        for (var additionalFolder : additionalFolders.entrySet()) {
            Path destination = additionalFolder.getKey().normalize();
            if (destination.isAbsolute() || destination.startsWith("..")) {
                throw new IllegalArgumentException(
                        "Repository folder path must be relative and stay within the repository: "
                                + additionalFolder.getKey());
            }
            copyFolder(additionalFolder.getValue(), repository.resolve(destination));
        }

        initialCommit(repository, name, branch);
        return uri;
    }

    public void init(String name, String folder) throws Exception {
        uploadResources(name, folder);
    }

    public void uploadFiles(String name, Path folder) throws Exception {
        String branch = "master";

        Path tmp = tmpdir.resolve("git").resolve(name);
        Files.createDirectories(tmp);

        configure(tmp, branch);

        Files.walk(folder)
                .filter(Files::isRegularFile)
                .forEach(
                        file -> {
                            Path path = folder.relativize(file);
                            try {
                                Path dst = tmp.resolve(path).toAbsolutePath();
                                LOGGER.info("Copying file {} to {}", file, dst);
                                Files.createDirectories(dst.getParent());
                                Files.copy(
                                        file,
                                        dst,
                                        StandardCopyOption.REPLACE_EXISTING,
                                        StandardCopyOption.COPY_ATTRIBUTES);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });

        initialCommit(tmp, name, branch);
    }

    private void copyFolder(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("Source folder does not exist: " + source);
        }

        try (var paths = Files.walk(source)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                Path target = destination.resolve(source.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(
                        file,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    public void uploadResources(String name, String folder) throws Exception {
        String branch = "master";

        Path tmp = tmpdir.resolve("git").resolve(name);
        Files.createDirectories(tmp);

        configure(tmp, branch);

        ClassGraph graph = new ClassGraph();
        ScanResult result = graph.acceptPaths(folder).scan();

        for (var resource : result.getAllResources()) {
            Path path = Path.of(folder).relativize(Path.of(resource.getPath()));

            try (var content = resource.open()) {
                Path dst = tmp.resolve(path).toAbsolutePath();
                LOGGER.info("Copying file {} to {}", resource.getPath(), dst);
                Files.copy(content, dst);
            }
        }

        initialCommit(tmp, name, branch);
    }

    public URI create(String name) throws Exception {
        CreateRepoOption input = new CreateRepoOption();
        input.setName(name);
        input.setPrivate(false);
        api.createCurrentUserRepo(input);

        return repositoryUri(name);
    }

    public URI endpoint() {
        return URI.create("http://" + host());
    }

    public URI repositoryUri(String repository) {
        return URI.create("http://" + host() + "/" + username() + "/" + repository + ".git");
    }

    public String username() {
        return gitea.getUserName();
    }

    public String password() {
        return gitea.getUserPassword();
    }

    public void run(Path directory, String... args) throws Exception {
        GitRunInput input =
                ImmutableGitRunInput.builder()
                        .directory(directory)
                        .addArguments(args)
                        .interactive(false)
                        .username(username())
                        .password(password())
                        .build();

        git.run(input);
    }

    private void configure(Path directory, String branch) throws Exception {
        run(directory, "init", "--initial-branch=" + branch);
        run(directory, "config", "user.name", username());
        run(directory, "config", "user.email", username() + "@example.com");
    }

    public void initialCommit(Path directory, String name, String branch) throws Exception {
        run(directory, "add", "--all");
        run(directory, "commit", "--message", "Initial commit");
        run(directory, "remote", "add", "origin", repositoryUri(name).toString());
        run(directory, "push", "--set-upstream", "origin", branch);
    }

    private String host() {
        return gitea.getHost() + ":" + gitea.getMappedPort(3000);
    }

    private static ApiClient createApiClient(GiteaContainer gitea) {
        String url = "http://" + gitea.getHost() + ":" + gitea.getMappedPort(3000) + "/api/v1";
        ApiClient client = new ApiClient();
        client.setBasePath(url);
        client.setUsername(gitea.getUserName());
        client.setPassword(gitea.getUserPassword());
        return client;
    }
}
