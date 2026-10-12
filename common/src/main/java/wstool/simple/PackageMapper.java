package wstool.simple;

import java.nio.file.Path;
import wstool.Distribution.Archive;
import wstool.Distribution.Folder;
import wstool.Distribution.GitRepository;
import wstool.Distribution.PackageSource;
import wstool.simple.model.PackageMajorVersion;

public class PackageMapper {
    public PackageMapper() {}

    public LocalPackage toLocalPackage(PackageSource source, Path workspace) {
        PackageMajorVersion pmv = new PackageMajorVersion(source.getName(), source.getVersion());

        return switch (source) {
            case Archive _ -> LocalPackage.ofCached(pmv, workspace);
            case Folder folder ->
                    toLocalPackage(folder.getFrom(), workspace)
                            .nested(pmv, workspace, Path.of(folder.getPath()));
            case GitRepository _ -> LocalPackage.ofCached(pmv, workspace);
            default ->
                    throw new UnsupportedOperationException(
                            "Unknown package source " + source.toString());
        };
    }
}
