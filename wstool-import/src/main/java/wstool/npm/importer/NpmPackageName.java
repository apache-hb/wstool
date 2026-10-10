package wstool.npm.importer;

/** Maps npm package names to names accepted by the wstool package model. */
public final class NpmPackageName {
    private NpmPackageName() {}

    public static String encode(String npmName) {
        StringBuilder encoded = new StringBuilder("npm_");
        for (byte value : npmName.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int character = Byte.toUnsignedInt(value);
            if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_'
                    || character == '-'
                    || character == '.') {
                encoded.append((char) character);
            } else {
                encoded.append('_').append(String.format("%02x", character)).append('_');
            }
        }
        return encoded.toString();
    }
}
