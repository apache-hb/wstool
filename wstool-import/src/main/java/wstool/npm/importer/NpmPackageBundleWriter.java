package wstool.npm.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import wstool.importer.ImportRoots;
import wstool.importer.MajorVersion;
import wstool.npm.importer.NpmDependencyResolver.ResolvedPackage;

/** Creates a source archive and Pkl distribution entries for resolved npm packages. */
public class NpmPackageBundleWriter {
    private final ObjectMapper json = new ObjectMapper();

    public Path write(
            NpmDependencyResolver.Result resolved,
            Path outputDirectory,
            String baseUrl,
            String buildImage)
            throws Exception {
        Path tree = outputDirectory.resolve("package-tree");
        Path bundle = outputDirectory.resolve("wstool-npm-import.zip");
        ImportRoots.resetGeneratedTree(outputDirectory, "package-tree");
        Files.createDirectories(tree);
        for (ResolvedPackage pkg : groupedPackages(resolved).values()) {
            writePackage(pkg, tree, buildImage);
        }
        zip(tree, bundle);
        String uri = artifactUri(baseUrl, bundle);
        String checksum = sha256(bundle);
        Files.writeString(
                outputDirectory.resolve("packages.pkl"), packageEntries(resolved, uri, checksum));
        Files.writeString(
                outputDirectory.resolve("npm-dependencies.pkl"), rootDependencies(resolved));
        return bundle;
    }

    private void writePackage(ResolvedPackage pkg, Path root, String buildImage)
            throws IOException {
        String wstoolName = NpmPackageName.wstoolName(pkg.name());
        String versionLine = MajorVersion.line(pkg.version());
        Path directory = root.resolve("packages").resolve(pkg.name()).resolve(versionLine);
        copyTree(pkg.directory(), directory.resolve("package"));
        json.writeValue(
                directory.resolve("wstool-npm.json").toFile(),
                Map.of(
                        "name", pkg.name(),
                        "wstoolName", wstoolName,
                        "version", pkg.version(),
                        "wstoolVersion", versionLine,
                        "dependencies", pkg.dependencies()));
        Files.writeString(directory.resolve("wstool.pkl"), packageConfig(pkg, buildImage));
    }

    private String packageConfig(ResolvedPackage pkg, String buildImage) {
        StringBuilder pkl =
                new StringBuilder(
                                "amends \"package://pkl.konna.cloud/pkl/wstool@1.0.1#/package.pkl\"\n\n"
                                        + "package { name = \"")
                        .append(NpmPackageName.wstoolName(pkg.name()))
                        .append("\"; version = \"")
                        .append(escape(MajorVersion.line(pkg.version())))
                        .append("\" }\n\n")
                        .append("buildOptions {\n")
                        .append("  tool = \"npm-wstool\"\n")
                        .append("  dockerImage = \"")
                        .append(escape(buildImage))
                        .append("\"\n")
                        .append("  networkIsolation = \"isolated\"\n")
                        .append("}\n");
        if (!pkg.dependencies().isEmpty()) {
            pkl.append("\ndependencies {\n");
            pkg.dependencies().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            dependency ->
                                    pkl.append("  [\"")
                                            .append(NpmPackageName.wstoolName(dependency.getKey()))
                                            .append("\"] = \"")
                                            .append(
                                                    escape(
                                                            MajorVersion.line(
                                                                    dependency.getValue())))
                                            .append("\"\n"));
            pkl.append("}\n");
        }
        pkl.append("\nbuildDependencies { [\"wstool-npm-import\"] = \"1.0.0\" }\n");
        return pkl.toString();
    }

    private String packageEntries(
            NpmDependencyResolver.Result resolved, String uri, String checksum) {
        StringBuilder pkl =
                new StringBuilder(
                        "module wstool.importedNpmPackages\n\n"
                                + "import \"package://pkl.konna.cloud/pkl/wstool@1.0.1#/distribution.pkl\" as wstoolDistribution\n\n"
                                + "packages {\n");
        groupedPackages(resolved).values().stream()
                .sorted(
                        java.util.Comparator.comparing(ResolvedPackage::name)
                                .thenComparing(ResolvedPackage::version))
                .forEach(
                        pkg ->
                                pkl.append("\nnew wstoolDistribution.Folder {\n")
                                        .append("  name = \"")
                                        .append(NpmPackageName.wstoolName(pkg.name()))
                                        .append("\"\n")
                                        .append("  version = \"")
                                        .append(escape(MajorVersion.line(pkg.version())))
                                        .append("\"\n")
                                        .append("  from = new wstoolDistribution.Archive {\n")
                                        .append("    name = \"wstool-npm-package-sources\"\n")
                                        .append("    version = \"1.0.0\"\n")
                                        .append("    uri = \"")
                                        .append(escape(uri))
                                        .append("\"\n")
                                        .append("    checksum = \"")
                                        .append(checksum)
                                        .append("\"\n")
                                        .append("  }\n")
                                        .append("  path = \"packages/")
                                        .append(pkg.name())
                                        .append("/")
                                        .append(escape(MajorVersion.line(pkg.version())))
                                        .append("\"\n")
                                        .append("}\n"));
        pkl.append("}\n");
        return pkl.toString();
    }

    private String rootDependencies(NpmDependencyResolver.Result resolved) {
        StringBuilder pkl = new StringBuilder("module wstool.importedNpmDependencies\n");
        appendDependencyMap(pkl, "dependencies", resolved);
        appendDependencyMap(pkl, "buildDependencies", resolved);
        return pkl.toString();
    }

    private void appendDependencyMap(
            StringBuilder pkl, String section, NpmDependencyResolver.Result resolved) {
        pkl.append("\n").append(section).append(" {\n");
        resolved.rootDependencies().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        dependency ->
                                pkl.append("  [\"")
                                        .append(NpmPackageName.wstoolName(dependency.getKey()))
                                        .append("\"] = \"")
                                        .append(escape(MajorVersion.line(dependency.getValue())))
                                        .append("\"\n"));
        pkl.append("}\n");
    }

    private Map<String, ResolvedPackage> groupedPackages(NpmDependencyResolver.Result resolved) {
        java.util.TreeMap<String, ResolvedPackage> grouped = new java.util.TreeMap<>();
        resolved.packages().values().stream()
                .sorted(java.util.Comparator.comparing(ResolvedPackage::version))
                .forEach(
                        pkg ->
                                grouped.put(
                                        pkg.name() + "\u0000" + MajorVersion.line(pkg.version()),
                                        pkg));
        return grouped;
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

    private void copyTree(Path source, Path destination) throws IOException {
        Files.walkFileTree(
                source,
                new SimpleFileVisitor<>() {
                    @Override
                    public java.nio.file.FileVisitResult preVisitDirectory(
                            Path directory, BasicFileAttributes attributes) throws IOException {
                        Files.createDirectories(destination.resolve(source.relativize(directory)));
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult visitFile(
                            Path file, BasicFileAttributes attributes) throws IOException {
                        Path target = destination.resolve(source.relativize(file));
                        Files.createDirectories(target.getParent());
                        Files.copy(
                                file,
                                target,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
