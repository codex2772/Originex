package com.originex.starter.security;

import com.originex.common.security.SubjectContext;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

/**
 * Classifies a <b>verified</b> JWT into one of the three v1 principal kinds
 * ({@link SubjectContext.PrincipalType}) — the single place the platform decides
 * "human user vs customer vs service account". See {@code dev/AUTH_DESIGN.md} §4.1.
 *
 * <p>Classification (model per §4.1; the discriminator is centralized here so it
 * can be tuned to the Keycloak realm's token shape without touching callers):
 * <ol>
 *   <li>a {@code sub} present with a {@code customer_id} claim → {@code CUSTOMER};</li>
 *   <li>a {@code sub} present without {@code customer_id} → {@code HUMAN_USER};</li>
 *   <li>no {@code sub} but an {@code azp}/{@code client_id} → {@code SERVICE_ACCOUNT}
 *       (client-credentials machine identity);</li>
 *   <li>neither → no usable identity (the filter rejects the request).</li>
 * </ol>
 *
 * <p>This resolver only <i>identifies</i> the principal; it performs no
 * authorization (no roles/scopes/ownership).
 */
public final class JwtPrincipalResolver {

    /** Binds a borrower principal to its customer record. */
    static final String CUSTOMER_CLAIM = "customer_id";
    /** Authorized party — the client id in an OAuth2 token (RFC 7519/OIDC). */
    static final String AZP_CLAIM = "azp";
    /** Alternative client-id claim some IdPs emit for client-credentials tokens. */
    static final String CLIENT_ID_CLAIM = "client_id";

    private JwtPrincipalResolver() {
    }

    /**
     * Resolve the principal, or {@link Optional#empty()} if the token carries no
     * usable identity (no subject and no client id).
     */
    public static Optional<SubjectContext> resolve(Jwt jwt) {
        String subject = trimToNull(jwt.getSubject());
        if (subject != null) {
            String customerId = trimToNull(jwt.getClaimAsString(CUSTOMER_CLAIM));
            return Optional.of(customerId != null
                    ? SubjectContext.customer(subject, customerId)
                    : SubjectContext.user(subject));
        }
        String clientId = clientId(jwt);
        if (clientId != null) {
            return Optional.of(SubjectContext.serviceAccount(clientId));
        }
        return Optional.empty();
    }

    /**
     * The principal name for the Spring {@code Authentication}: the {@code sub} for
     * human principals, or the client id for service accounts; {@code null} if the
     * token carries neither.
     */
    public static String principalName(Jwt jwt) {
        String subject = trimToNull(jwt.getSubject());
        return subject != null ? subject : clientId(jwt);
    }

    private static String clientId(Jwt jwt) {
        String azp = trimToNull(jwt.getClaimAsString(AZP_CLAIM));
        return azp != null ? azp : trimToNull(jwt.getClaimAsString(CLIENT_ID_CLAIM));
    }

    private static String trimToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
