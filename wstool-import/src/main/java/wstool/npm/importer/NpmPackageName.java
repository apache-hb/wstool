package wstool.npm.importer;

/** Builds wstool package names while preserving npm's original package name. */
public final class NpmPackageName {
    private NpmPackageName() {}

    public static String wstoolName(String npmName) {
        return "npm_" + npmName;
    }
}
