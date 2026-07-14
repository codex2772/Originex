# Local Keycloak (dev IdP) — Originex Phase 1

Dev-only Keycloak that provides the JWTs the platform's OAuth2 resource server
validates (`dev/AUTH_DESIGN.md` §3–§5). It imports [`realm-export.json`](realm-export.json)
(realm **`originex`**) on startup. **Dev-only:** HTTP, throwaway admin creds,
in-memory store — not a production configuration.

## Start / stop

```bash
docker compose -f infra/keycloak/docker-compose.yml up      # start (foreground)
docker compose -f infra/keycloak/docker-compose.yml down    # stop + remove
```

- Admin console: <http://localhost:8180> (admin / admin)
- Issuer URI: `http://localhost:8180/realms/originex`
- JWKS: `http://localhost:8180/realms/originex/protocol/openid-connect/certs`

## Point a service at it

Enable security on a service (customer-service is the Phase-1 canary) and set
the issuer — the resource server discovers the JWKS from it:

```yaml
originex:
  security:
    enabled: true
    issuer-uri: http://localhost:8180/realms/originex
    audience: svc-customer   # optional; per-service audience if configured
```

With `originex.security.enabled=false` (default) none of this is used and
behaviour is unchanged.

## What's in the realm

**Single source of truth:** the client scopes here are the colon-form catalog in
`OriginexScopes` / `dev/AUTH_DESIGN.md §4.4` and must match the `@PreAuthorize`
authorities applied in Commit 4. Adding a scope means adding it in **both** places.

| Item | Value |
|---|---|
| Realm | `originex` |
| Realm roles | the 9 `OriginexRoles` (PLATFORM_ADMIN … CUSTOMER) → `ROLE_*` via `KeycloakRealmRoleConverter` |
| Client scopes | `customers:read`, `customers:write` (→ `SCOPE_customers:read` / `SCOPE_customers:write`) |
| Claim mappers | `tenant_id`, `customer_id` (from user attributes) → access-token claims |
| Human client | `originex-web` (public; Auth Code+PKCE; Direct Access Grants enabled for dev) |
| Machine client | `svc-customer` (confidential; `client_credentials`; secret `svc-customer-secret`) |

**Example users** (password `password`):

| Username | tenant_id | customer_id | Role |
|---|---|---|---|
| `customer-alice` | `1111…1111` (Tenant A) | `aaaa…aaaa` | CUSTOMER |
| `customer-bob` | `2222…2222` (Tenant B) | `bbbb…bbbb` | CUSTOMER |
| `staff-uma` | `1111…1111` (Tenant A) | — | UNDERWRITER |

## Mint a token

**Human (password grant), requesting a scope:**

```bash
curl -s http://localhost:8180/realms/originex/protocol/openid-connect/token \
  -d grant_type=password \
  -d client_id=originex-web \
  -d username=customer-alice -d password=password \
  -d 'scope=openid customers:read' | jq -r .access_token
```

Decode the JWT payload and you'll see (per the required claim shape):

```json
{ "sub": "<uuid>", "tenant_id": "11111111-1111-1111-1111-111111111111",
  "customer_id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  "scope": "openid customers:read", "realm_access": { "roles": ["CUSTOMER", "..."] } }
```

**Service account (client credentials):**

```bash
curl -s http://localhost:8180/realms/originex/protocol/openid-connect/token \
  -d grant_type=client_credentials \
  -d client_id=svc-customer -d client_secret=svc-customer-secret \
  -d 'scope=customers:read' | jq -r .access_token
```

**Call a secured service:**

```bash
TOKEN=$(...as above...)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/v1/customers/<id>
```

## Automated tests

`libs/test-support` provides `KeycloakSupport`, a Testcontainers helper (built on
`GenericContainer`, no extra Maven dependency) that starts this same realm and
mints tokens. It is exercised by CI integration tests (`*IntegrationTest`,
Testcontainers) — it needs Docker, so it runs under `mvn verify -Pintegration-test`,
not the unit build. The realm file's structure is guarded by a plain unit test
(`KeycloakRealmExportTest`).
