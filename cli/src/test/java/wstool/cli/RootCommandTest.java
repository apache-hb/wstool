package wstool.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class RootCommandTest {
    @Test
    void helpIsAvailable() {
        CommandLine commandLine = new CommandLine(new RootCommand());

        assertEquals(0, commandLine.execute("--help"));
    }
}
