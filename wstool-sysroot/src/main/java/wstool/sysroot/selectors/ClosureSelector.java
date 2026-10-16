package wstool.sysroot.selectors;

import java.util.Optional;

import de.tudresden.inf.lat.jsexp.Sexp;
import de.tudresden.inf.lat.jsexp.SexpFactory;
import wstool.common.model.PackageMajorVersion;
import wstool.sysroot.Selector;
import wstool.sysroot.SelectorContext;
import wstool.sysroot.SelectorFactory;

/**
 * Builds a closure of the given type and returns a path to it
 */
public class ClosureSelector implements Selector {
    private final String type;
    private final PackageMajorVersion target;

    public ClosureSelector(String type, PackageMajorVersion target) {
        this.type = type;
        this.target = target;
    }

    public static class ClosureSelectorFactory implements SelectorFactory {
        @Override
        public Optional<Selector> parse(Sexp expr, SelectorContext context) throws Exception {
            // (closure type (name version?))
            if (!expr.get(0).equals(SexpFactory.newAtomicSexp("closure"))) {
                return Optional.empty();
            }

            String type = expr.get(1).toString();
            PackageMajorVersion target = expr.getLength() > 2 ? VersionSelector.resolve(context, expr.get(2)) : context.name();

            return Optional.of(new ClosureSelector(type, target));
        }
    }

    @Override
    public String select() throws Exception {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'select'");
    }
}
