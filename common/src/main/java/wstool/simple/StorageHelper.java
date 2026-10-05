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
                prefix TEXT NOT NULL,
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

    public void addPackageToWorkspaceStorage(
            Path path,
            String name,
            String version,
            String source,
            String prefix,
            String build,
            boolean cloned)
            throws Exception {
        try (Connection connection = openConnection(path)) {
            try (var statement =
                    connection.prepareStatement(
                            """
                    INSERT INTO packages (name, version, source, prefix, build, cloned)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (name, version) DO UPDATE SET
                        source = excluded.source,
                        prefix = excluded.prefix,
                        build = excluded.build,
                        cloned = excluded.cloned
                    """)) {
                statement.setString(1, name);
                statement.setString(2, version);
                statement.setString(3, source);
                statement.setString(4, prefix);
                statement.setString(5, build);
                statement.setString(6, cloned ? "1" : "0");
                statement.executeUpdate();
            }
        }
    }

    private Connection openConnection(Path path) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + path);
    }
}
