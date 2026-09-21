package wstool.common.model;

public record BuildOptions(String networkIsolation, String sourceMount, String buildImage) {
    public static BuildOptions defaults() {
        return new BuildOptions("isolated", "readonly", "declared");
    }
}
