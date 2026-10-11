package wstool.importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import wstool.importer.MavenDependencyResolver.ResolvedArtifact;

/** Writes a checksummed source bundle and the Pkl entries that expose its Maven packages. */
public class PackageBundleWriter {
    public Path write(
            Map<MavenCoordinate, ResolvedArtifact> artifacts,
            Path outputDirectory,
            String baseUrl,
            String buildImage)
            throws Exception {
        Path tree = outputDirectory.resolve("package-tree");
        Path bundle = outputDirectory.resolve("wstool-maven-import.zip");
        ImportRoots.resetGeneratedTree(outputDirectory, "package-tree");
        Files.createDirectories(tree);
        for (ResolvedArtifact artifact : artifacts.values()) {
            writeArtifact(artifact, tree, buildImage);
        }
        zip(tree, bundle);
        String uri = artifactUri(baseUrl, bundle);
        String checksum = sha256(bundle);
        Files.writeString(
                outputDirectory.resolve("packages.pkl"), pklEntries(artifacts, uri, checksum));
        return bundle;
    }

    private void writeArtifact(ResolvedArtifact artifact, Path root, String buildImage)
            throws IOException {
        MavenCoordinate coordinate = artifact.coordinate();
        Path directory =
                root.resolve("packages")
                        .resolve(coordinate.packageName())
                        .resolve(MajorVersion.line(coordinate.version()));
        Path repositoryDirectory =
                directory.resolve("repository").resolve(coordinate.repositoryPath());
        Files.createDirectories(repositoryDirectory);
        copyOptional(
                artifact.binary(), repositoryDirectory.resolve(coordinate.fileStem() + ".jar"));
        copyOptional(artifact.pom(), repositoryDirectory.resolve(coordinate.fileStem() + ".pom"));
        for (var parent : artifact.supportPoms()) {
            Path parentPom =
                    directory
                            .resolve("repository")
                            .resolve(parent.coordinate().repositoryPath())
                            .resolve(parent.coordinate().fileStem() + ".pom");
            Files.createDirectories(parentPom.getParent());
            copy(parent.pom(), parentPom);
        }
        copyOptional(
                artifact.javadoc(),
                repositoryDirectory.resolve(coordinate.fileStem() + "-javadoc.jar"));
        copyOptional(
                artifact.sources(),
                repositoryDirectory.resolve(coordinate.fileStem() + "-sources.jar"));
        Files.writeString(directory.resolve("wstool.pkl"), packageConfig(artifact, buildImage));
    }

    private String packageConfig(ResolvedArtifact artifact, String buildImage) {
        StringBuilder pkl =
                new StringBuilder(
                                "amends \"package://pkl.konna.cloud/pkl/wstool@1.0.1#/package.pkl\"\n\n"
                                        + "package { name = \"")
                        .append(artifact.coordinate().packageName())
                        .append("\"; version = \"")
                        .append(MajorVersion.line(artifact.coordinate().version()))
                        .append("\" }\n\n")
                        .append("buildOptions {\n")
                        .append("  tool = \"maven-wstool-import\"\n")
                        .append("  dockerImage = \"")
                        .append(escape(buildImage))
                        .append("\"\n")
                        .append("}\n\n")
                        .append("buildDependencies { [\"maven-wstool-import\"] = \"1.0.0\" }\n");
        if (!artifact.dependencies().isEmpty()) {
            pkl.append("\ndependencies {\n");
            for (MavenCoordinate dependency : artifact.dependencies()) {
                pkl.append("  [\"")
                        .append(dependency.packageName())
                        .append("\"] = \"")
                        .append(MajorVersion.line(dependency.version()))
                        .append("\"\n");
            }
            pkl.append("}\n");
        }
        return pkl.toString();
    }

    private String pklEntries(
            Map<MavenCoordinate, ResolvedArtifact> artifacts, String uri, String checksum) {
        StringBuilder pkl =
                new StringBuilder(
                        "module wstool.importedMavenPackages\n\n"
                                + "import \"package://pkl.konna.cloud/pkl/wstool@1.0.1#/distribution.pkl\" as wstoolDistribution\n\n"
                                + "packages {\n");
        java.util.TreeMap<String, MavenCoordinate> packages = new java.util.TreeMap<>();
        for (MavenCoordinate coordinate : artifacts.keySet()) {
            packages.put(
                    coordinate.packageName() + "\u0000" + MajorVersion.line(coordinate.version()),
                    coordinate);
        }
        for (MavenCoordinate coordinate : packages.values()) {
            pkl.append("\nnew wstoolDistribution.Folder {\n")
                    .append("  name = \"")
                    .append(coordinate.packageName())
                    .append("\"\n")
                    .append("  version = \"")
                    .append(MajorVersion.line(coordinate.version()))
                    .append("\"\n")
                    .append("  from = new wstoolDistribution.Archive {\n")
                    .append("    name = \"wstool-maven-import\"\n")
                    .append("    version = \"1.0.0\"\n")
                    .append("    uri = \"")
                    .append(escape(uri))
                    .append("\"\n")
                    .append("    checksum = \"")
                    .append(checksum)
                    .append("\"\n")
                    .append("  }\n")
                    .append("  path = \"packages/")
                    .append(coordinate.packageName())
                    .append("/")
                    .append(MajorVersion.line(coordinate.version()))
                    .append("\"\n")
                    .append("}")
                    .append("\n");
        }
        pkl.append("}\n");
        return pkl.toString();
    }

    private String artifactUri(String baseUrl, Path bundle) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return bundle.toAbsolutePath().toUri().toString();
        }
        return baseUrl.endsWith("/")
                ? baseUrl + bundle.getFileName()
                : baseUrl + "/" + bundle.getFileName();
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void zip(Path source, Path archive) throws IOException {
        try (OutputStream output = Files.newOutputStream(archive);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            try (var paths = Files.walk(source)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    zip.putNextEntry(
                            new ZipEntry(source.relativize(path).toString().replace('\\', '/')));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        }
    }

    private void copyOptional(Path source, Path destination) throws IOException {
        if (source != null) {
            copy(source, destination);
        }
    }

    private void copy(Path source, Path destination) throws IOException {
        if (source == null || !Files.isRegularFile(source))
            throw new IOException("Resolved Maven artifact is missing: " + source);
        Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
