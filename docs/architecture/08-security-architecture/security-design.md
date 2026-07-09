# Security Architecture

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. Security Principles

| Principle | Implementation |
|-----------|---------------|
| Zero Trust | Never trust, always verify — mTLS between all services |
| Defense in Depth | Multiple security layers (WAF → Gateway → Service → Data) |
| Least Privilege | Minimal permissions for every identity (human or machine) |
| Encryption Everywhere | TLS 1.3 in transit; AES-256-GCM at rest |
| Immutable Audit | Every access and mutation logged immutably |
| Secrets Rotation | Automated rotation; no long-lived credentials |
| Secure by Default | Security controls enabled by default, not opt-in |

---

## 2. Identity & Authentication Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    AUTHENTICATION ARCHITECTURE                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  EXTERNAL (Partner APIs):                                                    │
│  ═══════════════════════                                                     │
│  • OAuth 2.0 Client Credentials Grant (machine-to-machine)                   │
│  • OpenID Connect (OIDC) for user-facing flows                               │
│  • JWT access tokens (RS256 signed, 15-min expiry)                           │
│  • Refresh tokens (encrypted, 24-hour expiry, single-use)                    │
│  • API Keys for legacy integrations (deprecated path)                        │
│                                                                              │
│  INTERNAL (Service-to-Service):                                              │
│  ═════════════════════════════                                                │
│  • mTLS via Istio Service Mesh (automatic certificate rotation)              │
│  • SPIFFE identity (spiffe://originex.io/ns/{namespace}/sa/{service})        │
│  • No shared secrets between services                                        │
│  • Service identity validated at mesh level before request reaches app       │
│                                                                              │
│  INFRASTRUCTURE:                                                             │
│  ═══════════════                                                             │
│  • AWS IAM Roles for Service Accounts (IRSA) for cloud resources             │
│  • No static AWS credentials in pods                                         │
│  • Temporary credentials via STS AssumeRole                                  │
│                                                                              │
│  ┌──────────┐  OAuth2   ┌──────────┐  mTLS     ┌──────────┐               │
│  │ Partner  │ ─────────►│   API    │ ─────────►│ Service  │               │
│  │ System   │  JWT      │ Gateway  │  SPIFFE   │  Pod     │               │
│  └──────────┘           └──────────┘           └──────────┘               │
│                              │                       │                      │
│                              │ JWT Validation         │ IRSA                │
│                              ▼                       ▼                      │
│                         ┌──────────┐          ┌──────────┐                 │
│                         │ Keycloak │          │  AWS IAM │                 │
│                         │  (IdP)   │          │          │                 │
│                         └──────────┘          └──────────┘                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Authorization Model

### 3.1 RBAC + ABAC Hybrid

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    AUTHORIZATION MODEL                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  LAYER 1 — RBAC (Role-Based Access Control):                                 │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━                                 │
│  • Roles scoped per tenant                                                   │
│  • Predefined roles: TENANT_ADMIN, LOAN_ORIGINATOR, LOAN_MANAGER,           │
│    COLLECTIONS_AGENT, FINANCE_MANAGER, AUDITOR, API_CLIENT                   │
│  • Role → Permission mapping in IAM service                                  │
│                                                                              │
│  LAYER 2 — ABAC (Attribute-Based Access Control):                            │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━                            │
│  • Fine-grained policies based on attributes                                 │
│  • Example: "Collections agent can only see cases assigned to them"          │
│  • Example: "Branch manager can only see loans in their branch"              │
│  • Evaluated at application layer using OPA (Open Policy Agent)              │
│                                                                              │
│  LAYER 3 — Data-Level (Row-Level Security):                                  │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━                                 │
│  • PostgreSQL RLS ensures tenant isolation at DB level                        │
│  • Even with application bug, one tenant cannot see another's data           │
│  • Defense in depth — last line of protection                                │
│                                                                              │
│  PERMISSION FORMAT: {domain}:{resource}:{action}                             │
│  Examples:                                                                   │
│    los:applications:create                                                   │
│    los:applications:read                                                     │
│    los:applications:approve                                                  │
│    lms:loans:read                                                            │
│    lms:loans:restructure                                                     │
│    ledger:accounts:read                                                      │
│    payments:orders:create                                                    │
│    admin:tenants:manage                                                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 OPA Policy Example

```rego
# policy.rego — Loan access control
package originex.authz

default allow = false

# Tenant isolation — always enforced
allow {
    input.resource.tenant_id == input.subject.tenant_id
}

# Role-based check
allow {
    input.action == "read"
    "LOAN_VIEWER" in input.subject.roles
}

# Collections agents can only see assigned cases
allow {
    input.resource.type == "collection_case"
    input.action == "read"
    input.resource.assigned_agent_id == input.subject.user_id
}

# Branch-level restriction
allow {
    input.resource.type == "loan"
    input.action == "read"
    input.resource.branch_id == input.subject.branch_id
}
```

---

## 4. Encryption Architecture

### 4.1 Encryption in Transit

| Connection | Protocol | Certificate |
|-----------|----------|-------------|
| Client → API Gateway | TLS 1.3 | ACM (AWS Certificate Manager) |
| API Gateway → Service | mTLS (Istio) | Istio CA (auto-rotated every 24h) |
| Service → Service | mTLS (Istio) | Istio CA (SPIFFE) |
| Service → Database | TLS 1.3 | RDS CA certificate |
| Service → Redis | TLS 1.3 | ElastiCache certificate |
| Service → Kafka | mTLS | Custom CA (Strimzi) |
| Cross-region replication | TLS 1.3 + VPN | AWS PrivateLink |

### 4.2 Encryption at Rest

| Data | Encryption | Key Management |
|------|-----------|----------------|
| PostgreSQL (RDS) | AES-256 | AWS KMS (CMK per tenant tier) |
| Redis (ElastiCache) | AES-256 | AWS KMS |
| OpenSearch | AES-256 | AWS KMS |
| S3 (Documents) | AES-256-GCM | AWS KMS (SSE-KMS) |
| Kafka (EBS volumes) | AES-256 | AWS KMS |
| EKS etcd | AES-256 | AWS KMS (envelope encryption) |

### 4.3 Application-Level Encryption (Field-Level)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    FIELD-LEVEL ENCRYPTION (PII Protection)                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SENSITIVE FIELDS (Encrypted at application layer):                          │
│  • Aadhaar number → Tokenized (irreversible hash for dedup)                 │
│  • PAN number → Encrypted (AES-256-GCM, rotatable key)                      │
│  • Bank account number → Encrypted                                           │
│  • Date of birth → Encrypted                                                 │
│  • Address (full) → Encrypted                                                │
│  • Phone number → Partially masked in logs, encrypted in DB                  │
│  • Email → Partially masked in logs, encrypted in DB                         │
│                                                                              │
│  IMPLEMENTATION:                                                             │
│  • AWS KMS for key management                                                │
│  • Data Encryption Key (DEK) per tenant (envelope encryption)                │
│  • DEK encrypted by KMS CMK (master key)                                     │
│  • DEK cached in memory (5 min TTL) to avoid KMS latency                    │
│  • Key rotation: DEK monthly, CMK annually                                   │
│  • Old DEKs retained for decryption of historical data                       │
│                                                                              │
│  TOKENIZATION (Aadhaar — DPDPA requirement):                                │
│  • Hash: SHA-256(aadhaar + tenant-specific-salt)                             │
│  • Original value NEVER stored                                               │
│  • Token used for deduplication only                                         │
│  • Cannot be reversed to original Aadhaar                                    │
│                                                                              │
│  DATA MASKING (Logs & Non-Production):                                       │
│  • PAN: XXXXX1234 (last 4 visible)                                          │
│  • Phone: XXXXXXX890 (last 3 visible)                                       │
│  • Email: r***@example.com                                                   │
│  • Account: XXXXXXXX5678 (last 4 visible)                                   │
│  • Masking applied at logging interceptor level                              │
│  • Non-production environments use synthetic data only                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Secrets Management

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SECRETS MANAGEMENT (HashiCorp Vault)                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ARCHITECTURE:                                                               │
│  • Vault cluster: 3 nodes (HA with Raft consensus)                           │
│  • Auto-unseal via AWS KMS                                                   │
│  • Kubernetes auth method (pod identity)                                     │
│  • Dynamic secrets for databases (no static passwords)                       │
│                                                                              │
│  SECRET TYPES:                                                               │
│  ┌─────────────────────────────────────────────────────────────┐            │
│  │  Dynamic Database Credentials                                │            │
│  │  • PostgreSQL: Vault creates temporary user per pod          │            │
│  │  • TTL: 1 hour, auto-renewed                                 │            │
│  │  • Revoked on pod termination                                │            │
│  └─────────────────────────────────────────────────────────────┘            │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────┐            │
│  │  API Keys & Tokens                                           │            │
│  │  • Partner API keys (bureau, payment gateway)                │            │
│  │  • Rotated per partner schedule                              │            │
│  │  • Accessed via Vault Agent Sidecar                          │            │
│  └─────────────────────────────────────────────────────────────┘            │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────┐            │
│  │  Encryption Keys                                             │            │
│  │  • Transit secret engine for application encryption          │            │
│  │  • Key versioning for rotation without re-encryption         │            │
│  │  • Encrypt/decrypt API (key never leaves Vault)              │            │
│  └─────────────────────────────────────────────────────────────┘            │
│                                                                              │
│  SECRET ACCESS PATTERN:                                                      │
│  Pod → Vault Agent (sidecar) → Vault Server → Secret                        │
│  • Pod never sees Vault token directly                                       │
│  • Vault Agent handles authentication and renewal                            │
│  • Secrets injected as files or environment variables                        │
│                                                                              │
│  ROTATION SCHEDULE:                                                          │
│  • Database credentials: Every 1 hour (dynamic)                              │
│  • API keys: Every 90 days (or per partner requirement)                      │
│  • Encryption keys: Every 30 days (new version, old retained)                │
│  • TLS certificates: Every 24 hours (Istio automatic)                        │
│  • JWT signing keys: Every 7 days (JWKS endpoint)                            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Network Security

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    NETWORK SECURITY LAYERS                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  LAYER 1 — EDGE (DDoS + WAF):                                               │
│  • AWS Shield Advanced (DDoS protection)                                     │
│  • AWS WAF (OWASP Top 10 rules)                                             │
│  • CloudFront geo-restriction (India-only for data residency)               │
│  • IP reputation filtering                                                   │
│  • Bot detection and mitigation                                              │
│                                                                              │
│  LAYER 2 — NETWORK (VPC + Security Groups):                                 │
│  • Private subnets for all services (no public IPs)                          │
│  • Security groups: least-privilege ingress/egress                           │
│  • NACLs: subnet-level deny rules                                            │
│  • VPC Flow Logs: all traffic logged to S3                                   │
│  • No direct internet access from pods (NAT Gateway for outbound)           │
│                                                                              │
│  LAYER 3 — SERVICE MESH (Istio):                                             │
│  • mTLS enforced (STRICT mode, not PERMISSIVE)                              │
│  • AuthorizationPolicy: service-to-service allowlist                        │
│  • No pod can communicate unless explicitly allowed                          │
│  • Example: payment-service can only be called by lms-service               │
│                                                                              │
│  LAYER 4 — APPLICATION:                                                      │
│  • Input validation (all endpoints)                                          │
│  • Output encoding (XSS prevention)                                          │
│  • SQL injection prevention (parameterized queries only)                     │
│  • CSRF protection (SameSite cookies, CSRF tokens)                          │
│  • Content-Security-Policy headers                                           │
│  • CORS: allowlist of partner domains only                                   │
│                                                                              │
│  Istio AuthorizationPolicy Example:                                          │
│  ─────────────────────────────────                                           │
│  apiVersion: security.istio.io/v1                                            │
│  kind: AuthorizationPolicy                                                   │
│  metadata:                                                                   │
│    name: payment-service-access                                              │
│    namespace: originex-core                                                  │
│  spec:                                                                       │
│    selector:                                                                 │
│      matchLabels:                                                            │
│        app: payment-service                                                  │
│    rules:                                                                    │
│    - from:                                                                   │
│      - source:                                                               │
│          principals:                                                         │
│          - "cluster.local/ns/originex-core/sa/lms-service"                   │
│          - "cluster.local/ns/originex-core/sa/collection-service"            │
│      to:                                                                     │
│      - operation:                                                            │
│          methods: ["POST", "GET"]                                            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Audit & Compliance Logging

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    IMMUTABLE AUDIT TRAIL                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  WHAT IS AUDITED:                                                            │
│  • All API calls (who, what, when, from where)                               │
│  • All data mutations (before/after state)                                   │
│  • All authentication events (login, logout, failure)                        │
│  • All authorization decisions (allow/deny)                                  │
│  • All data access (read of sensitive data)                                  │
│  • All configuration changes                                                 │
│  • All deployment events                                                     │
│  • All administrative actions                                                │
│                                                                              │
│  AUDIT EVENT STRUCTURE:                                                      │
│  {                                                                           │
│    "event_id": "uuid",                                                       │
│    "event_type": "DATA_ACCESS",                                              │
│    "timestamp": "2026-07-08T10:30:00.000Z",                                  │
│    "actor": {                                                                │
│      "id": "user-uuid",                                                      │
│      "type": "API_CLIENT",                                                   │
│      "tenant_id": "tenant-uuid",                                             │
│      "ip_address": "203.0.113.45"                                            │
│    },                                                                        │
│    "action": "READ",                                                         │
│    "resource": {                                                             │
│      "type": "loan",                                                         │
│      "id": "loan-uuid",                                                      │
│      "tenant_id": "tenant-uuid"                                              │
│    },                                                                        │
│    "result": "SUCCESS",                                                      │
│    "context": {                                                              │
│      "correlation_id": "corr-uuid",                                          │
│      "request_path": "/v1/loans/loan-uuid",                                  │
│      "user_agent": "PartnerSDK/1.0"                                         │
│    }                                                                         │
│  }                                                                           │
│                                                                              │
│  STORAGE:                                                                    │
│  • Hot (< 90 days): OpenSearch (searchable, dashboards)                      │
│  • Warm (90 days - 3 years): S3 (Parquet, Athena queryable)                  │
│  • Cold (3 - 8 years): S3 Glacier (regulatory retention)                     │
│                                                                              │
│  TAMPER EVIDENCE:                                                            │
│  • Hash chain: each event includes hash of previous event                    │
│  • Daily integrity check: verify hash chain continuity                       │
│  • S3 Object Lock (WORM): prevents deletion during retention                 │
│  • CloudTrail: monitors access to audit data itself                          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Threat Model (STRIDE)

| Threat | Category | Mitigation |
|--------|----------|-----------|
| Impersonation of partner | Spoofing | OAuth2 + mTLS + IP allowlisting |
| Tampered loan data | Tampering | Event sourcing + hash chains + HMAC |
| Denied disbursement | Repudiation | Immutable audit log + signed events |
| Exposed PII | Information Disclosure | Field-level encryption + RLS + masking |
| System overload | Denial of Service | WAF + rate limiting + auto-scaling |
| Privilege escalation | Elevation of Privilege | RBAC + ABAC + OPA + principle of least privilege |
| Supply chain attack | All | SBOM, CVE scanning, signed images, admission controller |
| Insider threat | All | MFA, audit logging, separation of duties, break-glass procedures |

---

## 9. Security Testing & Vulnerability Management

| Activity | Frequency | Tool |
|----------|-----------|------|
| Static Analysis (SAST) | Every commit | SonarQube, Semgrep |
| Dependency CVE scan | Every build | OWASP Dependency Check, Snyk |
| Container image scan | Every build | Trivy, ECR scanning |
| Dynamic Analysis (DAST) | Weekly | OWASP ZAP |
| Penetration testing | Quarterly | External vendor |
| Secret scanning | Every commit | GitLeaks, TruffleHog |
| Infrastructure scanning | Daily | AWS SecurityHub, Prowler |
| Kubernetes audit | Continuous | Falco, KubeAudit |
| Certificate expiry | Continuous | cert-manager alerts |
| WAF rule validation | Monthly | Custom test suite |
