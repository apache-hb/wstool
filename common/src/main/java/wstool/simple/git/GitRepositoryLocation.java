package wstool.simple.git;

import java.net.URI;
import org.immutables.value.Value;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@Value.Immutable
public interface GitRepositoryLocation {
    URI repository();

    @Nullable String branch();

    @Nullable String username();

    @Nullable String password();
}
