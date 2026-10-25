package wstool.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.fory.json.ForyJson;
import org.apache.fory.reflect.TypeRef;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import wstool.cli.Main;
import wstool.npm.importer.cli.NpmImportMain;
import wstool.test.components.CliProcess;
import wstool.test.components.CliResult;
import wstool.test.components.NpmRegistryManager;
import wstool.test.components.PackageGitManager;
import wstool.test.components.TestResources;

@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class CdktnInfrastructureEndToEndTest {
    private static final TypeRef<Map<String, Object>> JSON_OBJECT_TYPE = new TypeRef<>() {};
    private static final ForyJson JSON = ForyJson.builder().build();
    private static final Path OUTPUT_DIRECTORY =
            Path.of(System.getProperty("wstool.integration-tests.output-directory"))
                    .resolve("cdktn-infrastructure");
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("wstool.project-root"));
    private static final CliProcess WSTOOL =
            new CliProcess(Main.class, "wstool.cli.classpath", List.of());
    private static final CliProcess NPM_IMPORT =
            new CliProcess(
                    NpmImportMain.class,
                    "wstool.npm-import-cli.classpath",
                    List.of(),
                    Duration.ofMinutes(15));

    @Container
    private static final GenericContainer<?> NPM_REGISTRY = NpmRegistryManager.container();

    @Container private static final GiteaContainer GITEA = PackageGitManager.container();

    @Test
    void importsCdktnPackagesAndSynthesizesInfrastructureWithoutNpmDuringBuild() throws Exception {
        deleteDirectory(OUTPUT_DIRECTORY);
        Files.createDirectories(OUTPUT_DIRECTORY);
        PackageGitManager git = new PackageGitManager(OUTPUT_DIRECTORY, GITEA);
        Path index = OUTPUT_DIRECTORY.resolve("package-index");
        Files.createDirectories(index);

        Path infrastructureRepository =
                TestResources.copyDirectory(
                        getClass(),
                        "/cdktn-infrastructure",
                        OUTPUT_DIRECTORY.resolve("infrastructure-repository"));
        URI infrastructureRepositoryUri =
                createRepository(git, "infrastructure", infrastructureRepository);
        URI npmImportRepositoryUri =
                createRepository(
                        git,
                        "wstool-npm-import",
                        PROJECT_ROOT.resolve("packages/wstool-npm-import"));

        Path distribution =
                TestResources.copyDirectory(
                        getClass(),
                        "/cdktn-infrastructure-distribution",
                        OUTPUT_DIRECTORY.resolve("distribution"));
        URI distributionUri = createRepository(git, "distribution", distribution);

        Path wstoolBin =
                TestResources.copyDirectory(
                        getClass(), "/wstool-command", OUTPUT_DIRECTORY.resolve("wstool-bin"));
        Path wstoolExecutable = wstoolBin.resolve("wstool");
        assertTrue(wstoolExecutable.toFile().setExecutable(true));

        for (String packageSpecification :
                List.of(
                        "cdktn:0.24.0",
                        "@cdktn/provider-local:14.0.1",
                        "@cdktn/provider-random:15.0.1",
                        "constructs:10.7.2",
                        "typescript:5.9.3")) {
            CliResult npmImportResult =
                    NPM_IMPORT.run(
                            OUTPUT_DIRECTORY,
                            index,
                            Map.of(),
                            "--registry",
                            NpmRegistryManager.registryUri(NPM_REGISTRY).toString(),
                            packageSpecification);
            assertSuccess(npmImportResult);
            assertPackagesUseRegistry(
                    npmImportResult, NpmRegistryManager.registryUri(NPM_REGISTRY));
        }

        for (String packagePath :
                List.of(
                        "npm/cdktn/0.24.0",
                        "npm/@cdktn/provider-local/14.0.1",
                        "npm/@cdktn/provider-random/15.0.1",
                        "npm/constructs/10.7.2",
                        "npm/typescript/5.9.3")) {
            assertTrue(Files.isRegularFile(index.resolve(packagePath).resolve("wstool.pkl")));
        }

        URI indexRepositoryUri =
                createRepository(
                        git,
                        "package-index",
                        index,
                        Map.of(
                                Path.of("packages/npm-wstool"),
                                PROJECT_ROOT.resolve("packages/npm-wstool")));
        Map<String, String> environment = new HashMap<>();
        environment.put("PACKAGE_INDEX_REPOSITORY", indexRepositoryUri.toString());
        environment.put("WSTOOL_NPM_IMPORT_REPOSITORY", npmImportRepositoryUri.toString());
        environment.put("INFRASTRUCTURE_REPOSITORY", infrastructureRepositoryUri.toString());
        environment.put("WSTOOL_CLI_CLASSPATH", System.getProperty("wstool.cli.classpath"));
        environment.put("PATH", wstoolBin + File.pathSeparator + System.getenv("PATH"));
        CliResult workspaceResult =
                WSTOOL.run(
                        OUTPUT_DIRECTORY,
                        OUTPUT_DIRECTORY,
                        environment,
                        "workspace",
                        "create",
                        "--name",
                        "cdktn-infrastructure-workspace",
                        "--distribution",
                        distributionUri.toString());
        assertSuccess(workspaceResult);

        Path workspace = OUTPUT_DIRECTORY.resolve("cdktn-infrastructure-workspace");
        Path packageIndexSource = workspace.resolve("src/package-index");
        for (String packageName :
                List.of(
                        "npm-wstool",
                        "wstool-npm-import",
                        "cdktn",
                        "@cdktn/provider-local",
                        "@cdktn/provider-random",
                        "constructs",
                        "typescript",
                        "wstool-test-infrastructure")) {
            addPackage(workspace, packageName, environment);
        }

        runPackage(packageIndexSource.resolve("packages/npm-wstool"), environment);
        runPackage(workspace.resolve("src/wstool-npm-import"), environment);
        runPackage(packageIndexSource.resolve("npm/cdktn/0.24.0"), environment);
        runPackage(packageIndexSource.resolve("npm/@cdktn/provider-local/14.0.1"), environment);
        runPackage(packageIndexSource.resolve("npm/@cdktn/provider-random/15.0.1"), environment);
        runPackage(packageIndexSource.resolve("npm/constructs/10.7.2"), environment);
        runPackage(packageIndexSource.resolve("npm/typescript/5.9.3"), environment);
        runPackage(workspace.resolve("src/wstool-test-infrastructure"), environment);

        Path terraformJson =
                workspace.resolve(
                        "private/build/wstool-test-infrastructure/1.0.0/npm-project/cdktf.out/stacks/wstool-test-infrastructure/cdk.tf.json");
        assertTrue(Files.isRegularFile(terraformJson));
        Map<String, Object> synthesized =
                JSON.fromJson(Files.readString(terraformJson), JSON_OBJECT_TYPE);
        Map<String, Object> terraform = mapValue(synthesized, "terraform");
        Map<String, Object> requiredProviders = mapValue(terraform, "required_providers");
        assertEquals("hashicorp/random", mapValue(requiredProviders, "random").get("source"));
        assertEquals("hashicorp/local", mapValue(requiredProviders, "local").get("source"));
        Map<String, Object> providers = mapValue(synthesized, "provider");
        assertTrue(providers.containsKey("random"));
        assertTrue(providers.containsKey("local"));
        Map<String, Object> resource = mapValue(synthesized, "resource");
        Map<String, Object> randomStrings = mapValue(resource, "random_string");
        assertEquals(1, randomStrings.size());
        assertEquals(8, ((Number) mapValue(randomStrings, "bucketName").get("length")).intValue());
        Map<String, Object> localFiles = mapValue(resource, "local_file");
        assertEquals(1, localFiles.size());
        Map<String, Object> bucketDefinition = mapValue(localFiles, "bucketDefinition");
        Map<String, Object> definition =
                JSON.fromJson((String) bucketDefinition.get("content"), JSON_OBJECT_TYPE);
        assertTrue(((String) definition.get("bucket")).startsWith("wstool-test-infrastructure-"));
        List<?> lifecycleRules = listValue(definition, "lifecycleRules");
        assertEquals(2, lifecycleRules.size());
        Map<String, Object> expirationRule = mapValue(lifecycleRules.get(0));
        assertEquals("expire-temporary-objects", expirationRule.get("id"));
        assertEquals(Boolean.TRUE, expirationRule.get("enabled"));
        assertEquals("temporary/", expirationRule.get("prefix"));
        Map<String, Object> expiration = mapValue(expirationRule.get("expiration"));
        assertEquals(30, ((Number) expiration.get("days")).intValue());
        Map<String, Object> transitionRule = mapValue(lifecycleRules.get(1));
        assertEquals("archive-logs", transitionRule.get("id"));
        assertEquals("logs/", transitionRule.get("prefix"));
        Map<String, Object> transition = mapValue(listValue(transitionRule, "transition").get(0));
        assertEquals("GLACIER", transition.get("storageClass"));
    }

    private void addPackage(Path workspace, String name, Map<String, String> environment)
            throws Exception {
        assertSuccess(
                WSTOOL.run(
                        OUTPUT_DIRECTORY,
                        workspace,
                        environment,
                        "workspace",
                        "add",
                        "--package",
                        name));
    }

    private void runPackage(Path packageDirectory, Map<String, String> environment)
            throws Exception {
        assertSuccess(
                WSTOOL.run(OUTPUT_DIRECTORY, packageDirectory, environment, "package", "run"));
    }

    private URI createRepository(PackageGitManager git, String name, Path contents)
            throws Exception {
        String repository = name + "-" + UUID.randomUUID().toString().replace("-", "");
        return git.create(repository, contents, Map.of());
    }

    private URI createRepository(
            PackageGitManager git, String name, Path contents, Map<Path, Path> additionalFolders)
            throws Exception {
        String repository = name + "-" + UUID.randomUUID().toString().replace("-", "");
        return git.create(repository, contents, additionalFolders);
    }

    private void assertPackagesUseRegistry(CliResult result, URI registryUri) {
        for (String line : result.output().lines().toList()) {
            String[] fields = line.split("\\t", -1);
            assertEquals(3, fields.length, line);
            URI tarballUri = URI.create(fields[1]);
            assertEquals(
                    registryUri.getHost(),
                    tarballUri.getHost(),
                    "npm tarballs should use the local registry");
            assertEquals(registryUri.getPort(), tarballUri.getPort());
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new AssertionError("Expected a JSON object, got: " + value);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) map;
        return result;
    }

    private Map<String, Object> mapValue(Map<String, Object> parent, String key) {
        return mapValue(parent.get(key));
    }

    private List<?> listValue(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof List<?> list)) {
            throw new AssertionError("Expected a JSON array at " + key + ", got: " + value);
        }
        return list;
    }

    private void assertSuccess(CliResult result) {
        assertEquals(0, result.exitCode(), result.output() + result.error());
    }
}
