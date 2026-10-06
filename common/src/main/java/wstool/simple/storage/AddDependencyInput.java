package wstool.simple.storage;

import org.immutables.value.Value;
import wstool.simple.model.PackageMajorVersion;

@Value.Immutable
public interface AddDependencyInput {
    PackageMajorVersion name();

    DependencyType type();

    PackageMajorVersion dependency();
}
