package wstool.simple;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import wstool.simple.git.GitCloneInput;
import wstool.simple.git.GitRepositoryLocation;
import wstool.simple.git.GitRunInput;

@Singleton
public class GitHelper {
    private static final String CREDENTIAL_HELPER =
            "!f() { if test \"$1\" = get; then printf 'username=%s\\npassword=%s\\n\\n' \"$WSTOOL_GIT_USERNAME\" \"$WSTOOL_GIT_PASSWORD\"; fi; }; f";

    private boolean interactive;

    @Inject
    public GitHelper() {
        this(false);
    }

    public GitHelper(boolean interactive) {
        this.interactive = interactive;
    }

    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    public void clone(GitCloneInput input) throws Exception {
        Files.createDirectories(input.parent());

        GitRepositoryLocation location = input.repository();

        List<String> args = new ArrayList<>();
        args.add("git");

        args.add("clone");
        args.add(location.repository().toString());

        if (location.branch() != null) {
            args.add("--branch");
            args.add(location.branch());
        }

        args.add(input.name());

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(input.parent().toFile());
        var env = pb.environment();

        putInteractiveOptions(env);
        putAuthenticationOptions(env, location.username(), location.password());

        execute(pb);
    }

    public void run(GitRunInput input) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(input.arguments());
        pb.directory(input.directory().toFile());

        var env = pb.environment();
        putInteractiveOptions(env);
        putAuthenticationOptions(env, input.username(), input.password());

        execute(pb);
    }

    private void execute(ProcessBuilder pb) throws Exception {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        byte[] output = process.getInputStream().readAllBytes();
        int status = process.waitFor();
        if (status != 0) {
            throw new RuntimeException(
                    "Git command failed with status "
                            + status
                            + ": "
                            + new String(output, StandardCharsets.UTF_8));
        }
    }

    private void putInteractiveOptions(Map<String, String> env) {
        if (!interactive) {
            env.remove("GIT_ASKPASS");
            env.remove("SSH_ASKPASS");
            env.put("GIT_TERMINAL_PROMPT", "0");
            env.put("GCM_INTERACTIVE", "never");
        }
    }

    private void putAuthenticationOptions(
            Map<String, String> env, String username, String password) {
        if (username != null || password != null) {
            env.put("WSTOOL_GIT_USERNAME", username != null ? username : "");
            env.put("WSTOOL_GIT_PASSWORD", password != null ? password : "");
            env.putAll(
                    buildGitOptions(
                            Map.of("credential.helper", CREDENTIAL_HELPER, "core.askPass", "")));
        }
    }

    private Map<String, String> buildGitOptions(Map<String, String> options) {
        Map<String, String> result = new HashMap<>();
        result.put("GIT_CONFIG_COUNT", String.valueOf(options.size()));
        int i = 0;
        for (Entry<String, String> entry : options.entrySet()) {
            result.put("GIT_CONFIG_KEY_" + i, entry.getKey());
            result.put("GIT_CONFIG_VALUE_" + i, entry.getValue());
            i += 1;
        }
        return result;
    }
}
