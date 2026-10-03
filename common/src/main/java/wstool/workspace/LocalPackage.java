package wstool.workspace;

import com.google.common.base.Suppliers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;
import org.pkl.config.java.Config;
import wstool.Package;

public class LocalPackage {
    private final Path source;
    private final Path prefix;

    /** Supplied lazily to allow cloning package without a wstool.pkl file yet */
    private final Supplier<Package> packageConfig;

    public LocalPackage(Path source, Path prefix, PklHelper pkl) {
        this.source = Objects.requireNonNull(source);
        this.prefix = Objects.requireNonNull(prefix);

        this.packageConfig =
                Suppliers.memoize(
                        () -> {
                            Path path = source.resolve("wstool.pkl");
                            Config config = pkl.evaluate(path);
                            return config.as(Package.class);
                        });
    }

    public Path source() {
        return source;
    }

    public Path prefix() {
        return prefix;
    }

    public boolean hasPackageConfig() {
        return Files.exists(source.resolve("wstool.pkl"));
    }

    public Package packageConfig() {
        return packageConfig.get();
    }
}
