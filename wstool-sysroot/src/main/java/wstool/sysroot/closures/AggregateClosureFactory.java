package wstool.sysroot.closures;

import java.util.List;
import java.util.Optional;

import de.tudresden.inf.lat.jsexp.Sexp;
import wstool.sysroot.Closure;
import wstool.sysroot.ClosureContext;
import wstool.sysroot.ClosureFactory;

public class AggregateClosureFactory implements ClosureFactory {
    private final List<ClosureFactory> factories;

    public AggregateClosureFactory(List<ClosureFactory> factories) {
        this.factories = factories;
    }

    @Override
    public Optional<Closure> parse(Sexp expr, ClosureContext context) throws Exception {
        for (ClosureFactory factory : factories) {
            var result = factory.parse(expr, context);
            if (result.isPresent()) {
                return result;
            }
        }

        return Optional.empty();
    }
}
