package wstool.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.pkl.config.java.ConfigEvaluatorBuilder;
import org.pkl.core.ModuleSource;
import org.pkl.core.module.ModuleKeyFactories;
import org.pkl.core.module.ModulePathResolver;
import wstool.Distribution;
import wstool.common.model.DistributionDescriptor;

/** Evaluates a distribution world.pkl file with Pkl. */
public final class DistributionReader {
    private static final String PACKAGE_ARCHIVE_RESOURCE = "/wstool-package.zip";

    private DistributionReader() {}

    public static DistributionDescriptor read(Path worldFile) {
        if (!Files.exists(worldFile)) {
            throw new IllegalArgumentException("No world.pkl found at " + worldFile);
        }
        var packageArchive = createPackageArchive();
        try (var modulePathResolver = new ModulePathResolver(List.of(packageArchive))) {
            var evaluatorBuilder = ConfigEvaluatorBuilder.preconfigured();
            evaluatorBuilder
                    .getEvaluatorBuilder()
                    .addModuleKeyFactory(ModuleKeyFactories.modulePath(modulePathResolver));
            try (var evaluator = evaluatorBuilder.build()) {
                return new DistributionDescriptor(
                        evaluator.evaluate(ModuleSource.path(worldFile)).as(Distribution.class),
                        worldFile.toAbsolutePath().normalize());
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unable to evaluate " + worldFile, e);
        } finally {
            try {
                Files.deleteIfExists(packageArchive);
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to remove package archive", e);
            }
        }
    }

    private static Path createPackageArchive() {
        try (var packageArchive =
                DistributionReader.class.getResourceAsStream(PACKAGE_ARCHIVE_RESOURCE)) {
            if (packageArchive == null) {
                throw new IllegalStateException(
                        "Missing generated Pkl package archive " + PACKAGE_ARCHIVE_RESOURCE);
            }
            var archive = Files.createTempFile("wstool-package-", ".zip");
            Files.copy(packageArchive, archive, StandardCopyOption.REPLACE_EXISTING);
            return archive;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create package archive", e);
        }
    }
}
