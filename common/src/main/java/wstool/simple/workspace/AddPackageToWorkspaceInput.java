package wstool.simple.workspace;

import java.nio.file.Path;
import org.immutables.value.Value;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@Value.Immutable
@NullMarked
public interface AddPackageToWorkspaceInput {
    Path workspace();

    String name();

    @Nullable String version();
}
