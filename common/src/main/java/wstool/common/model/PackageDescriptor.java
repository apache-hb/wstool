package wstool.common.model;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public record PackageDescriptor(
        PackageVersion version,
        String buildTool,
        Map<String, String> buildDependencies,
        Map<String, String> dependencies,
        Map<String, String> testDependencies,
        Map<String, String> runtimeDependencies,
        Map<String, String> overrideDependencies,
        Path source) {

    public Map<String, String> allDependencies() {
        var result = new LinkedHashMap<String, String>();
        result.putAll(buildDependencies);
        result.putAll(dependencies);
        result.putAll(testDependencies);
        result.putAll(runtimeDependencies);
        return result;
    }
}
