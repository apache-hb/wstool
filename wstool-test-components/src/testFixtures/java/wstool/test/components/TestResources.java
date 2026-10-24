package wstool.test.components;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class TestResources {
    private TestResources() {}

    public static Path copyDirectory(Class<?> resourceOwner, String resourceName, Path destination)
            throws IOException {
        URL resource = resourceOwner.getResource(resourceName);
        if (resource == null) {
            throw new IOException("Missing test resource directory: " + resourceName);
        }

        URI resourceUri;
        try {
            resourceUri = resource.toURI();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid test resource URI: " + resource, e);
        }

        if (resourceUri.getScheme().equals("jar")) {
            try {
                JarURLConnection connection = (JarURLConnection) resource.openConnection();
                try (FileSystem fileSystem =
                        FileSystems.newFileSystem(
                                Path.of(connection.getJarFileURL().toURI()), Map.of())) {
                    copyTree(fileSystem.getPath("/" + connection.getEntryName()), destination);
                }
            } catch (URISyntaxException e) {
                throw new IOException("Invalid test resource archive URI: " + resource, e);
            }
        } else {
            copyTree(Path.of(resourceUri), destination);
        }
        return destination;
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }
}
