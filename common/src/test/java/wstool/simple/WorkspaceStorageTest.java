package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wstool.simple.model.PackageMajorVersion;
import wstool.simple.storage.DependencyType;
import wstool.simple.storage.ImmutableAddDependencyInput;

class WorkspaceStorageTest {
    @TempDir Path tempDir;

    @Test
    void addDependencyPersistsPackageDependencyAndLowercaseType() throws Exception {
        Path database = tempDir.resolve("workspace.db");
        var input =
                ImmutableAddDependencyInput.builder()
                        .name(new PackageMajorVersion("app", "1.x"))
                        .dependency(new PackageMajorVersion("library", "2.x"))
                        .type(DependencyType.RUNTIME)
                        .build();

        try (var storage = new WorkspaceStorage(database)) {
            storage.setup();
            storage.addDependency(input);
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement();
                var result =
                        statement.executeQuery(
                                "SELECT package_name, package_version, dependency_name, "
                                        + "dependency_version, dependency_type FROM dependencies")) {
            assertTrue(result.next());
            assertEquals("app", result.getString("package_name"));
            assertEquals("1.x", result.getString("package_version"));
            assertEquals("library", result.getString("dependency_name"));
            assertEquals("2.x", result.getString("dependency_version"));
            assertEquals("runtime", result.getString("dependency_type"));
            assertFalse(result.next());
        }
    }

    @Test
    void addDependencyIgnoresDuplicateRows() throws Exception {
        Path database = tempDir.resolve("workspace.db");
        var input =
                ImmutableAddDependencyInput.builder()
                        .name(new PackageMajorVersion("app", "1.x"))
                        .dependency(new PackageMajorVersion("library", "2.x"))
                        .type(DependencyType.PUBLIC)
                        .build();

        try (var storage = new WorkspaceStorage(database)) {
            storage.setup();
            storage.addDependency(input);
            storage.addDependency(input);
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM dependencies")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    @Test
    void getsDirectDependenciesForExactPackageAndType() throws Exception {
        Path database = tempDir.resolve("workspace.db");
        PackageMajorVersion app = new PackageMajorVersion("app", "1.x");
        PackageMajorVersion anotherAppVersion = new PackageMajorVersion("app", "2.x");

        try (var storage = new WorkspaceStorage(database)) {
            storage.setup();
            storage.addDependency(dependency(app, "alpha", "1.x", DependencyType.BUILD));
            storage.addDependency(dependency(app, "zeta", "2.x", DependencyType.PUBLIC));
            storage.addDependency(dependency(app, "zeta", "2.x", DependencyType.TEST));
            storage.addDependency(
                    dependency(anotherAppVersion, "other", "1.x", DependencyType.PUBLIC));

            assertEquals(
                    List.of(
                            dependency(app, "alpha", "1.x", DependencyType.BUILD),
                            dependency(app, "zeta", "2.x", DependencyType.PUBLIC),
                            dependency(app, "zeta", "2.x", DependencyType.TEST)),
                    storage.getDirectDependencies(app));
            assertEquals(
                    List.of(dependency(app, "zeta", "2.x", DependencyType.PUBLIC)),
                    storage.getDirectDependencies(app, DependencyType.PUBLIC));
            assertTrue(storage.getDirectDependencies(
                            new PackageMajorVersion("missing", "1.x"))
                    .isEmpty());
        }
    }

    private static ImmutableAddDependencyInput dependency(
            PackageMajorVersion name, String dependencyName, String dependencyVersion,
            DependencyType type) {
        return ImmutableAddDependencyInput.builder()
                .name(name)
                .dependency(new PackageMajorVersion(dependencyName, dependencyVersion))
                .type(type)
                .build();
    }
}
