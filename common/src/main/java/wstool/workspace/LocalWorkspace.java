package wstool.workspace;

import java.nio.file.Path;
import java.util.Objects;
import org.pkl.config.java.Config;
import org.pkl.config.java.ConfigEvaluator;
import org.pkl.core.ModuleSource;
import wstool.Distribution.DistributionConfig;

public class LocalWorkspace {
    private final Path root;
    private final DistributionConfig distribution;

    public LocalWorkspace(Path root) {
        this.root = Objects.requireNonNull(root);
        try (ConfigEvaluator evaluator = ConfigEvaluator.preconfigured()) {
            Path path = root.resolve("distribution").resolve("distribution.pkl");
            Config config = evaluator.evaluate(ModuleSource.path(path));
            this.distribution = config.get("distribution").as(DistributionConfig.class);
        }
    }

    public Path root() {
        return root;
    }

    public DistributionConfig distributionConfig() {
        return distribution;
    }
}
