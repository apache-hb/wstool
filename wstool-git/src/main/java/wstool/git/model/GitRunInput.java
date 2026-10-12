package wstool.git.model;

import java.nio.file.Path;
import java.util.List;
import org.immutables.value.Value;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@Value.Immutable
public interface GitRunInput {
    Path directory();

    List<String> arguments();

    boolean interactive();

    @Nullable String username();

    @Nullable String password();
}
