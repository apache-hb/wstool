package wstool.common.helpers;

public final class Asserts {
    public static void check(boolean expression, String message) {
        if (!expression) {
            throw new IllegalArgumentException(message);
        }
    }
}
