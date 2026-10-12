package wstool.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

class PackageCommandParsingTest {
    @ParameterizedTest
    @MethodSource("packageCommands")
    void parsesTargetAndForwardsUnmatchedOptions(List<String> command, boolean legacyRun) {
        CommandLine.ParseResult result =
                new CommandLine(Main.class).parseArgs(command.toArray(String[]::new));
        CommandLine.ParseResult target = result.subcommand();
        if (legacyRun) {
            assertEquals(List.of(), target.unmatched());
            target = target.subcommand();
        }
        assertEquals(List.of("release", "--jobs", "8"), target.unmatched());
    }

    static Stream<Arguments> packageCommands() {
        return Stream.of(
                Arguments.of(List.of("package", "release", "--jobs", "8"), false),
                Arguments.of(List.of("package", "run", "release", "--jobs", "8"), true));
    }
}
