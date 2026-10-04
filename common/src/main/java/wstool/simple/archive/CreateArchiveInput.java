package wstool.simple.archive;

import java.nio.file.Path;
import org.immutables.value.Value;

@Value.Immutable
public interface CreateArchiveInput {
    Path source();

    Path destination();
}
