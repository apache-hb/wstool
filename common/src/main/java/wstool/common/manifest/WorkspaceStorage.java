package wstool.common.manifest;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;

import wstool.common.distribution.DistributionConfig;
import wstool.common.model.DependencyType;
import wstool.common.model.PackageMajorVersion;

public class WorkspaceStorage {
    private static final String SCHEMA =
            """
            CREATE TABLE IF NOT EXISTS workspace (
                name TEXT NOT NULL
            ) STRICT;

            --- Information about a package
            CREATE TABLE IF NOT EXISTS package (
                name TEXT NOT NULL,
                version TEXT NOT NULL,
                PRIMARY KEY (name, version)
            ) STRICT;

            --- Dependency links between packages
            CREATE TABLE IF NOT EXISTS dependency (
                name TEXT NOT NULL,
                version TEXT NOT NULL,
                dependency_name TEXT NOT NULL,
                dependency_version TEXT NOT NULL,
                type TEXT NOT NULL CHECK (type IN ('public', 'test', 'runtime', 'override', 'remove', 'build')),
                PRIMARY KEY (name, version, dependency_name, dependency_version, type),
                FOREIGN KEY (name, version) REFERENCES package(name, version) ON DELETE CASCADE
            ) STRICT;
            """;

    private static final String ADD_PACKAGE =
            """
            INSERT INTO package (name, version)
            VALUES (?, ?)
            """;

    private static final String ADD_DEPENDENCY =
            """
            INSERT INTO dependency (name, version, dependency_name, dependency_version, type)
            VALUES (?, ?, ?, ?, ?)
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

    public void refresh(DistributionConfig config) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM dependency");
            statement.executeUpdate("DELETE FROM package");
        }

        for (var pkg : config.packages()) {
            addPackage(pkg.name());
        }
    }

    public void addPackage(PackageMajorVersion name) throws Exception {
        try (var statement = connection.prepareStatement(ADD_PACKAGE)) {
            statement.setString(1, name.name());
            statement.setString(2, name.version());
            statement.executeUpdate();
        }
    }

    public void addDependency(PackageMajorVersion name, PackageMajorVersion dependency, DependencyType type) throws Exception {
        try (var statement = connection.prepareStatement(ADD_DEPENDENCY)) {
            statement.setString(1, name.name());
            statement.setString(2, name.version());
            statement.setString(3, dependency.name());
            statement.setString(4, dependency.version());
            statement.setString(5, type.name().toLowerCase(Locale.ROOT));
            statement.executeUpdate();
        }
    }
}
