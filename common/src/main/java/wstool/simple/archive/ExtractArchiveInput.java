package wstool.simple.archive;

import java.nio.file.Path;
import org.immutables.value.Value;

@Value.Immutable
public interface ExtractArchiveInput {
    Path archive();

    Path destination();
}
