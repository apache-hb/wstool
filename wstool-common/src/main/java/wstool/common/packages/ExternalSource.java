package wstool.common.packages;

import java.net.URI;
import java.util.List;

public sealed interface ExternalSource permits ArchiveSourceConfig, FileSourceConfig {
    URI uri();

    String checksum();

    List<URI> patches();
}
