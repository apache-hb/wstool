package wstool.importer;

import picocli.CommandLine.IVersionProvider;

public class ImporterVersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
        String version = System.getProperty("wstool.version");
        if (version == null) {
            version = Main.class.getPackage().getImplementationVersion();
        }
        return new String[] {"wstool-import " + (version == null ? "development" : version)};
    }
}
