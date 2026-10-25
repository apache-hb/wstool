package wstool.test.components;

import dev.parsick.testcontainers.gitserver.gitea.GiteaContainer;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
public class PackageGitManagerTest {
    @Container private static final GiteaContainer GITEA = PackageGitManager.container();

    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    Path tmp;

    private PackageGitManager git;

    @BeforeEach
    void beforeEach() {
        git = new PackageGitManager(tmp, GITEA);
    }

    @Test
    void testCreateRepository() throws Exception {
        URI uri = git.create("test", "package");

        git.run(tmp, "clone", uri.toString(), "cloned");
        for (String name : List.of("LICENSE", "main.c", "README.md")) {
            Assertions.assertEquals(
                    Files.readString(tmp.resolve("cloned").resolve(name)),
                    resourceAsString(getClass(), "/package/" + name));
        }
    }

    private static String resourceAsString(Class<?> cls, String name) {
        try (InputStream is = cls.getResourceAsStream(name)) {
            if (is == null) throw new FileNotFoundException(name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
