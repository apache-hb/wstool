package wstool.simple.tool;

import java.util.List;

import org.immutables.value.Value;

import wstool.simple.Workspace;
import wstool.simple.model.PackageMajorVersion;

@Value.Immutable
public interface RunBuildToolInput {
    Workspace workspace();

    PackageMajorVersion name();

    List<String> arguments();
}
