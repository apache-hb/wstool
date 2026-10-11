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

/** Writes package metadata and Pkl entries that expose files from a Maven repository. */
public class PackageBundleWriter {
    public Path write(
            Map<MavenCoordinate, ResolvedArtifact> artifacts,
            Path outputDirectory,
            String repositoryUrl,
            String baseUrl)
            throws Exception {
        Path tree = outputDirectory.resolve("package-tree");
        Path bundle = outputDirectory.resolve("wstool-maven-import.zip");
        ImportRoots.resetGeneratedTree(outputDirectory, "package-tree");
        Files.createDirectories(tree);
        for (ResolvedArtifact artifact : artifacts.values()) {
            writeArtifact(artifact, tree, repositoryUrl);
        }
        zip(tree, bundle);
        Files.writeString(
                outputDirectory.resolve("packages.pkl"),
                pklEntries(artifacts, bundle, artifactUri(baseUrl, bundle)));
        return bundle;
    }

    private void writeArtifact(ResolvedArtifact artifact, Path root, String repositoryUrl)
            throws Exception {
        MavenCoordinate coordinate = artifact.coordinate();
        Path directory =
                root.resolve("packages")
                        .resolve(coordinate.packageName())
                        .resolve(MajorVersion.line(coordinate.version()));
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("wstool.pkl"), packageConfig(artifact, repositoryUrl));
    }

    private String packageConfig(ResolvedArtifact artifact, String repositoryUrl) throws Exception {
        StringBuilder pkl = new StringBuilder();
        pkl.append("amends \"package://pkl.konna.cloud/pkl/wstool@1.0.2#/package.pkl\"\n\n")
                .append("package { name = \"")
                .append(artifact.coordinate().packageName())
                .append("\"; version = \"")
                .append(MajorVersion.line(artifact.coordinate().version()))
                .append("\" }\n\n")
                .append("buildOptions {\n")
                .append("  tool = \"maven-wstool-import\"\n")
                .append("}\n\n")
                .append("importOptions {\n")
                .append("  source = new ExternalFiles {\n")
                .append("    files = new Mapping {\n");
        MavenCoordinate coordinate = artifact.coordinate();
        String repositoryPath = coordinate.repositoryPath() + "/";
        appendExternalFile(
                pkl,
                repositoryUrl,
                repositoryPath + coordinate.fileStem() + ".jar",
                "repository/" + repositoryPath + coordinate.fileStem() + ".jar",
                artifact.binary());
        appendExternalFile(
                pkl,
                repositoryUrl,
                repositoryPath + coordinate.fileStem() + ".pom",
                "repository/" + repositoryPath + coordinate.fileStem() + ".pom",
                artifact.pom());
        for (var parent : artifact.supportPoms()) {
            String parentPath =
                    parent.coordinate().repositoryPath()
                            + "/"
                            + parent.coordinate().fileStem()
                            + ".pom";
            appendExternalFile(
                    pkl, repositoryUrl, parentPath, "repository/" + parentPath, parent.pom());
        }
        appendExternalFile(
                pkl,
                repositoryUrl,
                repositoryPath + coordinate.fileStem() + "-javadoc.jar",
                "repository/" + repositoryPath + coordinate.fileStem() + "-javadoc.jar",
                artifact.javadoc());
        appendExternalFile(
                pkl,
                repositoryUrl,
                repositoryPath + coordinate.fileStem() + "-sources.jar",
                "repository/" + repositoryPath + coordinate.fileStem() + "-sources.jar",
                artifact.sources());
        pkl.append("    }\n  }\n  license = \"NOASSERTION\"\n}\n\n")
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

    private void appendExternalFile(
            StringBuilder pkl,
            String repositoryUrl,
            String repositoryPath,
            String packagePath,
            Path file)
            throws Exception {
        if (file == null) {
            return;
        }
        String uri =
                repositoryUrl.endsWith("/")
                        ? repositoryUrl + repositoryPath
                        : repositoryUrl + "/" + repositoryPath;
        pkl.append("      [\"")
                .append(escape(packagePath))
                .append("\"] = new ExternalFile {\n")
                .append("        uri = \"")
                .append(escape(uri))
                .append("\"\n")
                .append("        checksum = \"")
                .append(sha256(file))
                .append("\"\n")
                .append("      }\n");
    }

    private String pklEntries(
            Map<MavenCoordinate, ResolvedArtifact> artifacts, Path bundle, String bundleUri)
            throws Exception {
        StringBuilder pkl =
                new StringBuilder(
                        "module wstool.importedMavenPackages\n\n"
                                + "import \"package://pkl.konna.cloud/pkl/wstool@1.0.2#/distribution.pkl\" as wstoolDistribution\n\n"
                                + "packages: Listing<wstoolDistribution.PackageSource> = new Listing {\n");
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
                    .append(escape(bundleUri))
                    .append("\"\n")
                    .append("    checksum = \"")
                    .append(sha256(bundle))
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

    private String artifactUri(String baseUrl, Path bundle) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return bundle.toAbsolutePath().toUri().toString();
        }
        return baseUrl.endsWith("/")
                ? baseUrl + bundle.getFileName()
                : baseUrl + "/" + bundle.getFileName();
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

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
