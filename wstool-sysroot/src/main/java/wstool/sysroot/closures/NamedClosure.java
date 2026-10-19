package wstool.sysroot.closures;

import de.tudresden.inf.lat.jsexp.Sexp;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import wstool.common.model.DependencyType;
import wstool.common.model.PackageDependency;
import wstool.common.model.PackageMajorVersion;
import wstool.sysroot.Closure;
import wstool.sysroot.ClosureContext;
import wstool.sysroot.ClosureFactory;

/** Collects the transitive dependencies selected by a named closure. */
public class NamedClosure implements Closure {
    private final ClosureContext context;
    private final ClosureName name;

    public NamedClosure(ClosureContext context, ClosureName name) {
        this.context = context;
        this.name = name;
    }

    public static class NamedClosureFactory implements ClosureFactory {
        @Override
        public Optional<Closure> parse(Sexp expr, ClosureContext context) {
            String name;
            if (expr.isAtomic()) {
                name = expr.toString();
            } else if (expr.getLength() == 1 && expr.get(0).isAtomic()) {
                name = expr.get(0).toString();
            } else {
                return Optional.empty();
            }
            if (!ClosureNames.supports(name)) {
                return Optional.empty();
            }
            return Optional.of(new NamedClosure(context, ClosureNames.from(name)));
        }
    }

    @Override
    public Set<PackageMajorVersion> collect() throws Exception {
        Set<PackageMajorVersion> closure = new LinkedHashSet<>();
        Queue<PackageMajorVersion> pending = new ArrayDeque<>();
        
        for (PackageDependency dependency :
                context.workspace().storage().dependenciesOf(context.name())) {
            if (ClosureNames.includes(name, dependency.type())) {
                pending.add(dependency.dependency());
            }
        }

        while (!pending.isEmpty()) {
            PackageMajorVersion current = pending.remove();
            if (!closure.add(current)) {
                continue;
            }
            for (PackageDependency dependency :
                    context.workspace().storage().dependenciesOf(current)) {
                if (ClosureNames.includes(name, dependency.type())) {
                    pending.add(dependency.dependency());
                } else if (name == ClosureName.BUILD
                        && (dependency.type() == DependencyType.PUBLIC
                                || dependency.type() == DependencyType.RUNTIME)) {
                    pending.add(dependency.dependency());
                }
            }
        }
        return closure;
    }
}
