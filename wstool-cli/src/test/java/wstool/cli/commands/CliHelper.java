package wstool.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import wstool.cli.Main;
import wstool.test.components.CliProcess;
import wstool.test.components.CliResult;

public final class CliHelper {
    private static final Logger LOGGER = LogManager.getLogger(CliHelper.class);
    private static final CliProcess CLI_PROCESS =
            new CliProcess(
                    Main.class,
                    "wstool.cli.classpath",
                    List.of("-Dwstool.version=integration-test"),
                    Duration.ofMinutes(20));

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
        CliResult result = CLI_PROCESS.run(tmp, cwd, environment, arguments);
        if (!result.error().isBlank()) {
            LOGGER.info("wstool CLI stderr: {}", result.error());
        }
        return result;
    }

    public static void assertSuccess(CliResult result) {
        assertEquals(0, result.exitCode(), result.output() + result.error());
    }

    public static Path resourceDirectory(String resourceName) throws Exception {
        var resource = CliHelper.class.getResource(resourceName);
        if (resource == null) {
            throw new IOException("Missing test resource " + resourceName);
        }
        return Path.of(resource.toURI());
    }
}
