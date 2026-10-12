package wstool.cli.commands;

import wstool.simple.ArchiveHelper;
import wstool.simple.DependencyResolver;
import wstool.simple.DistributionArchiveHelper;
import wstool.simple.DistributionHelper;
import wstool.simple.ExternalFilesHelper;
import wstool.simple.ExternalPackageSourceHelper;
import wstool.simple.GitHelper;
import wstool.simple.PackageHelper;
import wstool.simple.PackageMapper;
import wstool.simple.PackageSourceHelper;
import wstool.simple.PklHelper;
import wstool.simple.StorageHelper;
import wstool.simple.WorkspaceHelper;

/** Builds command-level services from their shared dependencies. */
public final class CommandHelpers {
    private CommandHelpers() {}

    public static WorkspaceHelper workspace(GitHelper git, StorageHelper storage, PklHelper pkl) {
        DistributionArchiveHelper archives = new DistributionArchiveHelper(new ArchiveHelper());
        PackageMapper mapper = new PackageMapper();
        PackageSourceHelper sources = new PackageSourceHelper(archives, git, mapper);
        return new WorkspaceHelper(
                git,
                storage,
                new DistributionHelper(pkl),
                new PackageHelper(pkl),
                sources,
                new ExternalPackageSourceHelper(archives, sources, new ExternalFilesHelper(), git));
    }

    public static DependencyResolver dependencies(GitHelper git, PklHelper pkl) {
        StorageHelper storage = new StorageHelper();
        DistributionArchiveHelper archives = new DistributionArchiveHelper(new ArchiveHelper());
        PackageHelper packageHelper = new PackageHelper(pkl);
        PackageMapper mapper = new PackageMapper();
        PackageSourceHelper packageSourceHelper = new PackageSourceHelper(archives, git, mapper);
        ExternalFilesHelper externalFilesHelper = new ExternalFilesHelper();
        ExternalPackageSourceHelper externalPackageSourceHelper = new ExternalPackageSourceHelper(archives, packageSourceHelper, externalFilesHelper, git);
        WorkspaceHelper workspaceHelper = new WorkspaceHelper(git, storage, new DistributionHelper(pkl), packageHelper, packageSourceHelper, externalPackageSourceHelper);
        return new DependencyResolver(git, pkl, new PackageSourceHelper(archives, git, mapper), archives, workspaceHelper, packageHelper, mapper);
    }
}
