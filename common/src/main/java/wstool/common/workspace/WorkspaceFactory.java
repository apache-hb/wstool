package wstool.common.workspace;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import wstool.common.distribution.DistributionFactory;
import wstool.git.GitClient;

@Singleton
public class WorkspaceFactory {
    private final DistributionFactory distributionFactory;
    private final GitClient git;

    @Inject
    public WorkspaceFactory(DistributionFactory distributionFactory, GitClient git) {
        this.distributionFactory = distributionFactory;
        this.git = git;
    }

    public LocalWorkspace open(Path path) throws Exception {
        return new LocalWorkspace(path, distributionFactory, git);
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
