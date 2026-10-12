package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

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

    @BeforeEach
    void beforeEach() {
        helper = new PathHelper();
    }

    @Test
    void buildClosurePathIncludesLocalToolsAndDirectBuildDependencyBins() throws Exception {
        PackageMajorVersion app = new PackageMajorVersion("app", "1.x");
        PackageMajorVersion compiler = new PackageMajorVersion("compiler", "2.x");
        PackageMajorVersion runtimeLibrary = new PackageMajorVersion("runtime-library", "3.x");
        Path database = tempDir.resolve("wstool.db");
        Path appSource = tempDir.resolve("app-source");
        Path appLocalTools = appSource.resolve("wstool/bin");
        Path compilerPrefix = tempDir.resolve("compiler-prefix");
        Path runtimePrefix = tempDir.resolve("runtime-prefix");
        Files.createDirectories(appLocalTools);

        try (var storage = new WorkspaceStorage(database)) {
            storage.setup();
            storage.addPackage(packageInput(app, appSource, tempDir.resolve("app-prefix")));
            storage.addPackage(packageInput(compiler, tempDir.resolve("compiler-source"), compilerPrefix));
            storage.addPackage(
                    packageInput(
                            runtimeLibrary,
                            tempDir.resolve("runtime-source"),
                            runtimePrefix));
            storage.addDependency(dependency(app, compiler, DependencyType.BUILD));
            storage.addDependency(dependency(app, runtimeLibrary, DependencyType.RUNTIME));
        }

        try (var workspace = new Workspace(tempDir)) {
            String expected = appLocalTools.toAbsolutePath() + ":" + compilerPrefix.resolve("bin").toAbsolutePath();
            assertEquals(expected, helper.buildClosurePath(workspace, app));
        }
    }

    @Test
    void buildClosurePathOmitsLocalToolsWhenDirectoryDoesNotExist() throws Exception {
        PackageMajorVersion app = new PackageMajorVersion("app", "1.x");
        Path database = tempDir.resolve("wstool.db");
        Path appSource = tempDir.resolve("app-source");

        try (var storage = new WorkspaceStorage(database)) {
            storage.setup();
            storage.addPackage(packageInput(app, appSource, tempDir.resolve("app-prefix")));
        }

        try (var workspace = new Workspace(tempDir)) {
            assertEquals("", helper.buildClosurePath(workspace, app));
        }
    }

    private static ImmutableAddPackageInput packageInput(
            PackageMajorVersion name, Path source, Path prefix) {
        return ImmutableAddPackageInput.builder()
                .name(name)
                .source(source)
                .prefix(prefix)
                .build(Path.of("build"))
                .cloned(false)
                .build();
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
