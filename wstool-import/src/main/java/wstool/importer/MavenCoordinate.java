package wstool.importer;

import java.util.regex.Pattern;

public record MavenCoordinate(String group, String artifact, String version) {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_.+\\-]+");

    public MavenCoordinate {
        if (!VALID.matcher(group).matches()
                || !VALID.matcher(artifact).matches()
                || !VALID.matcher(version).matches()) {
            throw new IllegalArgumentException(
                    "Invalid Maven coordinate: " + group + ":" + artifact + ":" + version);
        }
    }

    public static MavenCoordinate parse(String value) {
        String[] parts = value.split(":", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Expected Maven coordinates in group:artifact:version form: " + value);
        }
        return new MavenCoordinate(parts[0], parts[1], parts[2]);
    }

    public String packageName() {
        return group.replace('.', '_') + "_" + artifact.replace('.', '_');
    }

    public String key() {
        return group + ":" + artifact + ":" + version;
    }

    public String repositoryPath() {
        return group.replace('.', '/') + "/" + artifact + "/" + version;
    }

    public String fileStem() {
        return artifact + "-" + version;
    }
}
