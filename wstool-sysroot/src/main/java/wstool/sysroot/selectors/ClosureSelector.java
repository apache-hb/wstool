package wstool.sysroot.selectors;

import java.nio.file.Path;
import java.util.Optional;

import de.tudresden.inf.lat.jsexp.Sexp;
import de.tudresden.inf.lat.jsexp.SexpFactory;
import wstool.common.model.PackageMajorVersion;
import wstool.sysroot.Closure;
import wstool.sysroot.ClosureContext;
import wstool.sysroot.ClosureFactory;
import wstool.sysroot.Selector;
import wstool.sysroot.SelectorContext;
import wstool.sysroot.SelectorFactory;
import wstool.sysroot.SymlinkFolder;

/**
 * Builds a closure of the given type and returns a path to it
 */
public class ClosureSelector implements Selector {
    private final Closure closure;
    private final SelectorContext context;

    public ClosureSelector(Closure closure, SelectorContext context) {
        this.closure = closure;
        this.context = context;
    }

    public static class ClosureSelectorFactory implements SelectorFactory {
        private final ClosureFactory closureFactory;

        public ClosureSelectorFactory(ClosureFactory closureFactory) {
            this.closureFactory = closureFactory;
        }

        @Override
        public Optional<Selector> parse(Sexp expr, SelectorContext context) throws Exception {
            if (expr.isAtomic()
                    || expr.getLength() < 2
                    || expr.getLength() > 3
                    || !expr.get(0).equals(SexpFactory.newAtomicSexp("closure"))) {
                return Optional.empty();
            }

            PackageMajorVersion target =
                    expr.getLength() > 2
                            ? VersionSelector.resolve(context, expr.get(2))
                            : context.name();
            Closure closure =
                    closureFactory
                            .parse(
                                    expr.get(1),
                                    new ClosureContext(context.workspace(), target))
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Unknown closure expression: " + expr.get(1)));

            return Optional.of(
                    new ClosureSelector(
                            closure,
                            new SelectorContext(context.workspace(), target, context.output())));
        }
    }

    @Override
    public String select() throws Exception {
        Path output = context.output() == null
                ? context.workspace().buildClosureCache(context.name()).resolve("sysroot")
                : context.output();
        SymlinkFolder.create(context.workspace(), closure.collect(), output);
        return output.toAbsolutePath().toString();
    }
}
