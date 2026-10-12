package wstool.common.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import wstool.common.distribution.DistributionConfig;
import wstool.common.distribution.DistributionFactory;
import wstool.common.helpers.Asserts;
import wstool.common.manifest.WorkspaceStorage;
import wstool.common.model.PackageMajorVersion;
import wstool.common.packages.PackageConfig;
import wstool.git.GitClient;
import wstool.git.model.GitCloneInput;
import wstool.git.model.GitRepositoryLocation;
import wstool.git.model.ImmutableGitCloneInput;

public class LocalWorkspace {
    private static final String DISTRIBUTION = "distribution";
    private static final String SOURCES = "src";
    private static final String CACHE = "private/cache";
    private static final String PREFIX = "private/prefix";
    private static final String BUILD = "private/build";
    private static final String MANIFEST = "wstool.db";

    private final Path path;
    private final WorkspaceStorage storage;
    private final DistributionFactory distributionFactory;
    private final GitClient git;

    public LocalWorkspace(Path path, DistributionFactory distributionFactory, GitClient git)
            throws Exception {
        this.path = path;
        this.storage = new WorkspaceStorage(path.resolve(MANIFEST));
        this.distributionFactory = distributionFactory;
        this.git = git;
    }

    public Path path() {
        return path;
    }

    public void setup() throws Exception {
        Files.createDirectories(path.resolve(SOURCES));
        Files.createDirectories(path.resolve(CACHE));
        Files.createDirectories(path.resolve(PREFIX));
        Files.createDirectories(path.resolve(BUILD));
    }

    public void configure(GitRepositoryLocation repository) throws Exception {
        GitCloneInput input =
                ImmutableGitCloneInput.builder()
                        .parent(path)
                        .name(DISTRIBUTION)
                        .repository(repository)
                        .build();

        git.clone(input);
    }

    public void refresh() throws Exception {
        DistributionConfig config = distributionFactory.open(path.resolve(DISTRIBUTION));
    }

    public void validate() {
        Asserts.check(Files.exists(path.resolve(SOURCES)), "Missing " + SOURCES + " directory");
        Asserts.check(Files.exists(path.resolve(CACHE)), "Missing " + CACHE + " directory");
        Asserts.check(Files.exists(path.resolve(PREFIX)), "Missing " + PREFIX + " directory");
        Asserts.check(Files.exists(path.resolve(BUILD)), "Missing " + BUILD + " directory");
        Asserts.check(Files.exists(path.resolve(MANIFEST)), "Missing " + MANIFEST + " manifest");
        Asserts.check(
                Files.isDirectory(path.resolve(DISTRIBUTION)),
                "Missing " + DISTRIBUTION + " repository");
    }

    public PackageConfig find(PackageMajorVersion name) {
        return null;
    }

    public static boolean isWorkspace(Path path) {
        return Files.exists(path.resolve(MANIFEST));
    }
}
