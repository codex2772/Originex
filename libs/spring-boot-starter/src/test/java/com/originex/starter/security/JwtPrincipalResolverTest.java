package com.originex.starter.security;

import com.originex.common.security.SubjectContext;
import com.originex.common.security.SubjectContext.PrincipalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JwtPrincipalResolver} classifies a verified token into the three v1
 * principal kinds and derives the {@code Authentication} principal name.
 */
@DisplayName("JwtPrincipalResolver")
class JwtPrincipalResolverTest {

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "RS256");
    }

    @Test
    @DisplayName("sub without customer_id → HUMAN_USER")
    void humanUser() {
        Optional<SubjectContext> p = JwtPrincipalResolver.resolve(jwt().subject("user-1").build());
        assertThat(p).isPresent();
        assertThat(p.get().type()).isEqualTo(PrincipalType.HUMAN_USER);
        assertThat(p.get().subject()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("sub with customer_id → CUSTOMER")
    void customer() {
        Optional<SubjectContext> p = JwtPrincipalResolver.resolve(
                jwt().subject("user-2").claim("customer_id", "cust-9").build());
        assertThat(p).isPresent();
        assertThat(p.get().type()).isEqualTo(PrincipalType.CUSTOMER);
        assertThat(p.get().customerId()).isEqualTo("cust-9");
    }

    @Test
    @DisplayName("no sub but azp → SERVICE_ACCOUNT (client id from azp)")
    void serviceAccountFromAzp() {
        Optional<SubjectContext> p = JwtPrincipalResolver.resolve(jwt().claim("azp", "svc-los").build());
        assertThat(p).isPresent();
        assertThat(p.get().type()).isEqualTo(PrincipalType.SERVICE_ACCOUNT);
        assertThat(p.get().subject()).isEqualTo("svc-los");
    }

    @Test
    @DisplayName("no sub, no azp, but client_id → SERVICE_ACCOUNT (fallback claim)")
    void serviceAccountFromClientId() {
        Optional<SubjectContext> p = JwtPrincipalResolver.resolve(jwt().claim("client_id", "svc-payment").build());
        assertThat(p).isPresent();
        assertThat(p.get().type()).isEqualTo(PrincipalType.SERVICE_ACCOUNT);
        assertThat(p.get().subject()).isEqualTo("svc-payment");
    }

    @Test
    @DisplayName("neither sub nor client id → no principal")
    void noIdentity() {
        assertThat(JwtPrincipalResolver.resolve(jwt().claim("scope", "x").build())).isEmpty();
    }

    @Test
    @DisplayName("principalName is sub for humans, client id for machines")
    void principalName() {
        assertThat(JwtPrincipalResolver.principalName(jwt().subject("user-1").build())).isEqualTo("user-1");
        assertThat(JwtPrincipalResolver.principalName(jwt().claim("azp", "svc-los").build())).isEqualTo("svc-los");
        assertThat(JwtPrincipalResolver.principalName(jwt().claim("scope", "x").build())).isNull();
    }
}
