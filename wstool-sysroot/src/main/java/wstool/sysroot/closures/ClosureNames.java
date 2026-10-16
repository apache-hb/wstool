package wstool.sysroot.closures;

import wstool.common.model.DependencyType;

final class ClosureNames {
    private ClosureNames() {}

    static boolean supports(String name) {
        return name.equals("build")
                || name.equals("runtime")
                || name.equals("test")
                || name.equals("development");
    }

    static ClosureName from(String name) {
        return switch (name) {
            case "build" -> ClosureName.BUILD;
            case "runtime" -> ClosureName.RUNTIME;
            case "test", "development" -> ClosureName.TEST;
            default -> throw new IllegalArgumentException("Unknown closure: " + name);
        };
    }

    static boolean includes(ClosureName name, DependencyType type) {
        return switch (name) {
            case BUILD -> type == DependencyType.BUILD;
            case RUNTIME -> type == DependencyType.PUBLIC || type == DependencyType.RUNTIME;
            case TEST -> type == DependencyType.BUILD
                    || type == DependencyType.PUBLIC
                    || type == DependencyType.TEST
                    || type == DependencyType.RUNTIME;
        };
    }
}
