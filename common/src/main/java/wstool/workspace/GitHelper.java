package wstool.workspace;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GitHelper {
    private static final Logger LOGGER = LogManager.getLogger(GitHelper.class);

    private final String username;
    private final String password;

    public GitHelper(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public void clone(Path parent, URI repository, String branch, String name) throws Exception {
        Files.createDirectories(parent);
        run(parent, "clone", repository.toString(), "--branch", branch, name);
    }

    public void run(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory.toFile());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.remove("GIT_ASKPASS");
        env.remove("SSH_ASKPASS");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GCM_INTERACTIVE", "never");

        if (username != null || password != null) {
            String helper =
                    "!f() { if test \"$1\" = get; then printf 'username=%s\\npassword=%s\\n\\n' "
                            + "\"$WSTOOL_GIT_USERNAME\" \"$WSTOOL_GIT_PASSWORD\"; fi; }; f";
            env.put("WSTOOL_GIT_USERNAME", username == null ? "" : username);
            env.put("WSTOOL_GIT_PASSWORD", password == null ? "" : password);
            env.put("GIT_CONFIG_COUNT", "2");
            env.put("GIT_CONFIG_KEY_0", "credential.helper");
            env.put("GIT_CONFIG_VALUE_0", helper);
            env.put("GIT_CONFIG_KEY_1", "core.askPass");
            env.put("GIT_CONFIG_VALUE_1", "");
        }

        LOGGER.info("exec: {}", command);
        Process process = pb.start();
        byte[] output = process.getInputStream().readAllBytes();
        int status = process.waitFor();
        if (status != 0) {
            throw new IOException(
                    "Git command failed ("
                            + status
                            + "): "
                            + new String(output, StandardCharsets.UTF_8));
        }
    }
}
