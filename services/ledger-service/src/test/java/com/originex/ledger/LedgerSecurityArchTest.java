package com.originex.ledger;

import com.originex.testsupport.arch.SecurityArchRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Applies the platform's shared authorization fitness rules ({@link SecurityArchRules}) to
 * ledger-service — canary #2 of the Phase-1 authorization rollout. Fails the build if the
 * authorization boundary erodes: a new use-case operation without a capability guard, a controller
 * reaching past the port into persistence, or security annotations drifting out of the application
 * layer. Same rules customer-service applies, now applied to {@code com.originex.ledger}.
 */
@AnalyzeClasses(
        packages = "com.originex.ledger",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class LedgerSecurityArchTest {

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
