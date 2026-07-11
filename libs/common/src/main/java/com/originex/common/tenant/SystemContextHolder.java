package com.originex.common.tenant;

/**
 * Marks the current (virtual) thread as running system / cross-tenant work —
 * background jobs (accrual, DPD, notification & payment retries) and migrations
 * that must operate across all tenants rather than be scoped to one.
 *
 * <p>Companion to {@link TenantContextHolder}, following the same set-use-clear
 * discipline. When row-level security enforcement is active, work run in system
 * context is routed to the {@code BYPASSRLS} datasource and no
 * {@code app.tenant_id} is applied; when RLS is disabled this holder has no
 * effect. See {@code dev/RLS_DESIGN.md} §7.
 *
 * <pre>
 *   SystemContextHolder.runAsSystem(() -&gt; {
 *       // cross-tenant eligibility scan + per-item processing
 *   });
 * </pre>
 */
public final class SystemContextHolder {

    private static final ThreadLocal<Boolean> SYSTEM = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private SystemContextHolder() {
        // Utility class
    }

    /** Enter system context for the current thread. */
    public static void enter() {
        SYSTEM.set(Boolean.TRUE);
    }

    /** Exit system context for the current thread (must run in a finally block). */
    public static void exit() {
        SYSTEM.remove();
    }

    /** True when the current thread is running as a cross-tenant system worker. */
    public static boolean isSystemContext() {
        return Boolean.TRUE.equals(SYSTEM.get());
    }

    /**
     * Runs {@code action} in system context, restoring the prior state afterward
     * (safe to nest).
     */
    public static void runAsSystem(Runnable action) {
        boolean prior = isSystemContext();
        enter();
        try {
            action.run();
        } finally {
            if (!prior) {
                exit();
            }
        }
    }
}
