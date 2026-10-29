package wstool.cli.commands.packages;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine.ITypeConverter;

public final class MemorySizeConverter implements ITypeConverter<Long> {
    private static final Pattern SIZE =
            Pattern.compile("^([1-9][0-9]*)([KMGT]?I?B?)?$", Pattern.CASE_INSENSITIVE);

    @Override
    public Long convert(String value) {
        Matcher matcher = SIZE.matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid memory size: " + value);
        }
        String unit = matcher.group(2) == null ? "" : matcher.group(2).toUpperCase(Locale.ROOT);
        int power =
                switch (unit.isEmpty() ? "B" : unit.substring(0, 1)) {
                    case "K" -> 1;
                    case "M" -> 2;
                    case "G" -> 3;
                    case "T" -> 4;
                    default -> 0;
                };
        return Math.multiplyExact(Long.parseLong(matcher.group(1)), 1L << (10 * power));
    }
}
