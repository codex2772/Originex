package com.originex.starter.rls;

import com.originex.common.tenant.SystemContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Routes each connection to the {@code app} (RLS-subject) or {@code system}
 * (BYPASSRLS) datasource based on {@link SystemContextHolder}. Cross-tenant
 * background jobs run inside {@code SystemContextHolder.runAsSystem(...)} and are
 * routed to the BYPASSRLS role; everything else uses the RLS-subject role.
 *
 * <p><b>Fail-loud routing</b> (see {@code dev/RLS_DESIGN.md} §7.3): configured
 * with {@code lenientFallback=false} so any unexpected lookup key throws rather
 * than silently falling back. The key here is derived from a boolean, so it is
 * never null; the {@code app} default only guards the theoretical null case and
 * errs toward more RLS filtering, never less.
 */
public class TenantRoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantRoutingDataSource.class);

    public enum Route { APP, SYSTEM }

    @Override
    protected Object determineCurrentLookupKey() {
        if (SystemContextHolder.isSystemContext()) {
            if (log.isTraceEnabled()) {
                log.trace("RLS routing → SYSTEM (BYPASSRLS)");
            }
            return Route.SYSTEM;
        }
        return Route.APP;
    }
}
