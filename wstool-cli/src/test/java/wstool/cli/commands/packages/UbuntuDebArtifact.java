package wstool.cli.commands.packages;

import java.nio.file.Path;

public record UbuntuDebArtifact(
        String packageName,
        String version,
        Path dataArchive,
        String dataChecksum,
        Path controlFile,
        String controlChecksum) {}
