package com.originex.testsupport.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link KeycloakSupport} starts the Originex realm and mints usable JWTs
 * with the required claim shape ({@code sub}, {@code tenant_id}, {@code customer_id},
 * {@code scope}, realm roles). <b>Requires Docker</b> (pulls the Keycloak image),
 * so it is named {@code *IntegrationTest} and tagged {@code keycloak}: it runs under
 * {@code mvn verify -Pintegration-test} in CI, not the unit build.
 */
@Tag("keycloak")
@DisplayName("KeycloakSupport (Testcontainers)")
class KeycloakSupportIntegrationTest {

    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String ALICE_CUSTOMER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Test
    @DisplayName("password grant for customer-alice yields a JWT with tenant_id, customer_id, scope and role")
    void mintsCustomerToken() {
        try (GenericContainer<?> keycloak = KeycloakSupport.newContainer()) {
            keycloak.start();

            String token = KeycloakSupport.passwordToken(
                    keycloak, "originex-web", "customer-alice", "password", "openid", "customers:read");

            JsonNode claims = KeycloakSupport.decodeClaims(token);
            assertThat(claims.get("sub").asText()).isNotBlank();
            assertThat(claims.get("tenant_id").asText()).isEqualTo(TENANT_A);
            assertThat(claims.get("customer_id").asText()).isEqualTo(ALICE_CUSTOMER_ID);
            assertThat(claims.get("scope").asText()).contains("customers:read");
            assertThat(claims.path("realm_access").path("roles").toString()).contains("CUSTOMER");
        }
    }

    @Test
    @DisplayName("client_credentials for svc-customer yields a machine token (azp, no customer_id)")
    void mintsServiceAccountToken() {
        try (GenericContainer<?> keycloak = KeycloakSupport.newContainer()) {
            keycloak.start();

            String token = KeycloakSupport.clientCredentialsToken(
                    keycloak, "svc-customer", "svc-customer-secret", "customers:read");

            JsonNode claims = KeycloakSupport.decodeClaims(token);
            assertThat(claims.get("azp").asText()).isEqualTo("svc-customer");
            assertThat(claims.get("scope").asText()).contains("customers:read");
        }
    }
}
