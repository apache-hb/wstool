package wstool.common.packages;

import java.util.List;
import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.NullMarked;
import wstool.common.model.DependencyType;
import wstool.common.model.PackageMajorVersion;

@NullMarked
@Value.Immutable
public interface PackageConfig {
    PackageMajorVersion name();

    String buildTool();

    Map<DependencyType, List<PackageMajorVersion>> dependencies();
}
