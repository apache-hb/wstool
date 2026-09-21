package wstool.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import org.pkl.config.java.ConfigEvaluatorBuilder;
import org.pkl.core.ModuleSource;
import org.pkl.core.module.ModuleKeyFactories;
import org.pkl.core.module.ModulePathResolver;
import wstool.Package;
import wstool.common.model.BuildOptions;
import wstool.common.model.ImportOptions;
import wstool.common.model.PackageDescriptor;
import wstool.common.model.PackageVersion;

/** Evaluates a package descriptor with Pkl and adapts it to the build model. */
public final class PackageDescriptorReader {
    private static final String PACKAGE_ARCHIVE_RESOURCE = "/wstool-package.zip";

    private PackageDescriptorReader() {}

    public static PackageDescriptor read(Path packageDirectory) {
        var file = packageDirectory.resolve("wstool.pkl");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("No wstool.pkl found in " + packageDirectory);
        }

        var packageArchive = createPackageArchive();
        try (var modulePathResolver = new ModulePathResolver(List.of(packageArchive))) {
            var evaluatorBuilder = ConfigEvaluatorBuilder.preconfigured();
            evaluatorBuilder
                    .getEvaluatorBuilder()
                    .addModuleKeyFactory(ModuleKeyFactories.modulePath(modulePathResolver));
            try (var evaluator = evaluatorBuilder.build()) {
                var source = ModuleSource.path(file);
                var descriptor = evaluator.evaluate(source).as(Package.class);
                return new PackageDescriptor(
                        new PackageVersion(
                                descriptor.getPackage().getName(),
                                descriptor.getPackage().getVersion()),
                        toBuildOptions(descriptor.getBuildOptions()),
                        toImportOptions(descriptor.getImportOptions()),
                        descriptor.getBuildTool(),
                        valuesOrEmpty(descriptor.getBuildDependencies()),
                        valuesOrEmpty(descriptor.getDependencies()),
                        valuesOrEmpty(descriptor.getTestDependencies()),
                        valuesOrEmpty(descriptor.getRuntimeDependencies()),
                        valuesOrEmpty(descriptor.getOverrideDependencies()),
                        packageDirectory);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unable to evaluate " + file, e);
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
                PackageDescriptorReader.class.getResourceAsStream(PACKAGE_ARCHIVE_RESOURCE)) {
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

    private static BuildOptions toBuildOptions(Package.BuildOptions options) {
        if (options == null) return BuildOptions.defaults();
        return new BuildOptions(
                options.getNetworkIsolation().toString(),
                options.getSourceMount().toString(),
                options.getBuildImage().toString(),
                options.getDockerImage());
    }

    private static ImportOptions toImportOptions(Package.ImportOptions options) {
        if (options == null) return null;
        return new ImportOptions(options.getRepo(), options.getLicense());
    }

    private static Map<String, String> valuesOrEmpty(Map<String, String> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
