package wstool.sysroot.selectors;

import java.util.Optional;

import de.tudresden.inf.lat.jsexp.Sexp;
import de.tudresden.inf.lat.jsexp.SexpFactory;
import wstool.common.model.PackageMajorVersion;
import wstool.sysroot.Selector;
import wstool.sysroot.SelectorContext;
import wstool.sysroot.SelectorFactory;

/**
 * Returns an absolute path to the given package install prefix
 */
public class PrefixSelector implements Selector {
    private final SelectorContext context;
    private final PackageMajorVersion target;

    private PrefixSelector(SelectorContext context, PackageMajorVersion target) {
        this.context = context;
        this.target = target;
    }

    public static class PrefixSelectorFactory implements SelectorFactory {
        @Override
        public Optional<Selector> parse(Sexp expr, SelectorContext context) throws Exception {
            // (prefix (name version?))
            if (!expr.get(0).equals(SexpFactory.newAtomicSexp("prefix"))) {
                return Optional.empty();
            }

            PackageMajorVersion target = VersionSelector.resolve(context, expr.get(1));
            return Optional.of(new PrefixSelector(context, target));
        }
    }

    @Override
    public String select() throws Exception {
        return context.workspace().prefix(target).toAbsolutePath().toString();
    }
}
