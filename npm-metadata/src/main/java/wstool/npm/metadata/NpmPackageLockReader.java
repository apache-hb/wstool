package wstool.npm.metadata;

import java.io.InputStream;

/** Reads and validates npm {@code package-lock.json} metadata. */
public interface NpmPackageLockReader {
    /**
     * Reads a package lock without closing the supplied stream.
     *
     * @param input the UTF-8 JSON input
     * @return the validated package lock
     */
    NpmPackageLock read(InputStream input);
}
