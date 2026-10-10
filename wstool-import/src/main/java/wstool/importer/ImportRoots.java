package wstool.importer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/** Persists import roots so repeated CLI invocations update one combined package bundle. */
public final class ImportRoots {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private ImportRoots() {}

    public static List<String> read(Path output, String fileName) throws Exception {
        Path path = output.resolve(fileName);
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        return JSON.readValue(path.toFile(), STRING_LIST);
    }

    public static List<String> merge(List<String> existing, List<String> additions) {
        LinkedHashSet<String> roots = new LinkedHashSet<>(existing);
        roots.addAll(additions);
        return new ArrayList<>(roots);
    }

    public static void write(Path output, String fileName, List<String> roots) throws Exception {
        Files.createDirectories(output);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve(fileName).toFile(), roots);
    }

    public static void resetGeneratedTree(Path output, String directoryName) throws Exception {
        Path tree = output.resolve(directoryName);
        if (!Files.exists(tree)) {
            return;
        }
        try (var paths = Files.walk(tree)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
