package wstool.sysroot.closures;

import java.util.Optional;
import java.util.Set;

import de.tudresden.inf.lat.jsexp.Sexp;
import wstool.common.model.PackageMajorVersion;
import wstool.sysroot.Closure;
import wstool.sysroot.ClosureContext;
import wstool.sysroot.ClosureFactory;

public class NamedClosure implements Closure {
    public static class NamedClosureFactory implements ClosureFactory {
        @Override
        public Optional<Closure> parse(Sexp expr, ClosureContext context) throws Exception {
            // [build|test|runtime]
            throw new UnsupportedOperationException("Unimplemented method 'parse'");
        }
    }

    @Override
    public Set<PackageMajorVersion> collect() throws Exception {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'collect'");
    }

}
