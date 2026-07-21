package com.originex.payment;

import com.originex.testsupport.arch.SecurityArchRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Applies the shared authorization fitness rules ({@link SecurityArchRules}) to payment-service —
 * Phase-1 authorization canary #3. Notably, {@code USE_CASE_PORTS_REQUIRE_AUTHORIZATION} forces even
 * the gateway webhook ({@code handlePaymentCallback}) to carry a guard, which is why it gets the
 * machine-only {@code payments:callback} scope rather than being left unauthorized.
 */
@AnalyzeClasses(
        packages = "com.originex.payment",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class PaymentSecurityArchTest {

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
