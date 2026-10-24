package wstool.common.packages;

import java.net.URI;
import java.util.List;

public record ArchiveSourceConfig(URI uri, String checksum, int stripComponents, List<URI> patches)
        implements ExternalSource {
    public ArchiveSourceConfig {
        patches = List.copyOf(patches);
    }
}
