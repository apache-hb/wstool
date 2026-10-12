package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wstool.simple.model.PackageMajorVersion;
import wstool.simple.storage.DependencyType;
import wstool.simple.storage.ImmutableAddDependencyInput;
import wstool.simple.storage.ImmutableAddPackageInput;

class PathHelperTest {
    @TempDir Path tempDir;

    PathHelper helper;

    @AutoClose PklHelper pkl;

    WorkspaceFactory workspaceFactory;

    @BeforeEach
    void beforeEach() {
        helper = new PathHelper();
        pkl = new PklHelper(Map.of());
        workspaceFactory = new WorkspaceFactory(pkl);
    }

    @Test
    void buildClosurePathIncludesLocalToolsAndDirectBuildDependencyBins() throws Exception {
        PackageMajorVersion app = new PackageMajorVersion("app", "1.x");
        PackageMajorVersion compiler = new PackageMajorVersion("compiler", "2.x");
        PackageMajorVersion runtimeLibrary = new PackageMajorVersion("runtime-library", "3.x");
        Path database = tempDir.resolve("wstool.db");
        Path appSource = tempDir.resolve("app-source");
        Path appLocalTools = appSource.resolve("wstool/bin");
        Path compilerPrefix = tempDir.resolve("private/prefix/compiler/2.x");
        Files.createDirectories(appLocalTools);

        try (var storage = new WorkspaceStorage(tempDir, database)) {
            storage.setup();
            storage.addPackage(packageInput(app, appSource));
            storage.addPackage(packageInput(compiler, tempDir.resolve("compiler-source")));
            storage.addPackage(packageInput(runtimeLibrary, tempDir.resolve("runtime-source")));
            storage.addDependency(dependency(app, compiler, DependencyType.BUILD));
            storage.addDependency(dependency(app, runtimeLibrary, DependencyType.RUNTIME));
        }

        try (var workspace = workspaceFactory.open(tempDir)) {
            String expected =
                    appLocalTools.toAbsolutePath()
                            + ":"
                            + compilerPrefix.resolve("bin").toAbsolutePath();
            assertEquals(expected, helper.buildClosurePath(workspace, app));
        }
    }

    @Test
    void buildClosurePathOmitsLocalToolsWhenDirectoryDoesNotExist() throws Exception {
        PackageMajorVersion app = new PackageMajorVersion("app", "1.x");
        Path database = tempDir.resolve("wstool.db");
        Path appSource = tempDir.resolve("app-source");

        try (var storage = new WorkspaceStorage(tempDir, database)) {
            storage.setup();
            storage.addPackage(packageInput(app, appSource));
        }

        try (var workspace = workspaceFactory.open(tempDir)) {
            assertThrows(
                    IllegalStateException.class, () -> helper.buildClosurePath(workspace, app));
        }
    }

    private static ImmutableAddPackageInput packageInput(PackageMajorVersion name, Path source) {
        return ImmutableAddPackageInput.builder().name(name).source(source).cloned(false).build();
    }

    private static ImmutableAddDependencyInput dependency(
            PackageMajorVersion source, PackageMajorVersion target, DependencyType type) {
        return ImmutableAddDependencyInput.builder()
                .name(source)
                .dependency(target)
                .type(type)
                .build();
    }
}
