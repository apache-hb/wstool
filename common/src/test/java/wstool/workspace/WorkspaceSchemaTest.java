package wstool.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wstool.common.api.CodePackage;
import wstool.common.model.PackageMajorVersion;

class WorkspaceSchemaTest {
    @TempDir Path tempDir;

    @Test
    void createsDatabaseAndSchemaIfMissing() throws Exception {
        Path databasePath = tempDir.resolve(".wstool").resolve("workspace.db");

        new WorkspaceSchema(databasePath);

        assertTrue(Files.isRegularFile(databasePath));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                Statement statement = connection.createStatement();
                ResultSet tables =
                        statement.executeQuery(
                                "SELECT name FROM sqlite_master WHERE type = 'table'")) {
            Set<String> tableNames = new HashSet<>();
            while (tables.next()) {
                tableNames.add(tables.getString("name"));
            }
            assertTrue(tableNames.contains("workspace"));
            assertTrue(tableNames.contains("packages"));
        }

        new WorkspaceSchema(databasePath);
    }

    @Test
    void addsAndLoadsPackage() throws Exception {
        Path databasePath = tempDir.resolve(".wstool").resolve("workspace.db");
        WorkspaceSchema schema = new WorkspaceSchema(databasePath);
        PackageMajorVersion version = new PackageMajorVersion("example", "1.x");
        Path source = tempDir.resolve("src").resolve("example");

        schema.addPackage(version, source, true);

        CodePackage result = schema.getPackage(version);
        assertEquals(version, result.version());
        assertEquals(source, result.source());
        assertEquals(tempDir.resolve("build").resolve("example").resolve("1.x"), result.prefix());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                Statement statement = connection.createStatement();
                ResultSet packageRows =
                        statement.executeQuery(
                                "SELECT cloned FROM packages WHERE name = 'example' AND version = '1.x'")) {
            assertTrue(packageRows.next());
            assertEquals("1", packageRows.getString("cloned"));
        }
    }

    @Test
    void addingExistingPackageUpdatesItsPath() throws Exception {
        WorkspaceSchema schema = new WorkspaceSchema(tempDir.resolve("workspace.db"));
        PackageMajorVersion version = new PackageMajorVersion("example", "1.x");
        Path firstSource = tempDir.resolve("source-one");
        Path secondSource = tempDir.resolve("source-two");

        schema.addPackage(version, firstSource, false);
        schema.addPackage(version, secondSource, true);

        assertEquals(secondSource, schema.getPackage(version).source());
        try (Connection connection =
                        DriverManager.getConnection(
                                "jdbc:sqlite:" + tempDir.resolve("workspace.db"));
                Statement statement = connection.createStatement();
                ResultSet packageRows = statement.executeQuery("SELECT COUNT(*) FROM packages")) {
            assertTrue(packageRows.next());
            assertEquals(1, packageRows.getInt(1));
        }
    }

    @Test
    void returnsNullWhenPackageIsNotPresent() throws Exception {
        WorkspaceSchema schema = new WorkspaceSchema(tempDir.resolve("workspace.db"));

        assertNull(schema.getPackage(new PackageMajorVersion("missing", "1.x")));
    }
}
