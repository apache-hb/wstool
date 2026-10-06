package wstool.simple.storage;

import java.util.List;
import org.immutables.value.Value;
import wstool.simple.model.PackageMajorVersion;

@Value.Immutable
public interface PackageModel {
    PackageMajorVersion name();

    String buildTool();

    List<PackageMajorVersion> buildDependencies();

    List<PackageMajorVersion> dependencies();

    List<PackageMajorVersion> testDependencies();

    List<PackageMajorVersion> overrideDependencies();

    List<PackageMajorVersion> removeDependencies();
}
