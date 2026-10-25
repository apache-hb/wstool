package wstool.maven.importer;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

/** Writes resolved Maven artifacts as folders in a wstool package index. */
public final class MavenPackageIndexWriter {
    private static final String PACKAGE_MODULE =
            "package://pkl.konna.cloud/pkl/wstool@1.0.2#/package.pkl";

    private final VelocityEngine velocityEngine;

    public MavenPackageIndexWriter() {
        Properties properties = new Properties();
        properties.setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath");
        properties.setProperty(
                "resource.loader.classpath.class", ClasspathResourceLoader.class.getName());
        velocityEngine = new VelocityEngine(properties);
        velocityEngine.init();
    }

    public List<Path> write(
            List<MavenArtifact> artifacts,
            String repositoryUrl,
            Path localRepository,
            Path packageIndexDirectory)
            throws Exception {
        Path localRoot = localRepository.toAbsolutePath().normalize();
        Path indexRoot = packageIndexDirectory.toAbsolutePath().normalize();
        URI repository = repositoryUri(repositoryUrl);
        List<Path> packageConfigs = new ArrayList<>();

        for (MavenArtifact artifact : artifacts) {
            MavenCoordinate coordinate = MavenCoordinate.parse(artifact.coordinate());
            Path artifactPath = artifact.file().toAbsolutePath().normalize();
            if (!artifactPath.startsWith(localRoot)) {
                throw new IOException(
                        "Resolved Maven artifact is outside the local repository: " + artifactPath);
            }
            Path relativeArtifact = localRoot.relativize(artifactPath);
            URI sourceUri = repository.resolve(relativeArtifact.toString().replace('\\', '/'));
            String checksum = sha256(artifactPath);
            Path packageDirectory =
                    indexRoot
                            .resolve("maven")
                            .resolve(coordinate.group().replace('.', '/'))
                            .resolve(coordinate.artifact())
                            .resolve(coordinate.version())
                            .normalize();
            if (!packageDirectory.startsWith(indexRoot)) {
                throw new IOException(
                        "Maven package path escapes the index: " + artifact.coordinate());
            }
            Files.createDirectories(packageDirectory);
            Path packageConfig = packageDirectory.resolve("wstool.pkl");
            writePackageConfig(artifact, coordinate, sourceUri, checksum, packageConfig);
            packageConfigs.add(packageConfig);
        }
        return List.copyOf(packageConfigs);
    }

    private URI repositoryUri(String repositoryUrl) {
        return URI.create(repositoryUrl.endsWith("/") ? repositoryUrl : repositoryUrl + "/");
    }

    private String sha256(Path artifact) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void writePackageConfig(
            MavenArtifact artifact,
            MavenCoordinate coordinate,
            URI sourceUri,
            String checksum,
            Path destination)
            throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("packageModule", pklString(PACKAGE_MODULE));
        context.put("name", pklString(coordinate.group() + ":" + coordinate.artifact()));
        context.put("version", pklString(coordinate.version()));
        context.put("sourceUri", pklString(sourceUri.toString()));
        context.put("path", pklString("lib/" + artifact.file().getFileName()));
        context.put("checksum", pklString(checksum));
        List<Map<String, String>> dependencies =
                artifact.dependencies().stream()
                        .map(MavenCoordinate::parse)
                        .sorted(
                                (left, right) ->
                                        (left.group() + ":" + left.artifact())
                                                .compareTo(right.group() + ":" + right.artifact()))
                        .map(
                                dependency ->
                                        Map.of(
                                                "name",
                                                pklString(
                                                        dependency.group()
                                                                + ":"
                                                                + dependency.artifact()),
                                                "version",
                                                pklString(dependency.version())))
                        .toList();
        context.put("dependencies", dependencies);
        StringWriter config = new StringWriter();
        velocityEngine
                .getTemplate("wstool/maven/importer/maven-package-index.pkl.vm")
                .merge(context, config);

        Path temporary = Files.createTempFile(destination.getParent(), ".wstool-", ".pkl");
        try {
            Files.writeString(
                    temporary,
                    config.toString(),
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String pklString(String value) {
        return "\""
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\r", "\\r")
                        .replace("\n", "\\n")
                + "\"";
    }

    private record MavenCoordinate(String group, String artifact, String version) {
        private static MavenCoordinate parse(String coordinate) {
            String[] fields = coordinate.split(":");
            if (fields.length < 4 || fields[0].isBlank() || fields[1].isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid resolved Maven coordinate: " + coordinate);
            }
            return new MavenCoordinate(fields[0], fields[1], fields[fields.length - 1]);
        }
    }
}
