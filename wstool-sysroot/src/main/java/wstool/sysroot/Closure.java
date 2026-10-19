package wstool.sysroot;

import java.util.Set;
import wstool.common.model.PackageMajorVersion;

public interface Closure {
    Set<PackageMajorVersion> collect() throws Exception;
}
