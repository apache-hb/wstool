package wstool.cli.commands;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Option;

public abstract class CommonCommand implements Callable<Integer> {
    @Option(names = { "--cwd", "-C" }, required = false)
    protected Path cwd;

    public CommonCommand() {
        cwd = Path.of("");
    }
}
