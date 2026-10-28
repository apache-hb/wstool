package wstool.common.path;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/** Builder for optional checksum and cache behavior on a download request. */
public final class DownloadRequestBuilder {
    private final Path cacheDirectory;
    private final URI uri;
    private String checksum;
    private String cacheName;
    private boolean shareCache = true;

    DownloadRequestBuilder(Path cacheDirectory, URI uri) {
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory);
        this.uri = Objects.requireNonNull(uri);
    }

    public DownloadRequestBuilder checksum(String checksum) {
        this.checksum = checksum;
        return this;
    }

    public DownloadRequestBuilder cacheName(String cacheName) {
        this.cacheName = cacheName;
        return this;
    }

    public DownloadRequestBuilder shareCache(boolean shareCache) {
        this.shareCache = shareCache;
        return this;
    }

    public DownloadRequest build() {
        return new DownloadRequest(cacheDirectory, uri, checksum, cacheName, shareCache);
    }
}
