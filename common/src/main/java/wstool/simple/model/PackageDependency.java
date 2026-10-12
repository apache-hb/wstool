package wstool.simple.model;

import wstool.simple.storage.DependencyType;

public record PackageDependency(
        PackageMajorVersion name, PackageMajorVersion dependency, DependencyType type) {}
