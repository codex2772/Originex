package com.originex.testsupport.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit guard for {@code infra/keycloak/realm-export.json} — runs in the normal
 * (surefire) build, no Docker required. Asserts the realm is well-formed and
 * carries the exact roles, scopes, claim mappers, clients and users the platform
 * expects, so a malformed or drifted realm is caught before the CI Keycloak
 * container (or a developer's {@code docker-compose}) tries to import it.
 *
 * <p><b>Single source of truth:</b> the scope names asserted here
 * ({@code customers:read} / {@code customers:write}) are the colon-form catalog
 * that must match {@code OriginexScopes} and the {@code @PreAuthorize} authorities
 * (cross-checked against the {@code OriginexScopes} constants in the
 * customer-service authorization tests, which have the starter on the classpath).
 */
@DisplayName("Keycloak realm export (infra/keycloak/realm-export.json)")
class KeycloakRealmExportTest {

    private static JsonNode realm() throws Exception {
        return new ObjectMapper().readTree(Files.readAllBytes(KeycloakSupport.resolveRealmFile()));
    }

    private static List<String> textList(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            array.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    @Test
    @DisplayName("realm is originex and well-formed")
    void realmIsOriginex() throws Exception {
        JsonNode realm = realm();
        assertThat(realm.get("realm").asText()).isEqualTo("originex");
        assertThat(realm.get("enabled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("carries the 9 OriginexRoles realm roles")
    void hasAllRealmRoles() throws Exception {
        List<String> roleNames = new ArrayList<>();
        realm().get("roles").get("realm").forEach(r -> roleNames.add(r.get("name").asText()));

        assertThat(roleNames).contains(
                "PLATFORM_ADMIN", "TENANT_ADMIN", "OPERATIONS", "UNDERWRITER", "COLLECTIONS",
                "FINANCE", "CUSTOMER_SUPPORT", "AUDITOR", "CUSTOMER");
    }

    @Test
    @DisplayName("client scopes include customers:read / customers:write, emitted in the scope claim")
    void hasCustomerScopes() throws Exception {
        JsonNode scopes = realm().get("clientScopes");
        List<String> scopeNames = new ArrayList<>();
        scopes.forEach(s -> scopeNames.add(s.get("name").asText()));
        assertThat(scopeNames).contains("customers:read", "customers:write");

        // Each capability scope must surface in the token's 'scope' claim -> SCOPE_ authority.
        scopes.forEach(s -> {
            String name = s.get("name").asText();
            if (name.equals("customers:read") || name.equals("customers:write")) {
                assertThat(s.path("attributes").path("include.in.token.scope").asText())
                        .as("scope %s must be included in the token scope claim", name)
                        .isEqualTo("true");
            }
        });
    }

    @Test
    @DisplayName("originex-tenant scope maps tenant_id and customer_id claims")
    void hasTenantAndCustomerClaimMappers() throws Exception {
        JsonNode tenantScope = null;
        for (JsonNode s : realm().get("clientScopes")) {
            if (s.get("name").asText().equals("originex-tenant")) {
                tenantScope = s;
                break;
            }
        }
        assertThat(tenantScope).as("originex-tenant client scope present").isNotNull();

        List<String> claimNames = new ArrayList<>();
        tenantScope.get("protocolMappers")
                .forEach(m -> claimNames.add(m.get("config").get("claim.name").asText()));
        assertThat(claimNames).contains("tenant_id", "customer_id");
    }

    @Test
    @DisplayName("human + machine clients configured for token minting")
    void hasClients() throws Exception {
        JsonNode web = null;
        JsonNode svc = null;
        for (JsonNode c : realm().get("clients")) {
            if (c.get("clientId").asText().equals("originex-web")) web = c;
            if (c.get("clientId").asText().equals("svc-customer")) svc = c;
        }
        assertThat(web).isNotNull();
        assertThat(web.get("directAccessGrantsEnabled").asBoolean()).as("password grant for dev/tests").isTrue();
        assertThat(textList(web.get("optionalClientScopes"))).contains("customers:read", "customers:write");

        assertThat(svc).isNotNull();
        assertThat(svc.get("serviceAccountsEnabled").asBoolean()).as("client_credentials").isTrue();
    }

    @Test
    @DisplayName("example users carry tenant_id (and customer_id for borrowers)")
    void hasExampleUsers() throws Exception {
        JsonNode alice = null;
        for (JsonNode u : realm().get("users")) {
            if (u.get("username").asText().equals("customer-alice")) alice = u;
        }
        assertThat(alice).as("customer-alice present").isNotNull();
        assertThat(alice.path("attributes").path("tenant_id").get(0).asText())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(alice.path("attributes").path("customer_id").get(0).asText())
                .isEqualTo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertThat(textList(alice.get("realmRoles"))).contains("CUSTOMER");
    }
}
