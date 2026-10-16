package wstool.common.path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import wstool.common.model.DependencyType;
import wstool.common.model.PackageDependency;
import wstool.common.model.PackageMajorVersion;
import wstool.common.workspace.LocalWorkspace;

class SysrootTest {
    private static final Path TEST_OUTPUT_ROOT = Path.of("build/sysroot-test-output").toAbsolutePath();
    private static final PackageMajorVersion APP = new PackageMajorVersion("app", "1.0");
    private static final PackageMajorVersion MIDDLE = new PackageMajorVersion("middle", "1.0");
    private static final PackageMajorVersion LEAF = new PackageMajorVersion("leaf", "1.0");
    private static final PackageMajorVersion BUILD_ONLY =
            new PackageMajorVersion("build-only", "1.0");
    private static final PackageMajorVersion TEST_ONLY =
            new PackageMajorVersion("test-only", "1.0");

    private LocalWorkspace workspace;
    private PathBuilder pathBuilder;
    private Path testOutput;

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        testOutput = TEST_OUTPUT_ROOT.resolve(testInfo.getTestMethod().orElseThrow().getName());
        deleteTestOutput();
        Path workspacePath = testOutput.resolve("workspace");
        Files.createDirectories(workspacePath);
        workspace = new LocalWorkspace(workspacePath, null, null, null);
        workspace.storage().setup();
        workspace.storage().refresh(
                List.of(APP, MIDDLE, LEAF, BUILD_ONLY, TEST_ONLY),
                List.of(
                        dependency(APP, MIDDLE, DependencyType.RUNTIME),
                        dependency(MIDDLE, LEAF, DependencyType.PUBLIC),
                        dependency(APP, BUILD_ONLY, DependencyType.BUILD),
                        dependency(BUILD_ONLY, MIDDLE, DependencyType.RUNTIME),
                        dependency(APP, TEST_ONLY, DependencyType.TEST)));

        copyResource("path-builder/middle-tool", workspace.prefix(MIDDLE).resolve("bin/middle-tool"));
        copyResource("path-builder/leaf-library", workspace.prefix(LEAF).resolve("lib/nested/libleaf.so"));
        copyResource(
                "path-builder/build-only-tool",
                workspace.prefix(BUILD_ONLY).resolve("bin/build-only-tool"));
        copyResource(
                "path-builder/test-only-tool",
                workspace.prefix(TEST_ONLY).resolve("bin/test-only-tool"));

        pathBuilder = new PathBuilder();
    }

    @Test
    void selectorsCreateTheirTransitiveDependencyClosures() throws Exception {
        Path build = testOutput.resolve("build-sysroot");
        Path development = testOutput.resolve("development-sysroot");
        Path runtime = testOutput.resolve("runtime-sysroot");

        pathBuilder.sysrootSymlinkFolder(workspace, APP, "build", build);
        pathBuilder.sysrootSymlinkFolder(workspace, APP, "development", development);
        pathBuilder.sysrootSymlinkFolder(workspace, APP, "runtime", runtime);

        assertTrue(Files.isSymbolicLink(build.resolve("bin/build-only-tool")));
        assertTrue(Files.isSymbolicLink(build.resolve("bin/middle-tool")));
        assertTrue(Files.isSymbolicLink(build.resolve("lib/nested/libleaf.so")));
        assertFalse(Files.exists(build.resolve("bin/test-only-tool")));
        assertFalse(Files.exists(build.resolve("bin/app-tool")));

        assertTrue(Files.isSymbolicLink(development.resolve("bin/build-only-tool")));
        assertTrue(Files.isSymbolicLink(development.resolve("bin/middle-tool")));
        assertTrue(Files.isSymbolicLink(development.resolve("lib/nested/libleaf.so")));
        assertTrue(Files.isSymbolicLink(development.resolve("bin/test-only-tool")));
        assertFalse(Files.exists(development.resolve("bin/app-tool")));

        assertFalse(Files.exists(runtime.resolve("bin/build-only-tool")));
        assertTrue(Files.isSymbolicLink(runtime.resolve("bin/middle-tool")));
        assertTrue(Files.isSymbolicLink(runtime.resolve("lib/nested/libleaf.so")));
        assertFalse(Files.exists(runtime.resolve("bin/test-only-tool")));
        assertFalse(Files.exists(runtime.resolve("bin/app-tool")));
    }

    @Test
    void rejectsUnknownSelector() {
        Path target = testOutput.resolve("invalid-sysroot");

        assertThrows(
                IllegalArgumentException.class,
                () -> pathBuilder.sysrootSymlinkFolder(workspace, APP, "invalid", target));
        assertFalse(Files.exists(target));
    }

    private static PackageDependency dependency(
            PackageMajorVersion name, PackageMajorVersion dependency, DependencyType type) {
        return new PackageDependency(name, dependency, type);
    }

    private static void copyResource(String resource, Path destination) throws Exception {
        Files.createDirectories(destination.getParent());
        Files.copy(resourcePath(resource), destination);
    }

    private static Path resourcePath(String resource) throws Exception {
        return Path.of(SysrootTest.class.getClassLoader().getResource(resource).toURI());
    }

    private void deleteTestOutput() throws IOException {
        if (Files.exists(testOutput)) {
            try (var paths = Files.walk(testOutput)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
