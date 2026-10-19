package wstool.sysroot;

import de.tudresden.inf.lat.jsexp.Sexp;
import java.util.Optional;

public interface SelectorFactory {
    Optional<Selector> parse(Sexp expr, SelectorContext context) throws Exception;
}
