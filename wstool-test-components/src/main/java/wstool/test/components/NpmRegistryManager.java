package wstool.test.components;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.fory.json.ForyJson;
import org.apache.fory.reflect.TypeRef;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Starts an ephemeral Verdaccio npm registry and publishes test package fixtures to it. */
public final class NpmRegistryManager {
    private static final int REGISTRY_PORT = 4873;
    private static final TypeRef<Map<String, Object>> JSON_OBJECT_TYPE = new TypeRef<>() {};
    private static final ForyJson JSON = ForyJson.builder().build();

    private NpmRegistryManager() {}

    public static GenericContainer<?> container() {
        GenericContainer<?> container =
                new GenericContainer<>(DockerImageName.parse("verdaccio/verdaccio:6.1.6"));
        container.withExposedPorts(REGISTRY_PORT);
        container.withCopyToContainer(
                MountableFile.forClasspathResource("npm-registry/verdaccio.yaml"),
                "/tmp/wstool-verdaccio.yaml");
        container.withCommand(
                "verdaccio",
                "--config",
                "/tmp/wstool-verdaccio.yaml",
                "--listen",
                "0.0.0.0:" + REGISTRY_PORT);
        container.waitingFor(Wait.forHttp("/-/ping").forPort(REGISTRY_PORT).forStatusCode(200));
        container.withStartupTimeout(Duration.ofMinutes(2));
        return TestContainerLimits.apply(container, 384L * 1024 * 1024, "1G");
    }

    public static URI registryUri(GenericContainer<?> container) {
        return URI.create(
                "http://"
                        + container.getHost()
                        + ":"
                        + container.getMappedPort(REGISTRY_PORT)
                        + "/");
    }

    public static void publishPackage(
            GenericContainer<?> container, Path packageDirectory, Path npmConfigDirectory)
            throws Exception {
        Files.createDirectories(npmConfigDirectory);
        Path userConfig = npmConfigDirectory.resolve(".npmrc");
        if (!Files.isRegularFile(userConfig)) {
            registerUser(container, userConfig);
        }
        Process process =
                new ProcessBuilder(
                                "npm",
                                "publish",
                                "--silent",
                                "--ignore-scripts",
                                "--registry",
                                registryUri(container).toString(),
                                "--userconfig",
                                userConfig.toString())
                        .directory(packageDirectory.toFile())
                        .inheritIO()
                        .start();
        if (!process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("npm publish did not finish within two minutes");
        }
        if (process.exitValue() != 0) {
            throw new IOException(
                    "npm publish failed with exit code "
                            + process.exitValue()
                            + " for "
                            + packageDirectory);
        }
    }

    private static void registerUser(GenericContainer<?> container, Path userConfig)
            throws Exception {
        Map<String, Object> requestBody =
                Map.of(
                        "name", "wstool-test",
                        "password", "wstool-test-password",
                        "email", "wstool-test@example.test",
                        "type", "user",
                        "roles", List.of(),
                        "date", Instant.now().toString());
        HttpRequest request =
                HttpRequest.newBuilder(
                                registryUri(container)
                                        .resolve("-/user/org.couchdb.user:wstool-test"))
                        .header("Content-Type", "application/json")
                        .PUT(
                                HttpRequest.BodyPublishers.ofString(
                                        JSON.toJson(requestBody, JSON_OBJECT_TYPE)))
                        .build();
        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                    "Could not register the npm test user: "
                            + response.statusCode()
                            + " "
                            + response.body());
        }
        Map<String, Object> tokenResponse = JSON.fromJson(response.body(), JSON_OBJECT_TYPE);
        Object token = tokenResponse.get("token");
        if (!(token instanceof String value) || value.isBlank()) {
            throw new IOException("Verdaccio returned no token for the npm test user");
        }
        String authority = container.getHost() + ":" + container.getMappedPort(REGISTRY_PORT);
        Files.writeString(
                userConfig,
                "registry="
                        + registryUri(container)
                        + System.lineSeparator()
                        + "//"
                        + authority
                        + "/:_authToken="
                        + value
                        + System.lineSeparator());
    }
}
