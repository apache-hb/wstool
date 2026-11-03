package wstool.sysroot.selectors;

import de.tudresden.inf.lat.jsexp.Sexp;
import de.tudresden.inf.lat.jsexp.SexpFactory;
import java.util.Optional;
import wstool.common.model.PackageMajorVersion;
import wstool.sysroot.Selector;
import wstool.sysroot.SelectorContext;
import wstool.sysroot.SelectorFactory;

/** Given a package name finds its effective version in the provided context */
public class VersionSelector implements Selector {
    private final PackageMajorVersion pmv;

    public VersionSelector(PackageMajorVersion pmv) {
        this.pmv = pmv;
    }

    public static class VersionSelectorFactory implements SelectorFactory {
        @Override
        public Optional<Selector> parse(Sexp expr, SelectorContext context) throws Exception {
            // (version name)
            if (!expr.get(0).equals(SexpFactory.newAtomicSexp("version"))) {
                return Optional.empty();
            }

            PackageMajorVersion target =
                    expr.getLength() > 1
                            ? VersionSelector.resolve(context, expr.get(1))
                            : context.name();

            return Optional.of(new VersionSelector(target));
        }
    }

    @Override
    public String select() throws Exception {
        return pmv.version();
    }

    public static PackageMajorVersion resolve(SelectorContext context, Sexp expr) throws Exception {
        if (expr.isAtomic()) {
            return context.workspace().find(expr.toString());
        }

        if (expr.getLength() == 1) {
            return context.workspace().find(expr.get(0).toString());
        }

        if (expr.getLength() == 2) {
            String name = expr.get(0).toString();
            String version = expr.get(1).toString();
            return new PackageMajorVersion(name, version);
        }

        throw new IllegalArgumentException("Invalid version expression: " + expr);
    }
}
