package wstool.simple.workspace;

import java.nio.file.Path;
import org.immutables.value.Value;
import wstool.simple.git.GitRepositoryLocation;

@Value.Immutable
public interface CreateWorkspaceInput {
    Path parent();

    String name();

    GitRepositoryLocation distribution();
}
