package com.originex.lms;

import com.originex.testsupport.arch.SecurityArchRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Applies the platform's shared authorization fitness rules
 * ({@link SecurityArchRules}) to lms-service — the fifth Phase-1 canary.
 *
 * <p>Deny-by-default earns its keep here: lms's port carries a five-way split
 * (read / create / disburse / service / repay-manual), and the last of those is
 * a fraud-sensitive capability. Any new port method must declare which it requires,
 * or the build fails.
 */
@AnalyzeClasses(
        packages = "com.originex.lms",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class LmsSecurityArchTest {

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
