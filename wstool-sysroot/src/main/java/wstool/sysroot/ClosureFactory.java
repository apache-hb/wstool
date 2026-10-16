package wstool.sysroot;

import java.util.Optional;

import de.tudresden.inf.lat.jsexp.Sexp;

public interface ClosureFactory {
    Optional<Closure> parse(Sexp expr, ClosureContext context) throws Exception;
}
