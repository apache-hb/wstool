package wstool.sysroot.selectors;

import java.util.Optional;
import java.util.Set;

import de.tudresden.inf.lat.jsexp.Sexp;
import de.tudresden.inf.lat.jsexp.SexpFactory;
import wstool.common.model.PackageMajorVersion;
import wstool.common.workspace.LocalWorkspace;
import wstool.sysroot.Closure;
import wstool.sysroot.ClosureContext;
import wstool.sysroot.ClosureFactory;
import wstool.sysroot.Selector;
import wstool.sysroot.SelectorContext;
import wstool.sysroot.SelectorFactory;

/**
 * Runs a colon seperated list of paths for a given closure, use ClosureSelector to build a closure
 * comprised of all the packages instead
 */
public class PathSelector implements Selector {
    private final Closure closure;
    private final LocalWorkspace workspace;

    public PathSelector(Closure closure, LocalWorkspace workspace) {
        this.closure = closure;
        this.workspace = workspace;
    }

    public static class PathSelectorFactory implements SelectorFactory {
        private final ClosureFactory closureFactory;

        public PathSelectorFactory(ClosureFactory closureFactory) {
            this.closureFactory = closureFactory;
        }

        @Override
        public Optional<Selector> parse(Sexp expr, SelectorContext context) throws Exception {
            // (path closure target?)
            if (!expr.get(0).equals(SexpFactory.newAtomicSexp("path"))) {
                return Optional.empty();
            }

            PackageMajorVersion target = expr.getLength() > 2 ? VersionSelector.resolve(context, expr.get(2)) : context.name();
            Closure closure = closureFactory.parse(expr.get(1), new ClosureContext(context.workspace(), target)).get();

            return Optional.of(new PathSelector(closure, context.workspace()));
        }
    }

    @Override
    public String select() throws Exception {
        Set<PackageMajorVersion> packages = closure.collect();
        throw new UnsupportedOperationException("Unimplemented method 'select'");
    }
}
