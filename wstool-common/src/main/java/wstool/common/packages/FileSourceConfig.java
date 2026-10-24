package wstool.common.packages;

import java.net.URI;
import java.util.List;

public record FileSourceConfig(URI uri, String path, String checksum, List<URI> patches)
        implements ExternalSource {
    public FileSourceConfig {
        patches = List.copyOf(patches);
    }
}
