package wstool.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class CliHelper {
    private final Path tmp;

    public CliHelper(Path tmp) {
        this.tmp = tmp;
    }

    public void checkCli(Path cwd, Map<String, String> environment, String... arguments)
            throws Exception {
        CliResult result = runCli(cwd, environment, arguments);
        assertSuccess(result);
    }

    public CliResult runCli(Path cwd, Map<String, String> environment, String... arguments)
            throws Exception {
        String classpath = System.getProperty("wstool.cli.classpath");
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("Missing wstool.cli.classpath test system property");
        }

        Path output = tmp.resolve("cli-output.txt");
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dwstool.version=integration-test");
        command.add("-cp");
        command.add(classpath);
        command.add("wstool.cli.Main");
        command.addAll(List.of(arguments));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        builder.environment().remove("PACKAGE_REPOSITORY");
        builder.environment().putAll(environment);

        Process process = builder.start();
        if (!process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("wstool CLI did not finish within two minutes");
        }
        return new CliResult(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
    }

    public static void assertSuccess(CliResult result) {
        assertEquals(0, result.exitCode(), result.output());
    }

    public static Path resourceDirectory(String resourceName) throws Exception {
        var resource = CliHelper.class.getResource(resourceName);
        if (resource == null) {
            throw new IOException("Missing test resource " + resourceName);
        }
        return Path.of(resource.toURI());
    }

    public record CliResult(int exitCode, String output) {}
}
