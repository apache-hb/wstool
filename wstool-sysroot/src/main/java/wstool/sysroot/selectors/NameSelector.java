package wstool.sysroot.selectors;

import java.util.Optional;

import de.tudresden.inf.lat.jsexp.Sexp;
import de.tudresden.inf.lat.jsexp.SexpFactory;
import wstool.common.model.PackageMajorVersion;
import wstool.sysroot.Closure;
import wstool.sysroot.ClosureFactory;
import wstool.sysroot.Selector;
import wstool.sysroot.SelectorContext;
import wstool.sysroot.SelectorFactory;

public class NameSelector implements Selector {
    private final String name;

    public NameSelector(String name) {
        this.name = name;
    }

    public static class NameSelectorFactory implements SelectorFactory {
        @Override
        public Optional<Selector> parse(Sexp expr, SelectorContext context) throws Exception {
            if (!expr.get(0).equals(SexpFactory.newAtomicSexp("name"))) {
                return Optional.empty();
            }

            PackageMajorVersion target =
                    expr.getLength() > 1
                            ? VersionSelector.resolve(context, expr.get(1))
                            : context.name();

            return Optional.of(new NameSelector(target.name()));
        }
    }

    @Override
    public String select() throws Exception {
        return name;
    }
}
