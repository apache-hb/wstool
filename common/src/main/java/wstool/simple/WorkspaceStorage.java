package wstool.simple;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import wstool.simple.model.PackageMajorVersion;
import wstool.simple.storage.AddDependencyInput;
import wstool.simple.storage.AddPackageInput;
import wstool.simple.storage.DependencyType;
import wstool.simple.storage.ImmutableAddDependencyInput;

public class WorkspaceStorage implements AutoCloseable {
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

    private static final String CREATE_DEPENDENCIES_TABLE =
            """
            CREATE TABLE IF NOT EXISTS dependencies (
                package_name TEXT NOT NULL,
                package_version TEXT NOT NULL,
                dependency_name TEXT NOT NULL,
                dependency_version TEXT NOT NULL,
                dependency_type TEXT NOT NULL CHECK (dependency_type IN ('build', 'public', 'runtime', 'test', 'remove', 'override')),
                PRIMARY KEY (package_name, package_version, dependency_name, dependency_version, dependency_type)
            )
            """;

    private static final String ADD_PACKAGE =
            """
            INSERT INTO packages (name, version, source, prefix, build, cloned)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (name, version) DO UPDATE SET
                source = excluded.source,
                prefix = excluded.prefix,
                build = excluded.build,
                cloned = excluded.cloned
            """;

    private static final String ADD_DEPENDENCY =
            """
            INSERT INTO dependencies (package_name, package_version, dependency_name, dependency_version, dependency_type)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (package_name, package_version, dependency_name, dependency_version, dependency_type) DO NOTHING
            """;

    private final Connection connection;

    public WorkspaceStorage(Path path) throws Exception {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + path);
    }

    public void setup() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_WORKSPACE_TABLE);
            statement.execute(CREATE_PACKAGES_TABLE);
            statement.execute(CREATE_DEPENDENCIES_TABLE);
        }
    }

    public void addPackage(AddPackageInput input) throws Exception {
        try (var statement = connection.prepareStatement(ADD_PACKAGE)) {
            statement.setString(1, input.name().name());
            statement.setString(2, input.name().version());
            statement.setString(3, input.source().toString());
            statement.setString(4, input.prefix().toString());
            statement.setString(5, input.build().toString());
            statement.setString(6, input.cloned() ? "1" : "0");
            statement.executeUpdate();
        }
    }

    public void addDependency(AddDependencyInput input) throws Exception {
        try (var statement = connection.prepareStatement(ADD_DEPENDENCY)) {
            statement.setString(1, input.name().name());
            statement.setString(2, input.name().version());
            statement.setString(3, input.dependency().name());
            statement.setString(4, input.dependency().version());
            statement.setString(5, input.type().name().toLowerCase());
            statement.executeUpdate();
        }
    }

    public List<AddDependencyInput> getDirectDependencies(
            PackageMajorVersion name) throws Exception {
        String query =
                "SELECT dependency_name, dependency_version, dependency_type "
                        + "FROM dependencies WHERE package_name = ? AND package_version = ? "
                        + "ORDER BY dependency_name, dependency_version, dependency_type";
        try (var statement = connection.prepareStatement(query)) {
            statement.setString(1, name.name());
            statement.setString(2, name.version());
            try (var result = statement.executeQuery()) {
                List<AddDependencyInput> dependencies = new ArrayList<>();
                while (result.next()) {
                    dependencies.add(
                            ImmutableAddDependencyInput.builder()
                                    .name(name)
                                    .dependency(
                                            new PackageMajorVersion(
                                                    result.getString("dependency_name"),
                                                    result.getString("dependency_version")))
                                    .type(
                                            DependencyType.valueOf(
                                                    result.getString("dependency_type")
                                                            .toUpperCase(Locale.ROOT)))
                                    .build());
                }
                return dependencies;
            }
        }
    }

    public List<AddDependencyInput> getDirectDependencies(
            PackageMajorVersion name, DependencyType type) throws Exception {
        return getDirectDependencies(name).stream()
                .filter(dependency -> dependency.type() == type)
                .toList();
    }

    @Override
    public void close() throws Exception {
        connection.close();
    }
}
