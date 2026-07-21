package com.originex.testsupport.arch;

import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Shared architecture fitness rules for the platform's authorization model
 * (see {@code dev/AUTH_DESIGN.md} §4.2, §4.5). They live here — not in one
 * service — so every service applies the same rules to its own packages:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "com.originex.customer", importOptions = DoNotIncludeTests.class)
 * class CustomerSecurityArchTest {
 *     @ArchTest
 *     static final ArchRule authorized = SecurityArchRules.USE_CASE_PORTS_REQUIRE_AUTHORIZATION;
 * }
 * }</pre>
 *
 * <p>The security annotations are referenced by fully-qualified <i>name</i>, so this
 * module needs no Spring Security dependency; the rules still match the real
 * annotations on the analyzed classes.
 *
 * <p><b>Deny-by-default.</b> {@link #USE_CASE_PORTS_REQUIRE_AUTHORIZATION} is the
 * build-time half of the platform's deny-by-default posture: a new use-case method
 * that ships without a capability check fails the build rather than silently
 * becoming reachable by any caller.
 */
public final class SecurityArchRules {

    /** Method-security annotations, matched by name (no Spring Security dependency here). */
    public static final String PRE_AUTHORIZE = "org.springframework.security.access.prepost.PreAuthorize";
    public static final String POST_AUTHORIZE = "org.springframework.security.access.prepost.PostAuthorize";

    private SecurityArchRules() {
    }

    /**
     * Every public method on an inbound use-case port must carry a capability check.
     * Ports are the authorization boundary, so an unannotated operation is a hole:
     * this fails the build instead of shipping an ungated use case.
     *
     * <p>Only interfaces in {@code ..application.port.in..} are considered — the
     * command/DTO records nested inside those ports are not use-case operations.
     */
    public static final ArchRule USE_CASE_PORTS_REQUIRE_AUTHORIZATION =
            methods()
                    .that().areDeclaredInClassesThat().resideInAPackage("..application.port.in..")
                    .and().areDeclaredInClassesThat().areInterfaces()
                    .and().arePublic()
                    .should().beAnnotatedWith(PRE_AUTHORIZE)
                    .orShould().beAnnotatedWith(POST_AUTHORIZE)
                    .because("use-case ports are the authorization boundary — an operation without a "
                            + "capability check would be reachable by any authenticated caller "
                            + "(dev/AUTH_DESIGN.md §4.5)");

    /**
     * Controllers must not reach persistence directly — that would bypass the
     * use-case port and, with it, the authorization (and tenant) boundary.
     */
    public static final ArchRule CONTROLLERS_DO_NOT_ACCESS_PERSISTENCE =
            noClasses()
                    .that().resideInAPackage("..adapter.in.rest..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out.persistence..")
                    .because("a controller that talks to repositories bypasses the authorized "
                            + "use-case port");

    /**
     * Controllers must go through the inbound port, not the application service
     * implementation, so the port's capability checks always apply.
     */
    public static final ArchRule CONTROLLERS_GO_THROUGH_USE_CASE_PORTS =
            noClasses()
                    .that().resideInAPackage("..adapter.in.rest..")
                    .should().dependOnClassesThat().resideInAPackage("..application.service..")
                    .because("controllers must depend on the use-case port, not its implementation, "
                            + "so authorization at the port is not bypassed");

    /**
     * Capability checks belong at the application boundary (the ports). Annotating
     * adapters or domain classes instead would leave other inbound adapters ungated
     * and scatter the policy.
     */
    public static final ArchRule SECURITY_ANNOTATIONS_ONLY_AT_APPLICATION_BOUNDARY =
            noMethods()
                    .that().areDeclaredInClassesThat().resideInAnyPackage("..adapter..", "..domain..")
                    .should().beAnnotatedWith(PRE_AUTHORIZE)
                    .orShould().beAnnotatedWith(POST_AUTHORIZE)
                    .because("authorization is declared once at the use-case port, so every inbound "
                            + "adapter passes the same gate (dev/AUTH_DESIGN.md §4.5)");
}
