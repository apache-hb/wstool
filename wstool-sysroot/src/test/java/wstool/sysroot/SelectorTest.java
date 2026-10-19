package wstool.sysroot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.tudresden.inf.lat.jsexp.SexpFactory;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import wstool.common.distribution.DistributionConfig;
import wstool.common.distribution.DistributionFactory;
import wstool.common.distribution.DistributionPackage;
import wstool.common.distribution.ImmutableDistributionConfig;
import wstool.common.model.DependencyType;
import wstool.common.model.PackageDependency;
import wstool.common.model.PackageMajorVersion;
import wstool.common.workspace.LocalWorkspace;
import wstool.sysroot.closures.AggregateClosureFactory;
import wstool.sysroot.closures.DirectClosure;
import wstool.sysroot.closures.NamedClosure;
import wstool.sysroot.selectors.AggregateSelectorFactory;
import wstool.sysroot.selectors.ClosureSelector;
import wstool.sysroot.selectors.PathSelector;
import wstool.sysroot.selectors.PrefixSelector;
import wstool.sysroot.selectors.VersionSelector;

class SelectorTest {
    private static final Path TEST_OUTPUT_ROOT =
            Path.of("build/selector-test-output").toAbsolutePath();
    private static final PackageMajorVersion APP = new PackageMajorVersion("app", "1.0");
    private static final PackageMajorVersion RUNTIME = new PackageMajorVersion("runtime", "1.0");
    private static final PackageMajorVersion PUBLIC = new PackageMajorVersion("public", "1.0");
    private static final PackageMajorVersion BUILD = new PackageMajorVersion("build", "1.0");
    private static final PackageMajorVersion TEST = new PackageMajorVersion("test", "1.0");
    private static final PackageMajorVersion UNUSED = new PackageMajorVersion("unused", "1.0");

    private LocalWorkspace workspace;
    private Path outputRoot;
    private ClosureFactory closureFactory;
    private SelectorFactory selectorFactory;

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        outputRoot = TEST_OUTPUT_ROOT.resolve(testInfo.getTestMethod().orElseThrow().getName());
        deleteOutput();
        Path workspacePath = outputRoot.resolve("workspace");
        Files.createDirectories(workspacePath);
        DistributionFactory distributionFactory =
                new DistributionFactory(null) {
                    @Override
                    public DistributionConfig open(Path path) {
                        return ImmutableDistributionConfig.builder()
                                .platforms(Set.of("test"))
                                .addPackages(
                                        new DistributionPackage.GitRepository(
                                                RUNTIME, URI.create("file:///unused"), "main"))
                                .build();
                    }
                };
        workspace = new LocalWorkspace(workspacePath, distributionFactory, null, null);
        workspace.storage().setup();
        workspace
                .storage()
                .refresh(
                        List.of(APP, RUNTIME, PUBLIC, BUILD, TEST, UNUSED),
                        List.of(
                                dependency(APP, RUNTIME, DependencyType.RUNTIME),
                                dependency(APP, PUBLIC, DependencyType.PUBLIC),
                                dependency(APP, BUILD, DependencyType.BUILD),
                                dependency(APP, TEST, DependencyType.TEST),
                                dependency(APP, UNUSED, DependencyType.OVERRIDE),
                                dependency(RUNTIME, PUBLIC, DependencyType.PUBLIC),
                                dependency(BUILD, RUNTIME, DependencyType.RUNTIME)));
        createPackageFiles(APP, RUNTIME, PUBLIC, BUILD, TEST, UNUSED);

        closureFactory =
                new AggregateClosureFactory(
                        List.of(
                                new DirectClosure.DirectClosureFactory(),
                                new NamedClosure.NamedClosureFactory()));
        selectorFactory =
                new AggregateSelectorFactory(
                        List.of(
                                new PathSelector.PathSelectorFactory(closureFactory),
                                new ClosureSelector.ClosureSelectorFactory(closureFactory),
                                new PrefixSelector.PrefixSelectorFactory(),
                                new VersionSelector.VersionSelectorFactory()));
    }

    @Test
    void namedClosuresFollowDependencyKindsTransitively() throws Exception {
        assertEquals(Set.of(RUNTIME, PUBLIC), collect("runtime"));
        assertEquals(Set.of(BUILD, RUNTIME, PUBLIC), collect("build"));
        assertEquals(Set.of(RUNTIME, PUBLIC, BUILD, TEST), collect("development"));
        assertEquals(Set.of(RUNTIME, PUBLIC, BUILD, TEST), collect("test"));
    }

    @Test
    void buildClosureIncludesRuntimeRequirementsOfBuildDependencies() throws Exception {
        Closure closure =
                closureFactory
                        .parse(SexpFactory.parse("build"), new ClosureContext(workspace, APP))
                        .orElseThrow();
        assertEquals(Set.of(BUILD, RUNTIME, PUBLIC), closure.collect());
    }

    @Test
    void directClosureCombinesRequestedClosuresAndAlwaysIncludesRoot() throws Exception {
        Closure closure =
                closureFactory
                        .parse(
                                SexpFactory.parse("(direct build runtime)"),
                                new ClosureContext(workspace, APP))
                        .orElseThrow();
        assertEquals(Set.of(APP, BUILD, RUNTIME, PUBLIC), closure.collect());
        assertEquals(
                Set.of(APP),
                closureFactory
                        .parse(SexpFactory.parse("(direct)"), new ClosureContext(workspace, APP))
                        .orElseThrow()
                        .collect());
    }

    @Test
    void pathSelectorBuildsColonSeparatedPathsAndSupportsExplicitTarget() throws Exception {
        Selector current = selector("(path (direct))");
        assertEquals(workspace.source(APP).resolve("wstool/bin").toString(), current.select());

        Selector explicit = selector("(path (runtime) (runtime 1.0))");
        assertEquals(workspace.prefix(PUBLIC).resolve("bin").toString(), explicit.select());

        Selector composed = selector("(path (direct build))");
        assertEquals(
                workspace.source(APP).resolve("wstool/bin")
                        + File.pathSeparator
                        + workspace.prefix(BUILD).resolve("bin")
                        + File.pathSeparator
                        + workspace.prefix(RUNTIME).resolve("bin")
                        + File.pathSeparator
                        + workspace.prefix(PUBLIC).resolve("bin"),
                composed.select());
    }

    @Test
    void closureSelectorCreatesMergedWorkspaceCacheForEachClosureType() throws Exception {
        Path output = Path.of(selector("(closure runtime)").select());
        assertTrue(Files.isSymbolicLink(output.resolve("bin/runtime-tool")));
        assertTrue(Files.isSymbolicLink(output.resolve("bin/public-tool")));
        assertFalse(Files.exists(output.resolve("bin/build-tool")));
        assertFalse(Files.exists(output.resolve("bin/app-tool")));

        Path buildOutput = Path.of(selector("(closure build)").select());
        assertTrue(Files.isSymbolicLink(buildOutput.resolve("bin/build-tool")));
        assertTrue(Files.isSymbolicLink(buildOutput.resolve("bin/runtime-tool")));
        assertFalse(Files.exists(buildOutput.resolve("bin/test-tool")));

        Path combinedOutput = Path.of(selector("(closure (direct runtime))").select());
        assertTrue(Files.isSymbolicLink(combinedOutput.resolve("bin/app-tool")));
        assertTrue(Files.isSymbolicLink(combinedOutput.resolve("bin/runtime-tool")));
    }

    @Test
    void simpleSelectorsResolveVersionsAndPrefixes() throws Exception {
        assertEquals("1.0", selector("(version runtime)").select());
        assertEquals(
                workspace.prefix(RUNTIME).toAbsolutePath().toString(),
                selector("(prefix runtime)").select());
        assertEquals(
                workspace.prefix(RUNTIME).toAbsolutePath().toString(),
                selector("(prefix (runtime 1.0))").select());
    }

    @Test
    void rejectsUnknownClosureAndMalformedSelectors() throws Exception {
        assertTrue(
                closureFactory
                        .parse(SexpFactory.parse("unknown"), new ClosureContext(workspace, APP))
                        .isEmpty());
        assertThrows(IllegalArgumentException.class, () -> selector("(path unknown)"));
        assertThrows(
                IllegalArgumentException.class, () -> selector("(path (direct invalid))"));
    }

    private Set<PackageMajorVersion> collect(String name) throws Exception {
        return closureFactory
                .parse(SexpFactory.parse(name), new ClosureContext(workspace, APP))
                .orElseThrow()
                .collect();
    }

    private Selector selector(String expr) throws Exception {
        return selectorFactory
                .parse(SexpFactory.parse(expr), new SelectorContext(workspace, APP))
                .orElseThrow();
    }

    private void createPackageFiles(PackageMajorVersion... packages) throws Exception {
        for (PackageMajorVersion name : packages) {
            Files.createDirectories(workspace.prefix(name).resolve("bin"));
            Files.writeString(
                    workspace.prefix(name).resolve("bin").resolve(name.name() + "-tool"),
                    name.name());
        }
        Files.createDirectories(workspace.source(APP).resolve("wstool/bin"));
        Files.writeString(workspace.source(APP).resolve("wstool/bin/local-tool"), "local");
    }

    private static PackageDependency dependency(
            PackageMajorVersion name, PackageMajorVersion dependency, DependencyType type) {
        return new PackageDependency(name, dependency, type);
    }

    private void deleteOutput() throws IOException {
        if (Files.exists(outputRoot)) {
            try (var paths = Files.walk(outputRoot)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
