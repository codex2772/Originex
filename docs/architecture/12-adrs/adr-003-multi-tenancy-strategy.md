# ADR-003: Hybrid Multi-Tenancy Strategy

**Status:** ACCEPTED  
**Date:** 2026-07-08  
**Deciders:** Architecture Board, Platform Team  
**Technical Story:** ORIG-ARCH-003  

---

## Context & Problem Statement

Originex is a B2B SaaS platform serving multiple lending partners (banks, NBFCs, fintechs). Each partner (tenant) requires:
- Data isolation (regulatory and contractual obligation)
- Independent configuration (products, rules, branding)
- Fair resource allocation (no noisy-neighbor impact)
- Some tenants are 100x larger than others in transaction volume

The multi-tenancy model must balance isolation guarantees against operational cost and complexity.

## Decision Drivers

* Strict data isolation (regulatory: one tenant cannot access another's data)
* 80/20 distribution: 20% of tenants generate 80% of volume
* Cost efficiency (small tenants shouldn't need dedicated infrastructure)
* Operational simplicity (fewer database instances to manage)
* Performance isolation (large tenant shouldn't impact small tenant latency)
* Compliance: some tenants require dedicated infrastructure per contract
* Scale: 50-200 tenants expected in first 3 years

## Considered Options

1. **Silo Model** — Dedicated database per tenant (complete isolation)
2. **Pool Model** — Shared database, row-level isolation (cost efficient)
3. **Hybrid Model** — Pool for small/medium, Silo for large/enterprise tenants
4. **Schema-per-Tenant** — Same database instance, separate schemas

## Decision Outcome

**Chosen option:** "Hybrid Model" because it provides the optimal balance between isolation/compliance and cost/operations. Small tenants (80%) share infrastructure with Row-Level Security, while large enterprise tenants (20%) get dedicated databases with full isolation.

### Consequences

**Good:**
* Cost-efficient for small tenants (shared infrastructure)
* Full isolation for large/regulated tenants (dedicated DB)
* Flexible: tenant can be migrated from pool to silo as it grows
* RLS provides strong isolation even in pool model
* Independent scaling for high-volume tenants

**Bad:**
* Two data access patterns to maintain (pool + silo routing)
* Migration from pool to silo requires downtime planning
* More complex connection management (routing layer)
* Testing must cover both modes
* Operational runbooks needed for both models

## Implementation Details

### Tenant Classification

| Tier | Criteria | Model | Database |
|------|----------|-------|----------|
| Standard | < 10K loans, < 1M txns/day | Pool (shared DB + RLS) | Shared RDS instance |
| Premium | 10K-100K loans, 1-10M txns/day | Pool (dedicated schema) | Shared RDS, own schema |
| Enterprise | > 100K loans, > 10M txns/day | Silo (dedicated DB) | Own RDS instance |
| Regulated | Any (contractual requirement) | Silo (dedicated DB) | Own RDS instance |

### Routing Logic

```java
@Component
public class TenantAwareDataSource extends AbstractRoutingDataSource {
    
    @Override
    protected Object determineCurrentLookupKey() {
        TenantContext tenant = TenantContextHolder.get();
        
        if (tenant.getIsolationModel() == IsolationModel.SILO) {
            return "tenant-" + tenant.getId(); // Dedicated datasource
        }
        
        // Pool model: use shared datasource, RLS handles isolation
        return "shared-pool";
    }
}
```

### Row-Level Security (Pool Tenants)

```sql
-- Applied on connection setup for pool tenants
SET app.tenant_id = :tenantId;

-- RLS policy ensures isolation
CREATE POLICY tenant_isolation ON loans
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
```

### Resource Limits (Noisy Neighbor Prevention)

| Resource | Pool Tenant Limit | Mechanism |
|----------|------------------|-----------|
| API Rate | Per-tenant token bucket (Redis) | API Gateway |
| DB Connections | Max 5 per pool tenant | HikariCP + tenant tracking |
| Kafka Throughput | Tenant-specific quotas | Kafka client quotas |
| Storage | Per-tenant S3 prefix limits | IAM policy |
| Compute | Tenant-aware resource quotas | Custom K8s scheduler |

## Pros & Cons of Other Options

### Silo Model (All Tenants Dedicated)
* Good: Perfect isolation
* Good: Independent scaling and maintenance
* Bad: 50-200 database instances is operationally expensive
* Bad: Small tenants don't justify dedicated infrastructure ($500+/month each)
* Bad: Schema migrations must be applied to all instances
* Bad: Monitoring 200 DB instances is complex

### Pool Model (All Tenants Shared)
* Good: Simplest to operate (single DB per service)
* Good: Most cost-efficient
* Bad: Noisy-neighbor risk (one large tenant's query affects all)
* Bad: Cannot meet contractual isolation requirements for enterprises
* Bad: RLS bug could leak data across tenants
* Bad: Cannot independently scale for specific tenants

### Schema-per-Tenant
* Good: Logical isolation within same instance
* Good: Each tenant has own tables (no RLS needed)
* Bad: PostgreSQL connection pooling doesn't work well across schemas
* Bad: 200 schemas × 50 tables = 10,000 tables (pg_catalog bloat)
* Bad: Schema migrations on 200 schemas is slow
* Bad: Cannot easily query across tenants for platform analytics

## Links

* [Database Strategy](../05-database-strategy/data-architecture.md)
* [Security Architecture](../08-security-architecture/security-design.md)
