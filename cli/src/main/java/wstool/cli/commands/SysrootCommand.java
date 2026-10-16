package wstool.cli.commands;

import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;

import de.tudresden.inf.lat.jsexp.SexpFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import wstool.cli.VersionProvider;
import wstool.common.packages.PackageFactory;
import wstool.common.workspace.LocalWorkspace;
import wstool.common.workspace.WorkspaceFactory;
import wstool.sysroot.ClosureFactory;
import wstool.sysroot.Selector;
import wstool.sysroot.SelectorContext;
import wstool.sysroot.SelectorFactory;
import wstool.sysroot.closures.AggregateClosureFactory;
import wstool.sysroot.closures.DirectClosure;
import wstool.sysroot.closures.NamedClosure;
import wstool.sysroot.selectors.AggregateSelectorFactory;
import wstool.sysroot.selectors.ClosureSelector;
import wstool.sysroot.selectors.PathSelector;
import wstool.sysroot.selectors.PrefixSelector;
import wstool.sysroot.selectors.VersionSelector;

@Command(
        name = "sysroot",
        description = "Create a sysroot from workspace package artifacts.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class SysrootCommand extends CommonCommand {
    @Parameters(
            index = "0",
            description =
                    "Selector s-expression, for example (closure build) or (closure runtime (name version)).")
    private String selector;

    @Option(
            names = "--output",
            description = "Directory to create the sysroot in.",
            required = true)
    private Path output;

    private final WorkspaceFactory workspaceFactory;
    private final PackageFactory packageFactory;
    private final SelectorFactory selectorFactory;

    @Inject
    public SysrootCommand(
            WorkspaceFactory workspaceFactory,
            PackageFactory packageFactory) {
        this.workspaceFactory = workspaceFactory;
        this.packageFactory = packageFactory;
        ClosureFactory closureFactory =
                new AggregateClosureFactory(
                        List.of(
                                new DirectClosure.DirectClosureFactory(),
                                new NamedClosure.NamedClosureFactory()));
        this.selectorFactory =
                new AggregateSelectorFactory(
                        List.of(
                                new PathSelector.PathSelectorFactory(closureFactory),
                                new ClosureSelector.ClosureSelectorFactory(closureFactory),
                                new PrefixSelector.PrefixSelectorFactory(),
                                new VersionSelector.VersionSelectorFactory()));
    }

    @Override
    public Integer call() throws Exception {
        LocalWorkspace workspace = workspaceFactory.findUp(cwd);
        workspace.refresh();

        SelectorContext context =
                new SelectorContext(workspace, packageFactory.findUp(cwd).name(), output);
        Selector parsed =
                selectorFactory
                        .parse(SexpFactory.parse(selector), context)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Unknown selector expression: " + selector));
        if (!(parsed instanceof ClosureSelector)) {
            throw new IllegalArgumentException("The sysroot command requires a (closure ...) selector.");
        }
        parsed.select();
        return 0;
    }
}
