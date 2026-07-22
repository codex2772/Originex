package com.originex.bre;

import com.originex.testsupport.arch.SecurityArchRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Applies the platform's shared authorization fitness rules
 * ({@link SecurityArchRules}) to bre-service.
 *
 * <p>Unlike notification (a portless side-effect sink, where these rules would be a vacuous pass), bre has
 * a real inbound port — {@code EvaluationUseCase} — and a controller, so every rule here is load-bearing.
 * {@code use_case_ports_require_authorization} is the one that earns its keep: it fails the build if a new
 * operation is added to the port without a capability check. That guard matters most for the operation that
 * does <b>not</b> exist yet — rule authoring/management — so that if a rule-management method is ever added
 * to this port, it cannot ship ungated (it must declare its own elevated scope, not reuse
 * {@code decisioning:evaluate}).
 */
@AnalyzeClasses(
        packages = "com.originex.bre",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class BreSecurityArchTest {

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
