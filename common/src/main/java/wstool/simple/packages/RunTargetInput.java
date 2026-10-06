package wstool.simple.packages;

import java.nio.file.Path;
import java.util.List;
import org.immutables.value.Value;

@Value.Immutable
public interface RunTargetInput {
    Path workspace();

    Path _package();

    List<String> args();
}
