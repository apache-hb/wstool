package wstool.simple.workspace;

import java.nio.file.Path;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;
import wstool.simple.git.GitRepositoryLocation;

@Value.Immutable
public interface CreateWorkspaceInput {
    Path parent();

    String name();

    @Nullable GitRepositoryLocation distribution();
}
