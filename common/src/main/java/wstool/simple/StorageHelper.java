package wstool.simple;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class StorageHelper {
    private static final String CREATE_WORKSPACE_TABLE =
            """
            CREATE TABLE IF NOT EXISTS workspace (
                name TEXT NOT NULL
            )
            """;

    private static final String CREATE_PACKAGES_TABLE =
            """
            CREATE TABLE IF NOT EXISTS packages (
                name TEXT NOT NULL,
                version TEXT NOT NULL,
                source TEXT NOT NULL,
                build TEXT NOT NULL,
                cloned TEXT NOT NULL CHECK (cloned IN ('0', '1')),
                PRIMARY KEY (name, version)
            )
            """;

    public void createWorkspaceStorage(Path path) throws Exception {
        try (Connection connection = openConnection(path)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(CREATE_WORKSPACE_TABLE);
                statement.execute(CREATE_PACKAGES_TABLE);
            }
        }
    }

    private Connection openConnection(Path path) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + path);
    }
}
