package wstool.sysroot.closures;

import java.util.EnumSet;
import java.util.LinkedHashSet;
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
            if (expr.isAtomic() || expr.getLength() == 0 || !expr.get(0).equals(SexpFactory.newAtomicSexp("direct"))) {
                return Optional.empty();
            }
            EnumSet<ClosureName> names = EnumSet.noneOf(ClosureName.class);
            for (int i = 1; i < expr.getLength(); i++) {
                Sexp child = expr.get(i);
                if (!child.isAtomic() || !ClosureNames.supports(child.toString())) {
                    throw new IllegalArgumentException("Invalid direct closure member: " + child);
                }
                names.add(ClosureNames.from(child.toString()));
            }
            return Optional.of(new DirectClosure(context, names));
        }
    }

    @Override
    public Set<PackageMajorVersion> collect() throws Exception {
        Set<PackageMajorVersion> packages = new LinkedHashSet<>();
        packages.add(context.name());
        for (ClosureName name : names) {
            packages.addAll(new NamedClosure(context, name).collect());
        }
        return packages;
    }
}
