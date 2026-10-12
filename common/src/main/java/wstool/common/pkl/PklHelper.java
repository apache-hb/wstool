package wstool.common.pkl;

import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import org.pkl.config.java.Config;
import org.pkl.config.java.ConfigEvaluator;
import org.pkl.config.java.ConfigEvaluatorBuilder;
import org.pkl.core.ModuleSource;

@Singleton
public class PklHelper implements AutoCloseable {
    private final ConfigEvaluator evaluator;

    public PklHelper(Map<String, String> env) {
        env = Objects.requireNonNullElseGet(env, Collections::emptyMap);

        this.evaluator =
                ConfigEvaluatorBuilder.preconfigured().addEnvironmentVariables(env).build();
    }

    public Config evaluate(Path path) {
        return evaluator.evaluate(ModuleSource.path(path));
    }

    @Override
    public void close() throws Exception {
        evaluator.close();
    }
}
