package wstool.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Installs the locally generated wstool Pkl package into a test user's package cache. */
public final class PklPackageCache {
    private PklPackageCache() {}

    public static void install(Path userHome) throws IOException {
        Path packageDirectory =
                userHome.resolve(".pkl/cache/package-2/pkl.konna.cloud/pkl/wstool@1.0.2");
        Files.createDirectories(packageDirectory);
        Path generatedPackage = Path.of("../config/build/generated/pkl/packages");
        Files.copy(
                generatedPackage.resolve("wstool@1.0.2"),
                packageDirectory.resolve("wstool@1.0.2.json"));
        Files.copy(
                generatedPackage.resolve("wstool@1.0.2.zip"),
                packageDirectory.resolve("wstool@1.0.2.zip"));
    }
}
