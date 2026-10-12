package wstool.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.inject.Guice;
import com.google.inject.Injector;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;
import wstool.cli.inject.GuiceCommandFactory;
import wstool.cli.inject.WstoolModule;
import wstool.simple.PklHelper;

class PackageCommandParsingTest {
    private Injector injector;
    private PklHelper pklHelper;

    @BeforeEach
    void setUp() {
        injector = Guice.createInjector(new WstoolModule(Map.of()));
        pklHelper = injector.getInstance(PklHelper.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        pklHelper.close();
    }

    @ParameterizedTest
    @MethodSource("packageCommands")
    void parsesTargetAndForwardsUnmatchedOptions(List<String> command, boolean legacyRun) {
        CommandLine.ParseResult result =
                new CommandLine(new Main(), new GuiceCommandFactory(injector))
                        .parseArgs(command.toArray(String[]::new));
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
