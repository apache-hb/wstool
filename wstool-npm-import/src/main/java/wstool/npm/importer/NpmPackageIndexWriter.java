package wstool.npm.importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

/** Writes resolved npm packages as folders in a wstool package index. */
public final class NpmPackageIndexWriter {
    private static final String PACKAGE_MODULE =
            "package://pkl.konna.cloud/pkl/wstool@1.0.2#/package.pkl";

    private final HttpClient httpClient;
    private final VelocityEngine velocityEngine;

    public NpmPackageIndexWriter(HttpClient httpClient) {
        this.httpClient = httpClient;
        Properties properties = new Properties();
        properties.setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath");
        properties.setProperty(
                "resource.loader.classpath.class", ClasspathResourceLoader.class.getName());
        velocityEngine = new VelocityEngine(properties);
        velocityEngine.init();
    }

    public List<Path> write(List<NpmPackage> packages, Path indexDirectory) throws Exception {
        Path indexRoot = indexDirectory.toAbsolutePath().normalize();
        List<Path> packageConfigs = new ArrayList<>();
        for (NpmPackage npmPackage : packages) {
            URI tarballUri = tarballUri(npmPackage.tarballUrl());
            String checksum = checksum(tarballUri.toString(), npmPackage.integrity());
            Path packageDirectory =
                    indexRoot
                            .resolve("npm")
                            .resolve(npmPackage.name())
                            .resolve(npmPackage.version())
                            .normalize();
            if (!packageDirectory.startsWith(indexRoot)) {
                throw new IOException("npm package path escapes the index: " + npmPackage.name());
            }
            Files.createDirectories(packageDirectory);
            Path packageConfig = packageDirectory.resolve("wstool.pkl");
            writePackageConfig(npmPackage, tarballUri, checksum, packageConfig);
            packageConfigs.add(packageConfig);
        }
        return List.copyOf(packageConfigs);
    }

    private String checksum(String url, String integrity) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException(
                        "Could not download npm package "
                                + url
                                + ": HTTP "
                                + response.statusCode());
            }

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                sha256.update(buffer, 0, count);
                sha512.update(buffer, 0, count);
            }
            verifyIntegrity(integrity, sha512.digest());
            return HexFormat.of().formatHex(sha256.digest());
        }
    }

    private void verifyIntegrity(String integrity, byte[] sha512) throws IOException {
        if (integrity == null || integrity.isBlank()) {
            throw new IOException("npm package lockfile has no integrity value");
        }
        for (String token : integrity.split("\\s+")) {
            if (token.startsWith("sha512-")) {
                String expected = token.substring("sha512-".length()).split("\\?", 2)[0];
                if (!MessageDigest.isEqual(Base64.getDecoder().decode(expected), sha512)) {
                    throw new IOException(
                            "npm package tarball failed SHA-512 integrity validation");
                }
                return;
            }
        }
        throw new IOException("Unsupported npm package integrity value: " + integrity);
    }

    private void writePackageConfig(
            NpmPackage npmPackage, URI tarballUri, String checksum, Path destination)
            throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("packageModule", pklString(PACKAGE_MODULE));
        context.put("name", pklString(npmPackage.name()));
        context.put("version", pklString(npmPackage.version()));
        context.put("tarballUri", pklString(tarballUri.toString()));
        context.put("checksum", pklString(checksum));
        List<Map<String, String>> dependencies =
                npmPackage.dependencies().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(
                                dependency ->
                                        Map.of(
                                                "name", pklString(dependency.getKey()),
                                                "version", pklString(dependency.getValue())))
                        .toList();
        context.put("dependencies", dependencies);
        StringWriter config = new StringWriter();
        velocityEngine
                .getTemplate("wstool/npm/importer/npm-package-index.pkl.vm")
                .merge(context, config);
        writeText(destination, config.toString());
    }

    private void writeText(Path destination, String content) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".wstool-", ".pkl");
        try {
            Files.writeString(
                    temporary,
                    content,
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

    private URI tarballUri(String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("npm package lockfile has no tarball download URL");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid npm package tarball URL: " + value, exception);
        }
        String scheme = uri.getScheme();
        if (!uri.isAbsolute()
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IOException("npm package tarball must use an HTTP(S) download URL: " + value);
        }
        return uri;
    }

    private String pklString(String value) {
        return "\""
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\r", "\\r")
                        .replace("\n", "\\n")
                + "\"";
    }
}
