package wstool.common.model;

import java.net.URI;

public record ImportOptions(URI archive, String checksum, String license) {}
