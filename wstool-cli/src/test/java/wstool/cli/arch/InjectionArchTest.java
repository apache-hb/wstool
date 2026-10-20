package wstool.cli.arch;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

@AnalyzeClasses(packages = {"wstool.cli", "wstool.simple"})
public class InjectionArchTest {
    @ArchTest
    public static final ArchRule NO_CLASSES_SHOULD_USE_FIELD_INJECTION =
            GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
}
