package wstool.cli.commands;

import de.tudresden.inf.lat.jsexp.SexpFactory;
import jakarta.inject.Inject;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import wstool.cli.VersionProvider;
import wstool.common.packages.PackageConfig;
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
        name = "select",
        description = "Select packages from a workspace.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class SelectCommand extends CommonCommand {
    @Spec private CommandSpec spec;

    @Parameters(
            index = "0",
            description =
                    "Selector s-expression, for example (closure build) or (closure runtime (name version)).")
    private String selector;

    private final WorkspaceFactory workspaceFactory;
    private final PackageFactory packageFactory;
    private final SelectorFactory selectorFactory;

    @Inject
    public SelectCommand(WorkspaceFactory workspaceFactory, PackageFactory packageFactory) {
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
                                new ClosureSelector.ClosureSelectorFactory(closureFactory),
                                new PathSelector.PathSelectorFactory(closureFactory),
                                new PrefixSelector.PrefixSelectorFactory(),
                                new VersionSelector.VersionSelectorFactory()));
    }

    @Override
    public Integer call() throws Exception {
        LocalWorkspace workspace = workspaceFactory.findUp(cwd);
        workspace.refresh();
        PackageConfig pkg = packageFactory.findUp(cwd);

        SelectorContext context = new SelectorContext(workspace, pkg.name());

        Selector parsed =
                selectorFactory
                        .parse(SexpFactory.parse(selector), context)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Unknown selector expression: " + selector));

        spec.commandLine().getOut().println(parsed.select());

        return 0;
    }
}
