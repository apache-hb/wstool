package wstool.sysroot.selectors;

import java.util.Optional;

import de.tudresden.inf.lat.jsexp.Sexp;
import de.tudresden.inf.lat.jsexp.SexpFactory;
import wstool.common.model.PackageMajorVersion;
import wstool.sysroot.Selector;
import wstool.sysroot.SelectorContext;
import wstool.sysroot.SelectorFactory;

/**
 * Given a package name finds its effective version in the provided context
 */
public class VersionSelector implements Selector {
    private final SelectorContext context;
    private final String name;

    public VersionSelector(SelectorContext context, String name) {
        this.context = context;
        this.name = name;
    }

    public static class VersionSelectorFactory implements SelectorFactory {
        @Override
        public Optional<Selector> parse(Sexp expr, SelectorContext context) throws Exception {
            // (version name)
            if (!expr.get(0).equals(SexpFactory.newAtomicSexp("version"))) {
                return Optional.empty();
            }

            return Optional.of(new VersionSelector(context, expr.get(1).toString()));
        }
    }

    @Override
    public String select() throws Exception {
        PackageMajorVersion target = context.workspace().find(name);
        return target.version();
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
