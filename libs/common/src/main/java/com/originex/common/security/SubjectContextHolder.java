package com.originex.common.security;

/**
 * Thread-safe holder for the current authenticated {@link SubjectContext},
 * following the same set-use-clear discipline as
 * {@link com.originex.common.tenant.TenantContextHolder}:
 * <pre>
 *   SubjectContextHolder.set(subjectContext);
 *   try {
 *       // request scope — code here sees the authenticated principal
 *   } finally {
 *       SubjectContextHolder.clear();
 *   }
 * </pre>
 *
 * <p>In a web environment this is managed by the resource-server tenant/subject
 * resolution filter (set only when {@code originex.security.enabled=true}).
 */
public final class SubjectContextHolder {

    private static final ThreadLocal<SubjectContext> CONTEXT = new ThreadLocal<>();

    private SubjectContextHolder() {
        // Utility class
    }

    /** Set the subject context for the current thread/virtual-thread. */
    public static void set(SubjectContext context) {
        CONTEXT.set(context);
    }

    /** Current subject context, or {@code null} if unauthenticated / not set. */
    public static SubjectContext get() {
        return CONTEXT.get();
    }

    /** Clear the subject context (must be called in a finally block). */
    public static void clear() {
        CONTEXT.remove();
    }
}
