package wstool.test.components;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs a Java CLI in a subprocess using the classpath supplied by its test task. */
public final class CliProcess {
    private final Class<?> mainClass;
    private final String classpathProperty;
    private final List<String> javaOptions;

    public CliProcess(Class<?> mainClass, String classpathProperty, List<String> javaOptions) {
        this.mainClass = mainClass;
        this.classpathProperty = classpathProperty;
        this.javaOptions = List.copyOf(javaOptions);
    }

    public CliResult run(
            Path outputDirectory,
            Path workingDirectory,
            Map<String, String> environment,
            String... arguments)
            throws Exception {
        String classpath = System.getProperty(classpathProperty);
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("Missing test property: " + classpathProperty);
        }

        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve("cli-output.txt");
        Path error = outputDirectory.resolve("cli-error.txt");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(javaOptions);
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass.getName());
        command.addAll(List.of(arguments));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectOutput(output.toFile());
        builder.redirectError(error.toFile());
        builder.environment().putAll(environment);

        Process process = builder.start();
        if (!process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException(
                    "CLI process did not finish within two minutes: " + mainClass.getName());
        }
        return new CliResult(
                process.exitValue(),
                Files.readString(output, StandardCharsets.UTF_8),
                Files.readString(error, StandardCharsets.UTF_8));
    }
}
