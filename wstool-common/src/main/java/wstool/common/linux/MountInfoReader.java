package wstool.common.linux;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.ICSVParser;
import com.opencsv.bean.CsvToBeanBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/** Reads Linux mountinfo records and selects the most specific mount containing a path. */
public final class MountInfoReader {
    private static final Path MOUNT_INFO = Path.of("/proc/self/mountinfo");

    private static final CSVParserBuilder CSV_PARSER =
            new CSVParserBuilder()
                    .withSeparator(' ')
                    .withEscapeChar(ICSVParser.NULL_CHARACTER)
                    .withIgnoreQuotations(true);

    private final Path mountInfo;

    public MountInfoReader() {
        this(MOUNT_INFO);
    }

    public MountInfoReader(Path mountInfo) {
        this.mountInfo = mountInfo;
    }

    public Optional<MountInfoEntry> findMostSpecificMount(Path path) throws IOException {
        return readMounts()
                .filter(entry -> path.startsWith(entry.mount()))
                .max(Comparator.comparingInt(entry -> entry.mount().toString().length()));
    }

    public Stream<MountInfoEntry> readMounts() throws IOException {
        try (CSVReader reader = createCsvReader()) {
            return readEntries(reader);
        }
    }

    private Stream<MountInfoEntry> readEntries(CSVReader reader) throws IOException {
        return new CsvToBeanBuilder<MountInfoEntry>(reader)
                .withType(MountInfoEntry.class).build().parse().stream();
    }

    private CSVReader createCsvReader() throws IOException {
        return new CSVReaderBuilder(Files.newBufferedReader(mountInfo, StandardCharsets.UTF_8))
                .withCSVParser(CSV_PARSER.build())
                .build();
    }
}
