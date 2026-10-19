package wstool.sysroot;

import de.tudresden.inf.lat.jsexp.Sexp;
import java.util.Optional;

public interface ClosureFactory {
    Optional<Closure> parse(Sexp expr, ClosureContext context) throws Exception;
}
