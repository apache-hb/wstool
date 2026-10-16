package wstool.sysroot.selectors;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import de.tudresden.inf.lat.jsexp.Sexp;
import de.tudresden.inf.lat.jsexp.SexpFactory;
import wstool.common.model.PackageMajorVersion;
import wstool.common.workspace.LocalWorkspace;
import wstool.sysroot.Closure;
import wstool.sysroot.ClosureContext;
import wstool.sysroot.ClosureFactory;
import wstool.sysroot.Selector;
import wstool.sysroot.SelectorContext;
import wstool.sysroot.SelectorFactory;

/**
 * Runs a colon-separated list of paths for a given closure; use ClosureSelector to build a closure
 * comprised of all the packages instead
 */
public class PathSelector implements Selector {
    private final Closure closure;
    private final LocalWorkspace workspace;
    private final PackageMajorVersion target;

    public PathSelector(Closure closure, LocalWorkspace workspace, PackageMajorVersion target) {
        this.closure = closure;
        this.workspace = workspace;
        this.target = target;
    }

    public static class PathSelectorFactory implements SelectorFactory {
        private final ClosureFactory closureFactory;

        public PathSelectorFactory(ClosureFactory closureFactory) {
            this.closureFactory = closureFactory;
        }

        @Override
        public Optional<Selector> parse(Sexp expr, SelectorContext context) throws Exception {
            if (expr.isAtomic()
                    || expr.getLength() < 2
                    || expr.getLength() > 3
                    || !expr.get(0).equals(SexpFactory.newAtomicSexp("path"))) {
                return Optional.empty();
            }

            PackageMajorVersion target =
                    expr.getLength() > 2
                            ? VersionSelector.resolve(context, expr.get(2))
                            : context.name();
            Closure closure = closureFactory.parse(expr.get(1), new ClosureContext(context.workspace(), target))
                    .orElseThrow(
                            () ->
                                    new IllegalArgumentException(
                                            "Unknown closure expression: " + expr.get(1)));

            return Optional.of(new PathSelector(closure, context.workspace(), target));
        }
    }

    @Override
    public String select() throws Exception {
        Set<PackageMajorVersion> packages = closure.collect();
        List<Path> paths = new ArrayList<>();
        Path localBin = workspace.source(target).resolve("wstool/bin");
        if (Files.isDirectory(localBin)) {
            paths.add(localBin);
        }
        for (PackageMajorVersion name : packages) {
            if (!name.equals(target)) {
                Path bin = workspace.prefix(name).resolve("bin");
                if (Files.isDirectory(bin)) {
                    paths.add(bin);
                }
            }
        }
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("No paths found for package " + target);
        }
        return paths.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }
}
