package com.originex.common.tenant;

/**
 * Thread-safe holder for the current tenant context.
 *
 * <p>With Java 21 virtual threads, ThreadLocal remains safe as long as each virtual thread
 * sets its own value. This holder follows a set-use-clear pattern:
 * <pre>
 *   TenantContextHolder.set(tenantContext);
 *   try {
 *       // business logic — all code in this scope sees the tenant
 *   } finally {
 *       TenantContextHolder.clear();
 *   }
 * </pre>
 *
 * <p>In a web environment, this is managed by the TenantResolutionFilter in the Spring Boot starter.
 * In Kafka consumers, it is set by the TenantAwareKafkaConsumer.
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CONTEXT = new ThreadLocal<>();

    private TenantContextHolder() {
        // Utility class
    }

    /**
     * Set the tenant context for the current thread/virtual-thread.
     */
    public static void set(TenantContext context) {
        CONTEXT.set(context);
    }

    /**
     * Get the current tenant context. Returns null if not set.
     */
    public static TenantContext get() {
        return CONTEXT.get();
    }

    /**
     * Get the current tenant context or throw if not set.
     */
    public static TenantContext require() {
        TenantContext ctx = CONTEXT.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "TenantContext not set. Ensure TenantResolutionFilter is active.");
        }
        return ctx;
    }

    /**
     * Get the current tenant ID or throw if not set.
     */
    public static String requireTenantId() {
        return require().tenantId();
    }

    /**
     * Clear the tenant context (must be called in finally block).
     */
    public static void clear() {
        CONTEXT.remove();
    }
}
