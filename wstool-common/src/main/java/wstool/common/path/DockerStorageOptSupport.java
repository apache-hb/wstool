package wstool.common.path;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import javax.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import wstool.common.linux.MountInfoEntry;
import wstool.common.linux.MountInfoReader;

/** Determines whether Docker's writable-layer size option is supported by its storage filesystem. */
public final class DockerStorageOptSupport {
    private static final Logger LOGGER = LogManager.getLogger(DockerStorageOptSupport.class);

    private final MountInfoReader mountInfoReader;

    @Inject
    public DockerStorageOptSupport() {
        this(new MountInfoReader());
    }

    public DockerStorageOptSupport(MountInfoReader mountInfoReader) {
        this.mountInfoReader = mountInfoReader;
    }

    public boolean supports(String storageDriver, Path dockerRoot) {
        if (!"overlay2".equals(storageDriver)) {
            return false;
        }

        Path normalizedDockerRoot = dockerRoot.toAbsolutePath().normalize();
        try {
            Optional<MountInfoEntry> mountInfoEntry =
                    mountInfoReader.findMostSpecificMount(normalizedDockerRoot);
            return mountInfoEntry
                    .filter(entry -> "xfs".equals(entry.type()))
                    .map(this::hasProjectQuota)
                    .orElse(false);
        } catch (IOException exception) {
            LOGGER.info(
                    "Skipping Docker writable-layer limit because storage support is unknown");
            return false;
        }
    }

    private boolean hasProjectQuota(MountInfoEntry entry) {
        return entry.mountOptions().contains("pquota") || entry.superOptions().contains("prjquota");
    }
}
