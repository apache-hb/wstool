package wstool.npm.metadata;

import java.io.IOException;
import java.io.InputStream;

/** Reads and validates npm {@code package.json} metadata. */
public interface NpmPackageReader {
    /**
     * Reads a package manifest without closing the supplied stream.
     *
     * @param input the UTF-8 JSON input
     * @return the validated package metadata
     * @throws IOException if the input cannot be read or is invalid
     */
    NpmPackageMetadata read(InputStream input) throws IOException;
}
