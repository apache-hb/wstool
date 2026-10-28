package wstool.test.components;

import java.util.Map;
import org.testcontainers.containers.GenericContainer;

/** Applies bounded cgroup and writable-layer limits to test service containers. */
public final class TestContainerLimits {
    private TestContainerLimits() {}

    public static <T extends GenericContainer<?>> T apply(
            T container, long memoryBytes, String diskSize) {
        container.withCreateContainerCmdModifier(
                command ->
                        command.getHostConfig()
                                .withMemory(memoryBytes)
                                .withMemorySwap(memoryBytes)
                                .withStorageOpt(Map.of("size", diskSize)));
        return container;
    }
}
