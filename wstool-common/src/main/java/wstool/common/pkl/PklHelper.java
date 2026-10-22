package wstool.common.pkl;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.pkl.config.java.Config;
import org.pkl.config.java.ConfigEvaluator;
import org.pkl.config.java.ConfigEvaluatorBuilder;
import org.pkl.core.ModuleSource;

@Singleton
public class PklHelper implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger(PklHelper.class);

    private final ConfigEvaluator evaluator;

    public PklHelper(Map<String, String> env) {
        env = Objects.requireNonNullElseGet(env, Collections::emptyMap);

        this.evaluator =
                ConfigEvaluatorBuilder.preconfigured().addEnvironmentVariables(env).build();
        LOGGER.debug("Environment: {}", env);
    }

    public Config evaluate(Path path) {
        return evaluator.evaluate(ModuleSource.path(path));
    }

    @Override
    public void close() throws Exception {
        evaluator.close();
    }
}
