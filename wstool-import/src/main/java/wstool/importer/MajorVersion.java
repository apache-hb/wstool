package wstool.importer;

/** Converts resolved artifact versions to the major-version line used by wstool packages. */
public final class MajorVersion {
    private MajorVersion() {}

    public static String line(String version) {
        while (!version.isEmpty() && (version.charAt(0) == '^' || version.charAt(0) == '~')) {
            version = version.substring(1);
        }
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
        if (end == 0 || end == version.length() || version.charAt(end) != '.') return version;
        return version.substring(0, end) + ".x";
    }
}
