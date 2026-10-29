package wstool.cli.commands.packages;

import java.nio.file.Path;

public record UbuntuDebFile(String packageName, Path file, String checksum) {}
