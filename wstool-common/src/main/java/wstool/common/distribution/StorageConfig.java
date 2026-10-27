package wstool.common.distribution;

import java.net.URI;

public record StorageConfig(URI endpoint, String bucket) {}
