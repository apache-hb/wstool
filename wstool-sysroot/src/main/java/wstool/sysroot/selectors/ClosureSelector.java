package wstool.sysroot.selectors;

import de.tudresden.inf.lat.jsexp.Sexp;
import de.tudresden.inf.lat.jsexp.SexpFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import wstool.common.model.PackageMajorVersion;
import wstool.sysroot.Closure;
import wstool.sysroot.ClosureContext;
import wstool.sysroot.ClosureFactory;
import wstool.sysroot.Selector;
import wstool.sysroot.SelectorContext;
import wstool.sysroot.SelectorFactory;
import wstool.sysroot.SymlinkFolder;

/** Builds a closure of the given type and returns a path to it */
public class ClosureSelector implements Selector {
    private final Closure closure;
    private final SelectorContext context;
    private final String key;

    public ClosureSelector(Closure closure, SelectorContext context, String key) {
        this.closure = closure;
        this.context = context;
        this.key = key;
    }

    public static class ClosureSelectorFactory implements SelectorFactory {
        private final ClosureFactory closureFactory;

        public ClosureSelectorFactory(ClosureFactory closureFactory) {
            this.closureFactory = closureFactory;
        }

        @Override
        public Optional<Selector> parse(Sexp expr, SelectorContext context) throws Exception {
            if (expr.isAtomic()
                    || expr.getLength() < 2
                    || expr.getLength() > 3
                    || !expr.get(0).equals(SexpFactory.newAtomicSexp("closure"))) {
                return Optional.empty();
            }

            PackageMajorVersion target =
                    expr.getLength() > 2
                            ? VersionSelector.resolve(context, expr.get(2))
                            : context.name();
            Closure closure =
                    closureFactory
                            .parse(expr.get(1), new ClosureContext(context.workspace(), target))
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Unknown closure expression: " + expr.get(1)));

            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(expr.toString().getBytes(StandardCharsets.UTF_8));
            String key = HexFormat.of().formatHex(hash);

            return Optional.of(
                    new ClosureSelector(
                            closure,
                            new SelectorContext(context.workspace(), target),
                            key));
        }
    }

    @Override
    public String select() throws Exception {
        Path output = context.workspace().selectorCache(context.name(), key);
        SymlinkFolder.create(context.workspace(), closure.collect(), output);
        return output.toAbsolutePath().toString();
    }
}
