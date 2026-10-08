package wstool.simple.container;

import java.nio.file.Path;
import org.immutables.value.Value;
import wstool.simple.model.PackageMajorVersion;

@Value.Immutable
public interface RunContainerBuildInput {
    Path workspace();

    PackageMajorVersion name();
}
