package wstool.simple.storage;

import java.nio.file.Path;
import org.immutables.value.Value;
import wstool.simple.model.PackageMajorVersion;

@Value.Immutable
public interface AddPackageInput {
    PackageMajorVersion name();

    Path source();

    boolean cloned();
}
