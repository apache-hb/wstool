package wstool.simple.download;

import java.net.URI;
import java.nio.file.Path;
import org.immutables.value.Value;

@Value.Immutable
public interface DownloadInput {
    Path path();

    URI remote();
}
