package wstool.common.manifest;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

public class WorkspaceStorage {
    private static final String SCHEMA =
            """
            CREATE TABLE IF NOT EXISTS workspace (
                name TEXT NOT NULL
            ) STRICT;

            CREATE TABLE IF NOT EXISTS package (
                name TEXT NOT NULL,
                version TEXT NOT NULL,
                PRIMARY KEY (name, version)
            ) STRICT;
            """;

    private final Connection connection;

    public WorkspaceStorage(Path path) throws Exception {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + path);
    }

    public void setup() throws Exception {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA foreign_keys = ON");
            statement.executeUpdate(SCHEMA);
        }
    }
}
