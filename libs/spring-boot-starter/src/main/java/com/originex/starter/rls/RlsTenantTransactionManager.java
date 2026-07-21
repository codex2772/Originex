package com.originex.starter.rls;

import com.originex.common.tenant.SystemContextHolder;
import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link JpaTransactionManager} that scopes every transaction to the current
 * tenant for PostgreSQL row-level security. Immediately after the JPA
 * transaction begins, it runs {@code set_config('<var>', <tenant>, true)} — the
 * parameterised, transaction-local (LOCAL) form of {@code SET} — on the
 * transaction's own connection. Because the statement forces Hibernate's
 * (delayed) connection acquisition, it is the first statement to execute on that
 * connection, so RLS policies filter every subsequent query. The {@code true}
 * (is_local) argument makes the setting reset automatically at commit/rollback,
 * so a pooled connection never carries a tenant into the next borrower.
 *
 * <p>Skipped (leaving {@code app.tenant_id} unset → {@code current_setting(...,
 * true)} = NULL → zero rows, i.e. fail-closed) when:
 * <ul>
 *   <li>the thread is in {@link SystemContextHolder system context} — cross-tenant
 *       jobs run on the BYPASSRLS route where the variable is irrelevant; or</li>
 *   <li>no {@link TenantContextHolder tenant} is bound — a safe default rather
 *       than exposing another tenant's rows.</li>
 * </ul>
 *
 * <p>Only wired when {@code originex.rls.enabled=true}; see {@code dev/RLS_DESIGN.md}.
 */
public class RlsTenantTransactionManager extends JpaTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(RlsTenantTransactionManager.class);

    private final String sessionVariable;

    public RlsTenantTransactionManager(EntityManagerFactory emf, String sessionVariable) {
        super(emf);
        this.sessionVariable = sessionVariable;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        if (SystemContextHolder.isSystemContext()) {
            return; // cross-tenant system work runs on the BYPASSRLS route
        }
        TenantContext ctx = TenantContextHolder.get();
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            // Fail-closed: leave the variable unset so RLS returns no rows.
            return;
        }

        EntityManagerFactory emf = getEntityManagerFactory();
        if (emf == null) {
            return;
        }
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(emf);
        if (holder == null) {
            return;
        }
        EntityManager em = holder.getEntityManager();
        em.createNativeQuery("select set_config(:name, :value, true)")
                .setParameter("name", sessionVariable)
                .setParameter("value", ctx.tenantId())
                .getSingleResult();

        if (log.isTraceEnabled()) {
            log.trace("RLS: set {}={} for transaction", sessionVariable, ctx.tenantId());
        }
    }
}
