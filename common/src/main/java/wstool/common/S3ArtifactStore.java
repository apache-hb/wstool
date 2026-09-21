package wstool.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import wstool.common.model.PackageVersion;

public final class S3ArtifactStore implements ArtifactStore {
    private static final Logger LOG = LogManager.getLogger(S3ArtifactStore.class);

    private final S3Client client;
    private final String bucket;

    public S3ArtifactStore() {
        var endpoint = env("WSTOOL_S3_ENDPOINT", null);
        var accessKey = env("WSTOOL_S3_ACCESS_KEY", env("AWS_ACCESS_KEY_ID", "garage-key"));
        var secretKey = env("WSTOOL_S3_SECRET_KEY", env("AWS_SECRET_ACCESS_KEY", "garage-secret"));
        var builder =
                S3Client.builder()
                        .region(Region.of(env("WSTOOL_S3_REGION", "us-east-1")))
                        // Garage 2.1 accepts ordinary signed payloads, but not the AWS SDK's
                        // newer trailing-checksum/chunked upload variant.
                        .serviceConfiguration(
                                S3Configuration.builder()
                                        .checksumValidationEnabled(false)
                                        .chunkedEncodingEnabled(false)
                                        .build());
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        client = builder.build();
        bucket = env("WSTOOL_S3_BUCKET", "storage");
    }

    @Override
    public void publish(PackageVersion version, Path directory) {
        ensureBucket();
        var artifact = directory.resolveSibling(directory.getFileName() + ".artifact.zip");
        try {
            zip(directory, artifact);
            var key = key(version);
            client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromFile(artifact));
            LOG.info("Published {} to s3://{}/{}", version, bucket, key);
            Files.deleteIfExists(artifact);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to publish " + version, e);
        }
    }

    @Override
    public Path fetch(PackageVersion version, Path cacheDirectory) {
        ensureBucket();
        var target =
                cacheDirectory
                        .resolve(version.name())
                        .resolve(version.version())
                        .resolve("artifacts");
        if (Files.exists(target)) {
            return target;
        }
        try {
            Files.createDirectories(target);
            var zipPath = target.getParent().resolve("artifact.zip");
            client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key(version)).build(), zipPath);
            unzip(zipPath, target);
            Files.deleteIfExists(zipPath);
            LOG.info("Fetched {} from s3://{}/{}", version, bucket, key(version));
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to fetch " + version, e);
        }
    }

    private void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (RuntimeException missing) {
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (RuntimeException alreadyCreated) {
                LOG.debug("Bucket {} was not created by this process", bucket, alreadyCreated);
            }
        }
    }

    private String key(PackageVersion version) {
        return "artifacts/%s/%s/artifact.zip".formatted(version.name(), version.version());
    }

    private static void zip(Path directory, Path output) throws IOException {
        try (var stream = Files.walk(directory);
                var zip = new ZipOutputStream(Files.newOutputStream(output))) {
            stream.filter(Files::isRegularFile)
                    .forEach(
                            path -> {
                                var name =
                                        directory
                                                .relativize(path)
                                                .toString()
                                                .replace(path.getFileSystem().getSeparator(), "/");
                                try {
                                    zip.putNextEntry(new ZipEntry(name));
                                    Files.copy(path, zip);
                                    zip.closeEntry();
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
        }
    }

    private static void unzip(Path zipPath, Path output) throws IOException {
        try (var zip = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                var target = output.resolve(entry.getName()).normalize();
                if (!target.startsWith(output)) {
                    throw new IOException("Artifact contains an unsafe path: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target);
                    if (entry.getName().startsWith("bin/")) makeExecutable(target);
                }
            }
        }
    }

    private static void makeExecutable(Path path) throws IOException {
        try {
            var permissions = EnumSet.noneOf(PosixFilePermission.class);
            permissions.addAll(Files.getPosixFilePermissions(path));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException e) {
            if (!path.toFile().setExecutable(true, false)) {
                throw new IOException("Unable to make executable: " + path, e);
            }
        }
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public void close() {
        client.close();
    }
}
