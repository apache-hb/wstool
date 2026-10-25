package wstool.test.components;

import com.google.auto.service.AutoService;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Serializes integration tests on the ASUS UX303LA laptop. */
@AutoService(Extension.class)
public final class IntegrationTestExecutionPolicy
        implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {
    private static final String INTEGRATION_TAG = "integration";
    private static final String LOCK_KEY = "integration-lock";
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(IntegrationTestExecutionPolicy.class);
    private static final String TARGET_MODEL = "UX303LA";
    private static final Semaphore CLASS_LOCK = new Semaphore(1, true);
    private static final Semaphore TEST_LOCK = new Semaphore(1, true);
    private static final boolean SERIALIZE_INTEGRATION_TESTS =
            isTargetLaptop(MachineModel.current());

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (shouldSerialize(context)) {
            acquire(CLASS_LOCK);
            context.getStore(NAMESPACE).put(LOCK_KEY, true);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (context.getStore(NAMESPACE).remove(LOCK_KEY, Boolean.class) != null) {
            CLASS_LOCK.release();
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        if (shouldSerialize(context)) {
            acquire(TEST_LOCK);
            context.getStore(NAMESPACE).put(LOCK_KEY, true);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (context.getStore(NAMESPACE).remove(LOCK_KEY, Boolean.class) != null) {
            TEST_LOCK.release();
        }
    }

    private boolean shouldSerialize(ExtensionContext context) {
        if (!SERIALIZE_INTEGRATION_TESTS) {
            return false;
        }
        return context.getTags().contains(INTEGRATION_TAG)
                || context.getParent().map(this::shouldSerialize).orElse(false);
    }

    private static void acquire(Semaphore semaphore) throws Exception {
        try {
            semaphore.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExtensionConfigurationException(
                    "Interrupted while waiting to serialize integration tests", exception);
        }
    }

    private static boolean isTargetLaptop(String model) {
        String normalized = model.strip().toUpperCase(Locale.ROOT);
        return normalized.equals(TARGET_MODEL)
                || normalized.equals("ASUSTEK COMPUTER INC. " + TARGET_MODEL);
    }
}
