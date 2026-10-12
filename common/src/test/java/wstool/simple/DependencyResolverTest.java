package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import software.amazon.awssdk.services.s3.S3Client;
import wstool.simple.git.ImmutableGitRepositoryLocation;
import wstool.simple.workspace.ImmutableAddPackageToWorkspaceInput;
import wstool.simple.workspace.ImmutableCreateWorkspaceInput;
import wstool.test.components.PackageGitManager;
import wstool.test.components.StorageServerManager;

@Testcontainers(disabledWithoutDocker = true)
class DependencyResolverTest {
    private static final String BUCKET = "dependency-resolver-test";
    private static final List<String> PACKAGE_NAMES =
            List.of(
                    "app",
                    "compiler",
                    "compiler-common",
                    "compiler-runtime",
                    "compiler-build-only",
                    "builder-script",
                    "builder-helper",
                    "public-api",
                    "shared",
                    "runtime-lib",
                    "test-framework",
                    "test-support",
                    "not-selected",
                    "alternate",
                    "alternate-runtime");
    private static final List<String> CLONED_PACKAGE_NAMES =
            List.of(
                    "app",
                    "compiler-runtime",
                    "builder-script",
                    "public-api",
                    "test-framework",
                    "alternate");

    @Container
    private static final GiteaContainer GITEA = PackageGitManager.container();

    @Container
    private static final GenericContainer<?> GARAGE = StorageServerManager.container();

    private static final StorageServerManager STORAGE = new StorageServerManager(GARAGE);

    @TempDir Path tempDir;

    private PackageGitManager gitManager;

    @AutoClose
    private static S3Client s3;

    @AutoClose private PklHelper pkl;
    private WorkspaceHelper workspaceHelper;
    private CacheHelper cache;
    private StorageHelper storage;
    private String repositorySuffix;

    @BeforeAll
    static void initializeStorageServer() throws Exception {
        STORAGE.setup();
        s3 = STORAGE.createBucket(BUCKET);
    }

    @BeforeEach
    void setUp() {
        gitManager = new PackageGitManager(tempDir, GITEA);

        String gitEndpoint = gitManager.endpoint().toString();
        repositorySuffix = UUID.randomUUID().toString().replace("-", "");
        pkl =
                new PklHelper(
                        Map.of(
                                "GIT_ENDPOINT", gitEndpoint,
                                "GIT_USERNAME", GITEA.getUserName(),
                                "REPOSITORY_SUFFIX", repositorySuffix));
        storage = new StorageHelper();
        workspaceHelper =
                new WorkspaceHelper(new GitHelper(false), storage, new DistributionHelper(pkl));

        cache = new CacheHelper(s3, new ArchiveHelper());
    }

    @Test
    void assemblesDevelopmentSysrootFromLocallyClonedAndCachedPackages() throws Exception {
        Path workspace = createWorkspace();
        DependencyResolver resolver = new DependencyResolver();

        Path sysroot = resolver.createClosure(workspaceHelper, workspace, "app", "development");

        for (String packageName :
                List.of(
                        "compiler",
                        "compiler-common",
                        "compiler-runtime",
                        "builder-script",
                        "builder-helper",
                        "public-api",
                        "shared",
                        "runtime-lib",
                        "test-framework",
                        "test-support")) {
            assertTrue(
                    Files.isSymbolicLink(sysroot.resolve("include").resolve(packageName + ".h")),
                    "Missing sysroot link for " + packageName);
            assertEquals(
                    "header from package\n",
                    Files.readString(sysroot.resolve("include").resolve(packageName + ".h")));
        }

        assertFalse(Files.exists(sysroot.resolve("include/not-selected.h")));
        assertFalse(Files.exists(sysroot.resolve("include/app.h")));
        Path compilerLink = sysroot.resolve("bin/compiler");
        assertTrue(Files.isSymbolicLink(compilerLink));
        assertEquals(
                workspaceHelper.artifactPackages(workspace).stream()
                        .filter(pkg -> pkg.version().name().equals("compiler"))
                        .findFirst()
                        .orElseThrow()
                        .prefix()
                        .resolve("bin/compiler")
                        .toAbsolutePath(),
                Files.readSymbolicLink(compilerLink));
        Path localPackageLink = sysroot.resolve("include/public-api.h");
        assertEquals(
                workspaceHelper.sourcePackages(workspace).stream()
                        .filter(pkg -> pkg.version().name().equals("public-api"))
                        .findFirst()
                        .orElseThrow()
                        .prefix()
                        .resolve("include/public-api.h")
                        .toAbsolutePath(),
                Files.readSymbolicLink(localPackageLink));
        assertTrue(Files.isSymbolicLink(sysroot.resolve("lib/libshared.so")));
        assertEquals("shared library\n", Files.readString(sysroot.resolve("lib/libshared.so")));
    }

    @Test
    void buildAndRuntimeSelectorsCollectTheirDocumentedGraphs() throws Exception {
        Path workspace = createWorkspace();
        DependencyResolver resolver = new DependencyResolver();

        Path build = resolver.createClosure(workspaceHelper, workspace, "app", "build");
        assertTrue(Files.exists(build.resolve("bin/compiler")));
        assertTrue(Files.exists(build.resolve("include/compiler-runtime.h")));
        assertTrue(Files.exists(build.resolve("include/builder-helper.h")));
        assertFalse(Files.exists(build.resolve("include/public-api.h")));
        assertFalse(Files.exists(build.resolve("include/test-framework.h")));

        Path runtime = resolver.createClosure(workspaceHelper, workspace, "app", "runtime");
        assertTrue(Files.exists(runtime.resolve("include/public-api.h")));
        assertTrue(Files.exists(runtime.resolve("include/runtime-lib.h")));
        assertTrue(Files.exists(runtime.resolve("include/shared.h")));
        assertFalse(Files.exists(runtime.resolve("bin/compiler")));
        assertFalse(Files.exists(runtime.resolve("include/test-framework.h")));
    }

    @Test
    void qualifiedSelectorChangesTheTraversalRoot() throws Exception {
        Path workspace = createWorkspace();
        DependencyResolver resolver = new DependencyResolver();

        Path sysroot =
                resolver.createClosure(workspaceHelper, workspace, "app", "alternate@2.0/runtime");

        assertTrue(Files.exists(sysroot.resolve("include/alternate-runtime.h")));
        assertFalse(Files.exists(sysroot.resolve("include/public-api.h")));
        assertTrue(sysroot.startsWith(workspace.resolve(".wstool/sysroots/alternate@2.0")));
    }

    private Path createWorkspace() throws Exception {
        String distributionRepositoryName = "distribution-" + repositorySuffix;
        URI distributionRepository =
                uploadRepository("dependency-resolver/distribution", distributionRepositoryName);
        for (String packageName : CLONED_PACKAGE_NAMES) {
            uploadRepository(
                    "dependency-resolver/" + packageName, packageName + "-" + repositorySuffix);
        }

        Path parent = tempDir.resolve("workspaces");
        Path workspace = parent.resolve("dependency-resolver");
        workspaceHelper.createWorkspace(
                ImmutableCreateWorkspaceInput.builder()
                        .parent(parent)
                        .name("dependency-resolver")
                        .distribution(
                                ImmutableGitRepositoryLocation.builder()
                                        .repository(distributionRepository)
                                        .branch("master")
                                        .build())
                        .build());

        for (String packageName : CLONED_PACKAGE_NAMES) {
            workspaceHelper.addPackageToWorkspace(
                    ImmutableAddPackageToWorkspaceInput.builder()
                            .workspace(workspace)
                            .name(packageName)
                            .version(packageVersion(packageName))
                            .build());
        }

        for (String packageName : PACKAGE_NAMES) {
            String version = packageVersion(packageName);
            Path artifact = createArtifact(packageName, version);
            String artifactKey = artifactKey(packageName, version);
            cache.uploadFolder(BUCKET, artifactKey, artifact);

            Path cachedPackage =
                    workspaceHelper.workspaceCacheFolder(workspace, packageName).resolve(version);
            Files.createDirectories(cachedPackage.getParent());
            cache.downloadFolder(BUCKET, artifactKey, cachedPackage);

            boolean cloned = CLONED_PACKAGE_NAMES.contains(packageName);
            Path source =
                    cloned
                            ? workspaceHelper.workspacePackageFolder(workspace, packageName)
                            : cachedPackage.resolve("source");
            Path prefix = cachedPackage.resolve("prefix");
            storage.addPackageToWorkspaceStorage(
                    workspace.resolve("wstool.db"),
                    packageName,
                    version,
                    source.toString(),
                    prefix.toString(),
                    workspaceHelper.workspaceBuildFolder(workspace, packageName).toString(),
                    cloned);
        }
        return workspace;
    }

    private Path createArtifact(String packageName, String version) throws Exception {
        Path artifact = tempDir.resolve("artifacts").resolve(repositorySuffix).resolve(packageName);
        Path source = artifact.resolve("source");
        Path prefix = artifact.resolve("prefix");
        Files.createDirectories(source);
        Files.copy(
                resourcePath("dependency-resolver/" + packageName + "/wstool.pkl"),
                source.resolve("wstool.pkl"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectories(prefix.resolve("include"));
        Files.copy(
                resourcePath("dependency-resolver/artifacts/include/package.h"),
                prefix.resolve("include").resolve(packageName + ".h"),
                StandardCopyOption.REPLACE_EXISTING);
        if (packageName.equals("compiler")) {
            Files.createDirectories(prefix.resolve("bin"));
            Files.copy(
                    resourcePath("dependency-resolver/artifacts/bin/compiler"),
                    prefix.resolve("bin/compiler"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        if (packageName.equals("shared")) {
            Files.createDirectories(prefix.resolve("lib"));
            Files.copy(
                    resourcePath("dependency-resolver/shared/libshared-real.so"),
                    prefix.resolve("lib/libshared-real.so"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.createSymbolicLink(
                    prefix.resolve("lib/libshared.so"), Path.of("libshared-real.so"));
        }
        return artifact;
    }

    private String packageVersion(String packageName) {
        return "alternate".equals(packageName) ? "2.0" : "1.0";
    }

    private String artifactKey(String packageName, String version) {
        return "dependency-resolver/" + repositorySuffix + "/" + packageName + "/" + version;
    }

    private URI uploadRepository(String fixture, String repositoryName) throws Exception {
        URI repository = gitManager.create(repositoryName);
        gitManager.uploadResources(repositoryName, fixture);
        return repository;
    }

    private static Path resourcePath(String name) throws Exception {
        URL resource = DependencyResolverTest.class.getClassLoader().getResource(name);
        if (resource == null) {
            throw new IOException("Test resource not found: " + name);
        }
        return Path.of(resource.toURI());
    }
}
