package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class StorageHelperTest {
    @TempDir Path tempDir;

    StorageHelper helper;

    @BeforeEach
    void beforeEach() {
        helper = new StorageHelper();
    }

    @Test
    void createsWorkspaceDatabaseAndRequiredTables() throws Exception {
        Path database = tempDir.resolve("wstool.db");

        helper.createWorkspaceStorage(database);

        assertTrue(Files.exists(database));
        helper.addPackageToWorkspaceStorage(database, "foo", "1.x", "src", true);
    }

    @Test
    void canInitializeExistingDatabaseAgain() throws Exception {
        Path database = tempDir.resolve("wstool.db");

        helper.createWorkspaceStorage(database);
        helper.createWorkspaceStorage(database);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            assertTrue(tableExists(statement, "workspace"));
            assertTrue(tableExists(statement, "packages"));
        }
    }

    private static boolean tableExists(Statement statement, String name) throws Exception {
        try (ResultSet result =
                statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '"
                                + name
                                + "'")) {
            return result.next();
        }
    }

    private static int count(Statement statement, String insert) throws Exception {
        statement.execute(insert);
        try (ResultSet result = statement.executeQuery("SELECT changes()")) {
            result.next();
            return result.getInt(1);
        }
    }
}
