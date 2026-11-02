package wstool.test.components;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import com.github.dockerjava.api.DockerClient;

public final class TestContainerLimits {
    private static final Logger LOGGER = LogManager.getLogger(TestContainerLimits.class);

    private final DockerClient docker;

    public TestContainerLimits(DockerClient docker) {
        this.docker = docker;
    }

    public static TestContainerLimits instance() {
        return Holder.INSTANCE;
    }

    public <T extends GenericContainer<?>> T apply(
            T container, long memoryBytes, String diskSize) {

        // Avoids '--storage-opt is supported only for overlay over xfs with 'pquota' mount option'
        Map<String, String> storageOpts =
                supportsStorageOpt() ? Map.of("size", diskSize) : null;

        container.withCreateContainerCmdModifier(
                command ->
                        command.getHostConfig()
                                .withMemory(memoryBytes)
                                .withMemorySwap(memoryBytes)
                                .withStorageOpt(storageOpts));
        return container;
    }

    private boolean supportsStorageOpt() {
        String driver = docker.infoCmd().exec().getDriver();
        String vfsType = getVfsType(dockerRoot());
        boolean supported = driver.equals("overlay2") && vfsType.equals("xfs");
        LOGGER.info("Docker storage driver: {}, vfs type: {}, supports --storage-opt: {}", driver, vfsType, supported);
        return supported;
    }

    private Path dockerRoot() {
        return Path.of(docker.infoCmd().exec().getDockerRootDir());
    }

    private String getVfsType(Path path) {
        try {
            FileStore store = Files.getFileStore(path);
            return store.type();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Holder {
        private static final TestContainerLimits INSTANCE = new TestContainerLimits(DockerClientFactory.lazyClient());
    }
}
