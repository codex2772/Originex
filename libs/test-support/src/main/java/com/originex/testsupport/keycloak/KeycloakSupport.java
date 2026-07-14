package com.originex.testsupport.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Reusable Testcontainers harness that starts the Originex dev Keycloak — the
 * <b>same</b> realm as {@code infra/keycloak/realm-export.json} — and mints JWTs
 * for integration tests (see {@code dev/AUTH_DESIGN.md} §3–§5).
 *
 * <p>Built on Testcontainers' {@link GenericContainer} directly (no third-party
 * Keycloak-testcontainer wrapper), so it adds no new Maven dependency. It needs
 * Docker, so tests using it are {@code *IntegrationTest} (failsafe /
 * {@code -Pintegration-test}) — they run in CI, not the unit build.
 *
 * <p>Single source of truth: the realm JSON is loaded from
 * {@code infra/keycloak/realm-export.json} (resolved by walking up from the
 * module's working directory), so the container and {@code docker-compose} import
 * exactly the same realm — including the {@code customers:read}/{@code customers:write}
 * scope catalog that must match {@code OriginexScopes} and the {@code @PreAuthorize}
 * authorities.
 *
 * <pre>{@code
 * try (GenericContainer<?> kc = KeycloakSupport.newContainer()) {
 *     kc.start();
 *     String issuer = KeycloakSupport.issuerUri(kc);          // -> resource-server issuer-uri
 *     String token  = KeycloakSupport.passwordToken(
 *             kc, "originex-web", "customer-alice", "password", "openid", "customers:read");
 * }
 * }</pre>
 */
public final class KeycloakSupport {

    /** Realm defined in {@code infra/keycloak/realm-export.json}. */
    public static final String REALM = "originex";

    private static final String IMAGE = "quay.io/keycloak/keycloak:26.0";
    private static final int KC_PORT = 8080;
    private static final String REALM_FILE = "infra/keycloak/realm-export.json";
    private static final String IMPORT_TARGET = "/opt/keycloak/data/import/realm-export.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private KeycloakSupport() {
    }

    /**
     * A Keycloak container that imports the Originex realm at startup and is ready
     * once its OIDC discovery document is served.
     */
    public static GenericContainer<?> newContainer() {
        return new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(KC_PORT)
                .withEnv("KEYCLOAK_ADMIN", "admin")
                .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
                .withCopyFileToContainer(MountableFile.forHostPath(resolveRealmFile()), IMPORT_TARGET)
                .withCommand("start-dev", "--import-realm")
                .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                        .forPort(KC_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    /** Base URL of the running Keycloak (host + mapped port). */
    public static String baseUrl(GenericContainer<?> keycloak) {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(KC_PORT);
    }

    /** OIDC issuer URI — feed to {@code originex.security.issuer-uri}. */
    public static String issuerUri(GenericContainer<?> keycloak) {
        return baseUrl(keycloak) + "/realms/" + REALM;
    }

    /** JWKS URI — feed to {@code originex.security.jwk-set-uri}. */
    public static String jwkSetUri(GenericContainer<?> keycloak) {
        return issuerUri(keycloak) + "/protocol/openid-connect/certs";
    }

    private static String tokenEndpoint(GenericContainer<?> keycloak) {
        return issuerUri(keycloak) + "/protocol/openid-connect/token";
    }

    /**
     * Mint an access token via the OAuth2 password grant (Direct Access Grants),
     * requesting the given scopes. Use for human principals (e.g.
     * {@code originex-web} + {@code customer-alice}).
     */
    public static String passwordToken(GenericContainer<?> keycloak, String clientId,
                                       String username, String password, String... scopes) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "password");
        form.put("client_id", clientId);
        form.put("username", username);
        form.put("password", password);
        if (scopes.length > 0) {
            form.put("scope", String.join(" ", scopes));
        }
        return requestToken(keycloak, form);
    }

    /**
     * Mint an access token via the client_credentials grant. Use for machine /
     * service-account principals (e.g. {@code svc-customer}).
     */
    public static String clientCredentialsToken(GenericContainer<?> keycloak, String clientId,
                                                String clientSecret, String... scopes) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        if (scopes.length > 0) {
            form.put("scope", String.join(" ", scopes));
        }
        return requestToken(keycloak, form);
    }

    private static String requestToken(GenericContainer<?> keycloak, Map<String, String> form) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint(keycloak)))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(urlEncode(form)))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Keycloak token request failed (" + response.statusCode() + "): " + response.body());
            }
            JsonNode body = MAPPER.readTree(response.body());
            JsonNode token = body.get("access_token");
            if (token == null || token.asText().isBlank()) {
                throw new IllegalStateException("No access_token in token response: " + response.body());
            }
            return token.asText();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to obtain token from Keycloak", e);
        }
    }

    /**
     * Decode a JWT's payload (claims) without verifying the signature — for
     * asserting claim contents in tests. The token's signature is validated by the
     * resource server under test; here we only inspect what Keycloak minted.
     */
    public static JsonNode decodeClaims(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Not a JWT: " + jwt);
            }
            byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
            return MAPPER.readTree(payload);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode JWT claims", e);
        }
    }

    private static String urlEncode(Map<String, String> form) {
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> e : form.entrySet()) {
            joiner.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return joiner.toString();
    }

    /**
     * Locate {@code infra/keycloak/realm-export.json} by walking up from the
     * current working directory (the module dir under Maven), so the container
     * imports the same realm the {@code docker-compose} setup does.
     */
    static Path resolveRealmFile() {
        Path dir = Paths.get("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path candidate = p.resolve(REALM_FILE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate " + REALM_FILE + " from " + dir);
    }
}
