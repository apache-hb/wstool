package wstool.common.model;

public record PackageDependency(PackageMajorVersion name, PackageMajorVersion dependency, DependencyType type) {

}
