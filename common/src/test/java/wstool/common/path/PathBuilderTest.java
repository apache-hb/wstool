package wstool.common.path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class PathBuilderTest {
    private static final Path TEST_OUTPUT_ROOT =
            Path.of("build/path-builder-test-output").toAbsolutePath();
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
                        dependency(APP, TEST_ONLY, DependencyType.TEST)));
        Files.createDirectories(workspace.prefix(APP));

        copyResource("path-builder/app-tool", workspace.prefix(APP).resolve("bin/app-tool"));
        copyResource(
                "path-builder/middle-tool", workspace.prefix(MIDDLE).resolve("bin/middle-tool"));
        copyResource(
                "path-builder/leaf-library",
                workspace.prefix(LEAF).resolve("lib/nested/libleaf.so"));
        copyResource(
                "path-builder/build-only-tool",
                workspace.prefix(BUILD_ONLY).resolve("bin/build-only-tool"));
        copyResource(
                "path-builder/test-only-tool",
                workspace.prefix(TEST_ONLY).resolve("bin/test-only-tool"));

        pathBuilder = new PathBuilder();
    }

    @Test
    void runtimeSymlinkFolderLinksContentsFromTransitiveRuntimeClosure() throws Exception {
        Path target = testOutput.resolve("runtime");

        pathBuilder.runtimeSymlinkFolder(workspace, APP, target);

        Path middleTool = target.resolve("bin/middle-tool");
        Path leafLibrary = target.resolve("lib/nested/libleaf.so");
        Path appTool = target.resolve("bin/app-tool");
        assertTrue(Files.isSymbolicLink(appTool));
        assertEquals(
                workspace.prefix(APP).resolve("bin/app-tool"), Files.readSymbolicLink(appTool));
        assertTrue(Files.isSymbolicLink(middleTool));
        assertEquals(
                workspace.prefix(MIDDLE).resolve("bin/middle-tool"),
                Files.readSymbolicLink(middleTool));
        assertTrue(Files.isSymbolicLink(leafLibrary));
        assertEquals(
                workspace.prefix(LEAF).resolve("lib/nested/libleaf.so"),
                Files.readSymbolicLink(leafLibrary));
        assertArrayEquals(
                Files.readAllBytes(resourcePath("path-builder/middle-tool")),
                Files.readAllBytes(middleTool));
        assertArrayEquals(
                Files.readAllBytes(resourcePath("path-builder/leaf-library")),
                Files.readAllBytes(leafLibrary));

        assertFalse(Files.exists(target.resolve("bin/build-only-tool")));
        assertFalse(Files.exists(target.resolve("bin/test-only-tool")));
    }

    @Test
    void runtimeSymlinkFolderLeavesExistingDestinationFilesAlone() throws Exception {
        Path target = testOutput.resolve("runtime");
        Path existingFile = target.resolve("bin/middle-tool");
        Path fixture = resourcePath("path-builder/middle-tool");
        Files.createDirectories(existingFile.getParent());
        Files.copy(fixture, existingFile);

        pathBuilder.runtimeSymlinkFolder(workspace, APP, target);

        assertFalse(Files.isSymbolicLink(existingFile));
        assertArrayEquals(Files.readAllBytes(fixture), Files.readAllBytes(existingFile));
        assertTrue(Files.isSymbolicLink(target.resolve("lib/nested/libleaf.so")));
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
        return Path.of(PathBuilderTest.class.getClassLoader().getResource(resource).toURI());
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
