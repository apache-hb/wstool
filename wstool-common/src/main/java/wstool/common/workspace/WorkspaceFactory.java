package wstool.common.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.inject.Inject;
import javax.inject.Singleton;
import wstool.common.distribution.DistributionFactory;
import wstool.common.packages.PackageFactory;
import wstool.git.GitClient;
import wstool.git.model.GitRepositoryLocation;

@Singleton
public class WorkspaceFactory {
    private final DistributionFactory distributionFactory;
    private final PackageFactory packageFactory;
    private final GitClient git;

    @Inject
    public WorkspaceFactory(
            DistributionFactory distributionFactory, PackageFactory packageFactory, GitClient git) {
        this.distributionFactory = distributionFactory;
        this.packageFactory = packageFactory;
        this.git = git;
    }

    public LocalWorkspace setup(Path path, GitRepositoryLocation distribution) throws Exception {
        Files.createDirectories(path);
        LocalWorkspace workspace =
                new LocalWorkspace(path, distributionFactory, packageFactory, git);
        workspace.setup(distribution);
        return workspace;
    }

    public LocalWorkspace open(Path path) throws Exception {
        return new LocalWorkspace(path, distributionFactory, packageFactory, git);
    }

    public LocalWorkspace findUp(Path cwd) throws Exception {
        Path current = cwd.toAbsolutePath();

        while (current != null && !LocalWorkspace.isWorkspace(current)) {
            current = current.getParent();
        }

        if (current == null) {
            throw new IllegalArgumentException(
                    "No workspace found in current directory or any parent directories.");
        }

        return open(current);
    }
}
