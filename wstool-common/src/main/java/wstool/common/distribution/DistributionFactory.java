package wstool.common.distribution;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import wstool.Distribution;
import wstool.Distribution.Archive;
import wstool.Distribution.Folder;
import wstool.Distribution.GitRepository;
import wstool.Distribution.PackageSource;
import wstool.common.model.PackageMajorVersion;
import wstool.common.pkl.PklHelper;

@Singleton
public class DistributionFactory {
    private static final String CONFIG = "distribution.pkl";

    private final PklHelper pklHelper;

    @Inject
    public DistributionFactory(PklHelper pklHelper) {
        this.pklHelper = pklHelper;
    }

    public DistributionConfig open(Path path) {
        Distribution distribution = pklHelper.evaluate(path.resolve(CONFIG)).as(Distribution.class);

        List<DistributionPackage> packages =
                distribution.getPackages().stream().map(this::map).toList();

        return ImmutableDistributionConfig.builder()
                .storage(
                        new StorageConfig(
                                distribution.getStorage().getEndpoint(),
                                distribution.getStorage().getBucket()))
                .platforms(Set.copyOf(distribution.getPlatforms()))
                .addAllPackages(packages)
                .build();
    }

    private DistributionPackage map(PackageSource source) {
        PackageMajorVersion pmv = new PackageMajorVersion(source.getName(), source.getVersion());
        return switch (source) {
            case GitRepository repo ->
                    new DistributionPackage.GitRepository(pmv, repo.getRepo(), repo.getBranch());
            case Archive archive ->
                    new DistributionPackage.Archive(pmv, archive.getUri(), archive.getChecksum());
            case Folder folder ->
                    new DistributionPackage.Folder(
                            pmv, map(folder.getFrom()), Path.of(folder.getPath()));
            default -> throw new IllegalArgumentException("Unknown type " + source);
        };
    }
}
