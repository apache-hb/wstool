package wstool.common.path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import wstool.common.linux.MountInfoReader;

class DockerStorageOptSupportTest {
    private static final Path DOCKER_ROOT = Path.of("/var/lib/docker");

    @TempDir Path tempDirectory;

    @Test
    void defaultConstructorUsesHostMountInfoDefaults() {
        assertDoesNotThrow(() -> new DockerStorageOptSupport().supports("overlay2", Path.of("/")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storageOptSupportCases")
    void detectsStorageOptSupport(
            String scenario,
            String driver,
            Path dockerRoot,
            List<String> mountInfoLines,
            boolean expected) {
        assertEquals(expected, supports(driver, dockerRoot, mountInfoLines), scenario);
    }

    private static Stream<Arguments> storageOptSupportCases() {
        return Stream.of(
                Arguments.of(
                        "accepts pquota in mount options",
                        "overlay2",
                        DOCKER_ROOT,
                        mount("/var/lib/docker", "rw,relatime,pquota"),
                        true),
                Arguments.of(
                        "accepts prjquota in superblock options",
                        "overlay2",
                        DOCKER_ROOT,
                        mount("/var/lib/docker", "rw,relatime", "rw,prjquota"),
                        true),
                Arguments.of(
                        "reads fields after the separator when no optional fields exist",
                        "overlay2",
                        DOCKER_ROOT,
                        List.of("36 25 0:32 / /var/lib/docker rw,pquota - xfs /dev/sda1 rw"),
                        true),
                Arguments.of(
                        "rejects other storage drivers",
                        "vfs",
                        DOCKER_ROOT,
                        mount("/var/lib/docker", "rw,pquota"),
                        false),
                Arguments.of(
                        "rejects non-xfs filesystems",
                        "overlay2",
                        DOCKER_ROOT,
                        mount("/var/lib/docker", "rw,pquota", "rw,pquota", "ext4"),
                        false),
                Arguments.of(
                        "rejects xfs without project quota",
                        "overlay2",
                        DOCKER_ROOT,
                        mount("/var/lib/docker", "rw,relatime"),
                        false),
                Arguments.of(
                        "uses the most specific matching mount",
                        "overlay2",
                        DOCKER_ROOT,
                        List.of(
                                mountLine("/var/lib", "rw,pquota"),
                                mountLine("/var/lib/docker", "rw,relatime")),
                        false),
                Arguments.of(
                        "unescapes mount points before matching",
                        "overlay2",
                        Path.of("/var/lib/docker data/overlay2"),
                        mount("/var/lib/docker\\040data", "rw,pquota"),
                        true));
    }

    @Test
    void propagatesMalformedMountInfoWithoutFilesystemSeparator() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        supports(
                                "overlay2",
                                DOCKER_ROOT,
                                List.of("36 25 0:32 / /var/lib/docker rw")));
    }

    private boolean supports(String driver, Path dockerRoot, List<String> mountInfoLines) {
        Path mountInfo = tempDirectory.resolve("mountinfo");
        try {
            Files.write(mountInfo, mountInfoLines);
        } catch (IOException exception) {
            throw new AssertionError("Could not write mount-info fixture", exception);
        }
        return new DockerStorageOptSupport(new MountInfoReader(mountInfo))
                .supports(driver, dockerRoot);
    }

    private static List<String> mount(String mountPoint, String mountOptions) {
        return List.of(mountLine(mountPoint, mountOptions));
    }

    private static List<String> mount(String mountPoint, String mountOptions, String superOptions) {
        return List.of(mountLine(mountPoint, mountOptions, superOptions));
    }

    private static List<String> mount(
            String mountPoint, String mountOptions, String superOptions, String filesystemType) {
        return List.of(mountLine(mountPoint, mountOptions, superOptions, filesystemType));
    }

    private static String mountLine(String mountPoint, String mountOptions) {
        return mountLine(mountPoint, mountOptions, mountOptions);
    }

    private static String mountLine(String mountPoint, String mountOptions, String superOptions) {
        return mountLine(mountPoint, mountOptions, superOptions, "xfs");
    }

    private static String mountLine(
            String mountPoint, String mountOptions, String superOptions, String filesystemType) {
        return "36 25 0:32 / "
                + mountPoint
                + " "
                + mountOptions
                + " shared:1 - "
                + filesystemType
                + " /dev/sda1 "
                + superOptions;
    }
}
