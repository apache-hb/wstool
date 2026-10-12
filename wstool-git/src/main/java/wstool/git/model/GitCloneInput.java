package wstool.git.model;

import java.nio.file.Path;
import org.immutables.value.Value;

@Value.Immutable
public interface GitCloneInput {
    Path parent();

    String name();

    GitRepositoryLocation repository();
}
