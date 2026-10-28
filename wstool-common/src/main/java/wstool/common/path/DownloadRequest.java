package wstool.common.path;

import java.net.URI;
import java.nio.file.Path;

/** Source and cache options for a download managed by {@link DownloadManager}. */
public final class DownloadRequest {
    private final Path cacheDirectory;
    private final URI uri;
    private final String checksum;
    private final String cacheName;
    private final boolean shareCache;

    DownloadRequest(
            Path cacheDirectory, URI uri, String checksum, String cacheName, boolean shareCache) {
        this.cacheDirectory = cacheDirectory;
        this.uri = uri;
        this.checksum = checksum;
        this.cacheName = cacheName;
        this.shareCache = shareCache;
    }

    public static DownloadRequestBuilder builder(Path cacheDirectory, URI uri) {
        return new DownloadRequestBuilder(cacheDirectory, uri);
    }

    Path cacheDirectory() {
        return cacheDirectory;
    }

    URI uri() {
        return uri;
    }

    String checksum() {
        return checksum;
    }

    String cacheName() {
        return cacheName;
    }

    boolean shareCache() {
        return shareCache;
    }
}
