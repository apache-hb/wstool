package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wstool.simple.model.PackageMajorVersion;
import wstool.simple.storage.DependencyType;
import wstool.simple.storage.ImmutableAddDependencyInput;
import wstool.simple.storage.ImmutableAddPackageInput;

class WorkspaceStorageTest {
    @TempDir Path tempDir;

    @AutoClose WorkspaceStorage storage;

    Path database;

    @BeforeEach
    void beforeEach() throws Exception {
        database = tempDir.resolve("workspace.db");
        storage = new WorkspaceStorage(tempDir, database);
        storage.setup();
    }

    @Test
    void addDependencyPersistsPackageDependencyAndLowercaseType() throws Exception {
        var input =
                ImmutableAddDependencyInput.builder()
                        .name(new PackageMajorVersion("app", "1.x"))
                        .dependency(new PackageMajorVersion("library", "2.x"))
                        .type(DependencyType.RUNTIME)
                        .build();

        addPackages(
                storage,
                new PackageMajorVersion("app", "1.x"),
                new PackageMajorVersion("library", "2.x"));
        storage.addDependency(input);

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement();
                var result =
                        statement.executeQuery(
                                "SELECT package_name, package_version, dependency_name,"
                                        + " dependency_version, dependency_type FROM dependencies")) {
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
        var input =
                ImmutableAddDependencyInput.builder()
                        .name(new PackageMajorVersion("app", "1.x"))
                        .dependency(new PackageMajorVersion("library", "2.x"))
                        .type(DependencyType.PUBLIC)
                        .build();

        addPackages(
                storage,
                new PackageMajorVersion("app", "1.x"),
                new PackageMajorVersion("library", "2.x"));
        storage.addDependency(input);
        storage.addDependency(input);

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM dependencies")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    @Test
    void getsDirectDependenciesForExactPackageAndType() throws Exception {
        PackageMajorVersion app = new PackageMajorVersion("app", "1.x");
        PackageMajorVersion anotherAppVersion = new PackageMajorVersion("app", "2.x");

        addPackages(
                storage,
                app,
                anotherAppVersion,
                new PackageMajorVersion("alpha", "1.x"),
                new PackageMajorVersion("zeta", "2.x"),
                new PackageMajorVersion("other", "1.x"));
        storage.addDependency(dependency(app, "alpha", "1.x", DependencyType.BUILD));
        storage.addDependency(dependency(app, "zeta", "2.x", DependencyType.PUBLIC));
        storage.addDependency(dependency(app, "zeta", "2.x", DependencyType.TEST));
        storage.addDependency(dependency(anotherAppVersion, "other", "1.x", DependencyType.PUBLIC));

        assertEquals(
                List.of(
                        dependency(app, "alpha", "1.x", DependencyType.BUILD),
                        dependency(app, "zeta", "2.x", DependencyType.PUBLIC),
                        dependency(app, "zeta", "2.x", DependencyType.TEST)),
                storage.getDirectDependencies(app));
        assertEquals(
                List.of(dependency(app, "zeta", "2.x", DependencyType.PUBLIC)),
                storage.getDirectDependenciesByType(app, DependencyType.PUBLIC));
        assertTrue(
                storage.getDirectDependencies(new PackageMajorVersion("missing", "1.x")).isEmpty());
    }

    @Test
    void directDependenciesOfReturnsOnlyDependenciesOfRequestedType() throws Exception {
        PackageMajorVersion app = new PackageMajorVersion("app", "1.x");
        PackageMajorVersion otherApp = new PackageMajorVersion("app", "2.x");
        PackageMajorVersion compiler = new PackageMajorVersion("compiler", "1.x");
        PackageMajorVersion library = new PackageMajorVersion("library", "2.x");
        PackageMajorVersion otherLibrary = new PackageMajorVersion("other-library", "3.x");

        addPackages(storage, app, otherApp, compiler, library, otherLibrary);
        storage.addDependency(dependency(app, "compiler", "1.x", DependencyType.BUILD));
        storage.addDependency(dependency(app, "library", "2.x", DependencyType.PUBLIC));
        storage.addDependency(dependency(otherApp, "other-library", "3.x", DependencyType.BUILD));

        assertEquals(List.of(compiler), storage.directDependenciesOf(app, DependencyType.BUILD));
        assertEquals(List.of(library), storage.directDependenciesOf(app, DependencyType.PUBLIC));
        assertTrue(storage.directDependenciesOf(app, DependencyType.TEST).isEmpty());
    }

    @Test
    void findReturnsPackageWithMatchingNameAndVersion() throws Exception {
        PackageMajorVersion first = new PackageMajorVersion("library", "1.x");
        PackageMajorVersion second = new PackageMajorVersion("library", "2.x");
        Path firstSource = tempDir.resolve("first-source");
        Path secondSource = tempDir.resolve("second-source");

        storage.addPackage(packageInput(first, firstSource));
        storage.addPackage(packageInput(second, secondSource));

        LocalPackage pkg = storage.find(second);
        assertEquals(new LocalPackage(second, tempDir, secondSource), pkg);
        assertEquals(tempDir.resolve("private/prefix/library/2.x"), pkg.prefix());
        assertEquals(tempDir.resolve("private/build/library/2.x"), pkg.builddir());
    }

    @Test
    void findThrowsWhenPackageDoesNotExist() throws Exception {
        PackageMajorVersion missing = new PackageMajorVersion("missing", "1.x");

        assertThrows(NoSuchElementException.class, () -> storage.find(missing));
    }

    @Test
    void getsTransitiveDependenciesWithoutFollowingBuildDependencies() throws Exception {
        PackageMajorVersion app = new PackageMajorVersion("app", "1.x");
        PackageMajorVersion library = new PackageMajorVersion("library", "2.x");
        PackageMajorVersion runtimeLibrary = new PackageMajorVersion("runtime-library", "3.x");
        PackageMajorVersion testLibrary = new PackageMajorVersion("test-library", "4.x");

        addPackages(
                storage,
                app,
                library,
                new PackageMajorVersion("build-tool", "1.x"),
                new PackageMajorVersion("compile-tool", "1.x"),
                runtimeLibrary,
                testLibrary);
        storage.addDependency(dependency(app, "build-tool", "1.x", DependencyType.BUILD));
        storage.addDependency(dependency(app, "library", "2.x", DependencyType.PUBLIC));
        storage.addDependency(dependency(library, "compile-tool", "1.x", DependencyType.BUILD));
        storage.addDependency(
                dependency(library, "runtime-library", "3.x", DependencyType.RUNTIME));
        storage.addDependency(dependency(runtimeLibrary, "app", "1.x", DependencyType.PUBLIC));
        storage.addDependency(
                dependency(runtimeLibrary, "test-library", "4.x", DependencyType.TEST));

        assertEquals(
                List.of(library, runtimeLibrary, testLibrary), storage.getAllDependencies(app));
    }

    @Test
    void rejectsMultipleVersionsOfADirectDependency() throws Exception {
        PackageMajorVersion app = new PackageMajorVersion("app", "1.x");
        PackageMajorVersion libraryOne = new PackageMajorVersion("library", "1.x");
        PackageMajorVersion libraryTwo = new PackageMajorVersion("library", "2.x");
        PackageMajorVersion leafOne = new PackageMajorVersion("leaf", "1.x");
        PackageMajorVersion leafTwo = new PackageMajorVersion("leaf", "2.x");

        addPackages(storage, app, libraryOne, libraryTwo, leafOne, leafTwo);
        storage.addDependency(dependency(app, "library", "1.x", DependencyType.PUBLIC));
        storage.addDependency(dependency(app, "library", "2.x", DependencyType.PUBLIC));
        storage.addDependency(dependency(libraryOne, "leaf", "1.x", DependencyType.PUBLIC));
        storage.addDependency(dependency(libraryTwo, "leaf", "2.x", DependencyType.PUBLIC));

        assertThrows(IllegalStateException.class, () -> storage.getAllDependencies(app));
    }

    @Test
    void rejectsMultipleTransitiveVersionsOfSameDependency() throws Exception {
        PackageMajorVersion app = new PackageMajorVersion("app", "1.x");
        PackageMajorVersion firstBranch = new PackageMajorVersion("first-branch", "1.x");
        PackageMajorVersion secondBranch = new PackageMajorVersion("second-branch", "1.x");
        PackageMajorVersion sharedOne = new PackageMajorVersion("shared", "1.x");
        PackageMajorVersion sharedTwo = new PackageMajorVersion("shared", "2.x");

        addPackages(storage, app, firstBranch, secondBranch, sharedOne, sharedTwo);
        storage.addDependency(dependency(app, "first-branch", "1.x", DependencyType.PUBLIC));
        storage.addDependency(dependency(app, "second-branch", "1.x", DependencyType.PUBLIC));
        storage.addDependency(dependency(firstBranch, "shared", "1.x", DependencyType.PUBLIC));
        storage.addDependency(dependency(secondBranch, "shared", "2.x", DependencyType.PUBLIC));

        assertThrows(IllegalStateException.class, () -> storage.getAllDependencies(app));
    }

    @Test
    void overrideResolvesTransitiveVersionConflict() throws Exception {
        PackageMajorVersion app = new PackageMajorVersion("app", "1.x");
        PackageMajorVersion firstBranch = new PackageMajorVersion("first-branch", "1.x");
        PackageMajorVersion secondBranch = new PackageMajorVersion("second-branch", "1.x");
        PackageMajorVersion sharedOne = new PackageMajorVersion("shared", "1.x");
        PackageMajorVersion sharedTwo = new PackageMajorVersion("shared", "2.x");
        PackageMajorVersion leafOne = new PackageMajorVersion("leaf", "1.x");
        PackageMajorVersion leafTwo = new PackageMajorVersion("leaf", "2.x");

        addPackages(
                storage, app, firstBranch, secondBranch, sharedOne, sharedTwo, leafOne, leafTwo);
        storage.addDependency(dependency(app, "first-branch", "1.x", DependencyType.PUBLIC));
        storage.addDependency(dependency(app, "second-branch", "1.x", DependencyType.PUBLIC));
        storage.addDependency(dependency(app, "shared", "2.x", DependencyType.OVERRIDE));
        storage.addDependency(dependency(firstBranch, "shared", "1.x", DependencyType.PUBLIC));
        storage.addDependency(dependency(secondBranch, "shared", "2.x", DependencyType.PUBLIC));
        storage.addDependency(dependency(sharedOne, "leaf", "1.x", DependencyType.PUBLIC));
        storage.addDependency(dependency(sharedTwo, "leaf", "2.x", DependencyType.PUBLIC));

        assertEquals(
                List.of(firstBranch, leafTwo, secondBranch, sharedTwo),
                storage.getAllDependencies(app));
    }

    @Test
    void rejectsConflictingOverridesForTheSameDependency() throws Exception {
        PackageMajorVersion app = new PackageMajorVersion("app", "1.x");
        PackageMajorVersion libraryOne = new PackageMajorVersion("library", "1.x");
        PackageMajorVersion libraryTwo = new PackageMajorVersion("library", "2.x");

        addPackages(storage, app, libraryOne, libraryTwo);
        storage.addDependency(dependency(app, "library", "1.x", DependencyType.OVERRIDE));
        storage.addDependency(dependency(app, "library", "2.x", DependencyType.OVERRIDE));

        assertThrows(IllegalStateException.class, () -> storage.getAllDependencies(app));
    }

    @Test
    void addDependencyRequiresBothPackagesToExist() throws Exception {
        PackageMajorVersion app = new PackageMajorVersion("app", "1.x");
        PackageMajorVersion library = new PackageMajorVersion("library", "2.x");

        addPackages(storage, app);

        assertThrows(
                SQLException.class,
                () ->
                        storage.addDependency(
                                dependency(app, "library", "2.x", DependencyType.PUBLIC)));

        addPackages(storage, library);
        assertThrows(
                SQLException.class,
                () ->
                        storage.addDependency(
                                dependency(
                                        new PackageMajorVersion("missing", "1.x"),
                                        "library",
                                        "2.x",
                                        DependencyType.PUBLIC)));
    }

    private static ImmutableAddDependencyInput dependency(
            PackageMajorVersion name,
            String dependencyName,
            String dependencyVersion,
            DependencyType type) {
        return ImmutableAddDependencyInput.builder()
                .name(name)
                .dependency(new PackageMajorVersion(dependencyName, dependencyVersion))
                .type(type)
                .build();
    }

    private static void addPackages(WorkspaceStorage storage, PackageMajorVersion... packages)
            throws Exception {
        for (PackageMajorVersion name : packages) {
            storage.addPackage(packageInput(name, Path.of("source")));
        }
    }

    private static ImmutableAddPackageInput packageInput(PackageMajorVersion name, Path source) {
        return ImmutableAddPackageInput.builder().name(name).source(source).cloned(false).build();
    }
}
