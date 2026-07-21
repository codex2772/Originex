package com.originex.starter.security;

/**
 * Authentication posture when {@code originex.security.enabled=true}
 * (see {@code dev/AUTH_DESIGN.md} §8). Stage-0 "DISABLED" is expressed by the
 * master switch {@code originex.security.enabled=false}, so only the two
 * meaningful enabled postures are modelled here.
 */
public enum AuthMode {

    /**
     * Migration observe/soak state. A present token is validated and tenant/subject
     * are derived from its claims; when <b>no</b> token is present the request is not
     * rejected and may fall back to the {@code X-Tenant-Id} header — but only from a
     * trusted source network (empty allowlist ⇒ no fallback). Claim/header mismatches
     * are recorded. Bounded and monitored (§8.2); not a steady state.
     */
    PERMISSIVE,

    /**
     * Steady state. Authentication is required (unauthenticated ⇒ 401), tenant and
     * subject come only from verified claims, and {@code X-Tenant-Id} is ignored.
     */
    ENFORCED
}
