package wstool.sysroot.selectors;

import de.tudresden.inf.lat.jsexp.Sexp;
import java.util.List;
import java.util.Optional;
import wstool.sysroot.Selector;
import wstool.sysroot.SelectorContext;
import wstool.sysroot.SelectorFactory;

public class AggregateSelectorFactory implements SelectorFactory {
    private final List<SelectorFactory> factories;

    public AggregateSelectorFactory(List<SelectorFactory> factories) {
        this.factories = factories;
    }

    @Override
    public Optional<Selector> parse(Sexp expr, SelectorContext context) throws Exception {
        for (SelectorFactory factory : factories) {
            var selector = factory.parse(expr, context);
            if (selector.isPresent()) {
                return selector;
            }
        }

        return Optional.empty();
    }
}
