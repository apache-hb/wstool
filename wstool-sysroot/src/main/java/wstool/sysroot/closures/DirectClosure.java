package wstool.sysroot.closures;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import de.tudresden.inf.lat.jsexp.Sexp;
import de.tudresden.inf.lat.jsexp.SexpFactory;
import wstool.common.model.PackageMajorVersion;
import wstool.sysroot.Closure;
import wstool.sysroot.ClosureContext;
import wstool.sysroot.ClosureFactory;

public class DirectClosure implements Closure {
    private final ClosureContext context;
    private final Set<ClosureName> names;

    public DirectClosure(ClosureContext context, Set<ClosureName> names) {
        this.context = context;
        this.names = names;
    }

    public static class DirectClosureFactory implements ClosureFactory {
        @Override
        public Optional<Closure> parse(Sexp expr, ClosureContext context) {
            // (direct [build|test|runtime]+)
            if (!expr.get(0).equals(SexpFactory.newAtomicSexp("direct"))) {
                return Optional.empty();
            }

            return Optional.of(new DirectClosure(context, EnumSet.allOf(ClosureName.class)));
        }
    }

    @Override
    public Set<PackageMajorVersion> collect() throws Exception {
        return Set.of(context.name());
    }
}
