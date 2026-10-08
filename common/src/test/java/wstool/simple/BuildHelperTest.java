package wstool.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wstool.simple.packages.ImmutableRunTargetInput;

class BuildHelperTest {
    @TempDir Path tempDir;

    @Test
    void runsConfiguredToolWithTargetArgumentsAndPackageEnvironment() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path source = workspace.resolve("src/example");
        Files.createDirectories(source);
        copyResource("build-helper/wstool.pkl", source.resolve("wstool.pkl"));
        Path script = source.resolve("capture.sh");
        copyResource("build-helper/capture.sh", script);

        new BuildHelper(new PackageHelper(new PklHelper(null)))
                .runTarget(
                        ImmutableRunTargetInput.builder()
                                .workspace(workspace)
                                ._package(source)
                                .args(List.of(script.toString(), "target", "extra argument"))
                                .build());

        Path log = source.resolve("invocation.txt");
        assertTrue(Files.exists(log));
        List<String> lines = Files.readAllLines(log);
        assertEquals(source.toString(), lines.get(0));
        assertEquals(source.toString(), lines.get(1));
        assertEquals(workspace.resolve("private/build/build-helper").toString(), lines.get(2));
        assertEquals(workspace.resolve("private/prefix/build-helper").toString(), lines.get(3));
        assertEquals("target", lines.get(4));
        assertEquals("extra argument", lines.get(5));
    }

    private void copyResource(String resource, Path destination) throws Exception {
        try (var input = BuildHelperTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing test resource: " + resource);
            }
            Files.copy(input, destination);
        }
    }
}
