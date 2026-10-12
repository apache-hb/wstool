package wstool.simple;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import org.pkl.config.java.Config;
import org.pkl.config.java.ConfigEvaluator;
import org.pkl.config.java.ConfigEvaluatorBuilder;
import org.pkl.core.ModuleSource;

import jakarta.inject.Singleton;

@Singleton
public class PklHelper implements AutoCloseable {
    private final ConfigEvaluator evaluator;

    public PklHelper(Map<String, String> env) {
        this.evaluator =
                ConfigEvaluatorBuilder.preconfigured()
                        .addEnvironmentVariables(
                                Objects.requireNonNullElseGet(env, Collections::emptyMap))
                        .build();
    }

    public Config evaluate(Path path) {
        return evaluator.evaluate(ModuleSource.path(path));
    }

    @Override
    public void close() throws Exception {
        evaluator.close();
    }
}
