package wstool.common.distribution;

import java.util.List;
import java.util.Set;
import org.immutables.value.Value;

@Value.Immutable
public interface DistributionConfig {
    Set<String> platforms();

    List<DistributionPackage> packages();
}
