package wstool.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class PackageCommandParsingTest {
    @Test
    void parsesDirectTargetAndForwardsUnmatchedOptions() {
        CommandLine.ParseResult result =
                new CommandLine(Main.class).parseArgs("package", "release", "--jobs", "8");

        assertEquals(List.of("release", "--jobs", "8"), result.subcommand().unmatched());
    }

    @Test
    void parsesLegacyRunTargetAndForwardsUnmatchedOptions() {
        CommandLine.ParseResult result =
                new CommandLine(Main.class).parseArgs("package", "run", "release", "--jobs", "8");

        assertEquals(List.of(), result.subcommand().unmatched());
        assertEquals(
                List.of("release", "--jobs", "8"), result.subcommand().subcommand().unmatched());
    }
}
