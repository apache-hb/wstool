package wstool.sysroot;

import java.util.Optional;

import de.tudresden.inf.lat.jsexp.Sexp;

public interface SelectorFactory {
    Optional<Selector> parse(Sexp expr, SelectorContext context) throws Exception;
}
