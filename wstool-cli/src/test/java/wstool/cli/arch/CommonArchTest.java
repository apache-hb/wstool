package wstool.cli.arch;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.GeneralCodingRules;

@AnalyzeClasses(packages = "wstool.cli")
public class CommonArchTest {
    @ArchTest
    public static final ArchRule NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS =
            GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    public static final ArchRule NO_CLASSES_SHOULD_USE_JODATIME =
            GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME;

    @ArchTest
    public static final ArchRule OLD_DATE_AND_TIME_CLASSES_SHOULD_NOT_BE_USED =
            GeneralCodingRules.OLD_DATE_AND_TIME_CLASSES_SHOULD_NOT_BE_USED;

    private static final String[] LOGGING_PACKAGES =
            new String[] {
                "java.util.logging..",
                "org.slf4j..",
                "org.apache.log4j..",
                "org.jboss.logging..",
                "org.apache.commons.logging..",
            };

    @ArchTest
    public static final ArchRule ONLY_LOG4J2_SHOULD_BE_USED =
            ArchRuleDefinition.noClasses()
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(LOGGING_PACKAGES)
                    .because("Only log4j2 should be used");

    private static final String[] SHADED_PACKAGES =
            new String[] {
                "org.testcontainers.shaded..",
                "software.amazon.smithy.java.internal.shaded..",
                "software.amazon.awssdk.thirdparty..",
                "io.netty.util.internal.shaded..",
                "io.opentelemetry.context.internal.shaded..",
                "io.opentelemetry.sdk.trace.internal.shaded..",
                "io.pyroscope.vendor..",
                "com.tngtech.archunit.thirdparty..",
                "org.junit.jupiter.params.shadow..",
                "org.postgresql.shaded..",
                "org.snakeyaml.engine.external..",
                "tools.jackson.core.internal.shaded.."
            };

    @ArchTest
    public static final ArchRule NO_SHADED_PACKAGES =
            ArchRuleDefinition.noClasses()
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(SHADED_PACKAGES)
                    .because("Using shaded dependencies is fragile");
}
