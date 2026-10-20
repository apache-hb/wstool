package wstool.cli;

import picocli.CommandLine.IVersionProvider;

public class VersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
        String version = System.getProperty("wstool.version");
        if (version == null) {
            version = Main.class.getPackage().getImplementationVersion();
        }
        return new String[] {"wstool " + (version == null ? "development" : version)};
    }
}
