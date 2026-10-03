package wstool.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import wstool.common.api.CodePackage;
import wstool.common.model.PackageMajorVersion;

public class WorkspaceSchema {
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
                path TEXT NOT NULL,
                cloned TEXT NOT NULL CHECK (cloned IN ('0', '1')),
                PRIMARY KEY (name, version)
            )
            """;

    private static final String UPSERT_PACKAGE =
            """
            INSERT INTO packages (name, version, path, cloned)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (name, version) DO UPDATE SET
                path = excluded.path,
                cloned = excluded.cloned
            """;

    private static final String SELECT_PACKAGE =
            """
            SELECT name, version, path
            FROM packages
            WHERE name = ? AND version = ?
            """;

    private final Path databasePath;

    public WorkspaceSchema(Path path) throws IOException, SQLException {
        this.databasePath = Objects.requireNonNull(path).toAbsolutePath().normalize();
        Path parent = databasePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        createSchema();
    }

    public void addPackage(PackageMajorVersion version, Path packagePath, boolean cloned)
            throws SQLException {

        Objects.requireNonNull(version);
        Objects.requireNonNull(packagePath);
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(UPSERT_PACKAGE)) {
            statement.setString(1, version.name());
            statement.setString(2, version.version());
            statement.setString(3, packagePath.toString());
            statement.setString(4, cloned ? "1" : "0");
            statement.executeUpdate();
        }
    }

    public CodePackage getPackage(PackageMajorVersion version) throws SQLException {
        Objects.requireNonNull(version);

        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_PACKAGE)) {

            statement.setString(1, version.name());
            statement.setString(2, version.version());

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                PackageMajorVersion storedVersion =
                        new PackageMajorVersion(
                                result.getString("name"), result.getString("version"));
                Path source = Path.of(result.getString("path"));
                return new WorkspaceCodePackage(
                        storedVersion, source, packagePrefix(storedVersion));
            }
        }
    }

    private void createSchema() throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute(CREATE_WORKSPACE_TABLE);
                statement.execute(CREATE_PACKAGES_TABLE);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    private Path packagePrefix(PackageMajorVersion version) {
        Path databaseDirectory = databasePath.getParent();
        Path workspaceRoot = databaseDirectory;
        // Workspace state lives under <workspace>/.wstool, beside the workspace build directory.
        if (databaseDirectory != null
                && databaseDirectory.getFileName() != null
                && ".wstool".equals(databaseDirectory.getFileName().toString())) {
            workspaceRoot = databaseDirectory.getParent();
        }
        if (workspaceRoot == null) {
            workspaceRoot = Path.of("").toAbsolutePath().normalize();
        }
        return workspaceRoot.resolve("build").resolve(version.name()).resolve(version.version());
    }
}
