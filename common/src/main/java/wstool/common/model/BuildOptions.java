package wstool.common.model;

public record BuildOptions(
        String networkIsolation, String sourceMount, String buildImage, String dockerImage) {
    public static BuildOptions defaults() {
        return new BuildOptions("isolated", "readonly", "declared", "scratch");
    }

    public String effectiveDockerImage() {
        return buildImage.equals("declared") ? "scratch" : dockerImage;
    }
}
