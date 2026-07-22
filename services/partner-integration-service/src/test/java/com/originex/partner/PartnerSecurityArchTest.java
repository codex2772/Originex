package com.originex.partner;

import com.originex.testsupport.arch.SecurityArchRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Applies the platform's shared authorization fitness rules ({@link SecurityArchRules}) to
 * partner-integration-service. Real inbound port ({@code PartnerIntegrationUseCase}) + controller, so every
 * rule is load-bearing: {@code use_case_ports_require_authorization} fails the build if any of the four
 * vendor operations loses its capability check.
 */
@AnalyzeClasses(
        packages = "com.originex.partner",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class PartnerSecurityArchTest {

    @ArchTest
    static final ArchRule use_case_ports_require_authorization =
            SecurityArchRules.USE_CASE_PORTS_REQUIRE_AUTHORIZATION;

    @ArchTest
    static final ArchRule controllers_do_not_access_persistence =
            SecurityArchRules.CONTROLLERS_DO_NOT_ACCESS_PERSISTENCE;

    @ArchTest
    static final ArchRule controllers_go_through_use_case_ports =
            SecurityArchRules.CONTROLLERS_GO_THROUGH_USE_CASE_PORTS;

    @ArchTest
    static final ArchRule security_annotations_only_at_application_boundary =
            SecurityArchRules.SECURITY_ANNOTATIONS_ONLY_AT_APPLICATION_BOUNDARY;
}
