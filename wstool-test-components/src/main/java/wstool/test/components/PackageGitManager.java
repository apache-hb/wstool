package wstool.test.components;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import dev.parsick.testcontainers.gitserver.gitea.GiteaVersions;
import io.gitea.ApiClient;
import io.gitea.api.RepositoryApi;
import io.gitea.model.CreateRepoOption;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import wstool.git.GitClient;
import wstool.git.model.GitRunInput;
import wstool.git.model.ImmutableGitRunInput;

public class PackageGitManager {
    private static final Logger LOGGER = LogManager.getLogger(PackageGitManager.class);

    public static GiteaContainer container() {
        GiteaContainer container = new GiteaContainer(GiteaVersions.V1_25_4.getDockerImageName());
        container.withStartupTimeout(Duration.ofMinutes(2));
        return container;
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
        URI uri = setup(name);
        init(name, folder);
        return uri;
    }

    public void init(String name, String folder) throws Exception {
        String branch = "master";

        Path tmp = tmpdir.resolve("git").resolve(name);
        Files.createDirectories(tmp);

        run(tmp, "init", "--initial-branch=" + branch);
        run(tmp, "config", "user.name", username());
        run(tmp, "config", "user.email", username() + "@example.com");

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

        run(tmp, "add", "--all");
        run(tmp, "commit", "--message", "Initial commit");
        run(tmp, "remote", "add", "origin", repositoryUri(name).toString());
        run(tmp, "push", "--set-upstream", "origin", branch);
    }

    public URI setup(String name) throws Exception {
        CreateRepoOption input = new CreateRepoOption();
        input.setName(name);
        input.setPrivate(false);
        api.createCurrentUserRepo(input);

        return repositoryUri(name);
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
        GitRunInput input = ImmutableGitRunInput.builder()
            .directory(directory)
            .addArguments(args)
            .interactive(false)
            .username(username())
            .password(password())
            .build();

        git.run(input);
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
