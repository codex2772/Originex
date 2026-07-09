package com.originex.template;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture fitness tests — enforces hexagonal architecture layer dependencies.
 * These tests run as part of the regular test suite and fail the build on violations.
 */
@AnalyzeClasses(
        packages = "com.originex.template",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class HexagonalArchitectureTest {

    /**
     * Domain layer must not depend on application or adapter layers.
     * Domain is the innermost ring — it knows nothing about the outside world.
     */
    @ArchTest
    static final ArchRule domain_should_not_depend_on_application =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..application..", "..adapter..", "..config..");

    /**
     * Application layer must not depend on adapter layer.
     * Application orchestrates domain objects via ports — never touches infrastructure directly.
     */
    @ArchTest
    static final ArchRule application_should_not_depend_on_adapters =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..adapter..");

    /**
     * Domain layer must not depend on Spring Framework.
     * Domain model is framework-agnostic — pure Java.
     */
    @ArchTest
    static final ArchRule domain_should_not_use_spring =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.springframework..");

    /**
     * Inbound adapters (REST controllers, Kafka consumers) should only access
     * application ports — never domain repositories or other adapters directly.
     */
    @ArchTest
    static final ArchRule inbound_adapters_should_use_ports =
            noClasses().that().resideInAPackage("..adapter.in..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..adapter.out..");
}
