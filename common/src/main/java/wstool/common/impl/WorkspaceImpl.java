package wstool.common.impl;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import wstool.common.api.CodePackage;
import wstool.common.api.Workspace;

public class WorkspaceImpl implements Workspace {
    private final Path root;

    public WorkspaceImpl(Path root) {
        this.root = Objects.requireNonNull(root);
    }

    @Override
    public String name() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'name'");
    }

    @Override
    public Path root() {
        return root;
    }

    @Override
    public List<CodePackage> sources() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'sources'");
    }

    @Override
    public List<CodePackage> artifacts() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'artifacts'");
    }

    @Override
    public void clone(CodePackage pkg) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'clone'");
    }

    @Override
    public void remove(CodePackage pkg) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'remove'");
    }
}
