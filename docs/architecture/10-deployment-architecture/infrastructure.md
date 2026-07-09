# Deployment Architecture & Infrastructure

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. Infrastructure Overview

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                         AWS INFRASTRUCTURE — MULTI-REGION                                      │
├─────────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐        │
│  │                      GLOBAL SERVICES                                              │        │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐         │        │
│  │  │ Route 53 │  │CloudFront│  │  AWS WAF │  │  IAM     │  │  ECR     │         │        │
│  │  │  (DNS)   │  │  (CDN)   │  │          │  │          │  │(Registry)│         │        │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘         │        │
│  └─────────────────────────────────────────────────────────────────────────────────┘        │
│                                                                                              │
│  ┌─────────────────────────────────────┐  ┌─────────────────────────────────────┐          │
│  │     PRIMARY REGION (ap-south-1)      │  │      DR REGION (ap-south-2)         │          │
│  │           Mumbai                     │  │         Hyderabad                    │          │
│  │                                      │  │                                      │          │
│  │  ┌─── VPC: 10.0.0.0/16 ──────────┐ │  │  ┌─── VPC: 10.1.0.0/16 ──────────┐ │          │
│  │  │                                 │ │  │  │                                 │ │          │
│  │  │  Public Subnets (3 AZs)        │ │  │  │  Public Subnets (3 AZs)        │ │          │
│  │  │  ├── ALB                        │ │  │  │  ├── ALB                        │ │          │
│  │  │  ├── NAT Gateways              │ │  │  │  ├── NAT Gateways              │ │          │
│  │  │  └── Istio Ingress             │ │  │  │  └── Istio Ingress             │ │          │
│  │  │                                 │ │  │  │                                 │ │          │
│  │  │  Private Subnets (3 AZs)       │ │  │  │  Private Subnets (3 AZs)       │ │          │
│  │  │  ├── EKS Worker Nodes          │ │  │  │  ├── EKS Worker Nodes          │ │          │
│  │  │  ├── Kafka (Strimzi)           │ │  │  │  ├── Kafka (Strimzi)           │ │          │
│  │  │  └── Flink Cluster             │ │  │  │  └── Flink Cluster             │ │          │
│  │  │                                 │ │  │  │                                 │ │          │
│  │  │  Data Subnets (3 AZs)          │ │  │  │  Data Subnets (3 AZs)          │ │          │
│  │  │  ├── RDS PostgreSQL (Multi-AZ) │ │  │  │  ├── RDS PostgreSQL (Replica)  │ │          │
│  │  │  ├── ElastiCache Redis         │ │  │  │  ├── ElastiCache Redis         │ │          │
│  │  │  ├── OpenSearch                │ │  │  │  ├── OpenSearch                │ │          │
│  │  │  └── S3 (VPC Endpoint)         │ │  │  │  └── S3 (VPC Endpoint)         │ │          │
│  │  │                                 │ │  │  │                                 │ │          │
│  │  └─────────────────────────────────┘ │  │  └─────────────────────────────────┘ │          │
│  │                                      │  │                                      │          │
│  │  Transit Gateway ◄══════════════════►│══│══► Transit Gateway                   │          │
│  │                                      │  │                                      │          │
│  └─────────────────────────────────────┘  └─────────────────────────────────────┘          │
│                                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐        │
│  │                    CROSS-REGION REPLICATION                                       │        │
│  │  • RDS: Async replication (primary → read replica in DR)                          │        │
│  │  • Kafka: MirrorMaker 2 (active → passive topic mirroring)                       │        │
│  │  • S3: Cross-Region Replication (CRR)                                             │        │
│  │  • Redis: Global Datastore (async replication)                                    │        │
│  │  • OpenSearch: Cross-cluster replication                                           │        │
│  └─────────────────────────────────────────────────────────────────────────────────┘        │
│                                                                                              │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. EKS Cluster Architecture

### 2.1 Cluster Configuration

```yaml
# EKS Cluster Specification
cluster:
  name: originex-prod-mumbai
  version: "1.30"
  region: ap-south-1
  endpoint_access: private  # No public API endpoint
  
  networking:
    vpc_cni: true
    pod_cidr: 10.0.64.0/18  # 16K pods
    service_cidr: 172.20.0.0/16
    
  encryption:
    secrets: true  # Envelope encryption with KMS
    
  logging:
    api_server: true
    audit: true
    authenticator: true
    controller_manager: true
    scheduler: true
```

### 2.2 Node Groups

| Node Group | Instance Type | Min | Max | Purpose | Labels |
|-----------|--------------|-----|-----|---------|--------|
| core-services | m6i.2xlarge (8 vCPU, 32 GB) | 6 | 24 | Application services | tier=core |
| data-intensive | r6i.2xlarge (8 vCPU, 64 GB) | 3 | 12 | Kafka, Flink, OpenSearch | tier=data |
| compute-burst | c6i.2xlarge (8 vCPU, 16 GB) | 0 | 20 | EOD processing, batch jobs | tier=compute |
| system | m6i.xlarge (4 vCPU, 16 GB) | 3 | 6 | Istio, monitoring, operators | tier=system |
| gpu (future) | g5.xlarge | 0 | 4 | ML model inference (fraud) | tier=ml |

### 2.3 Kubernetes Namespace Strategy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    NAMESPACE LAYOUT                                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  originex-core          → Core business services (LOS, LMS, Ledger, etc.)   │
│  originex-data          → Kafka, Flink, data infrastructure                  │
│  originex-platform      → IAM, Config, Tenant, Audit                        │
│  originex-integration   → Partner service, notification service              │
│  originex-observability → Prometheus, Grafana, Jaeger, Loki                  │
│  istio-system           → Istio control plane                                │
│  cert-manager           → Certificate management                             │
│  vault                  → HashiCorp Vault                                    │
│  argocd                 → ArgoCD GitOps controller                           │
│                                                                              │
│  RESOURCE QUOTAS (per namespace):                                            │
│  ┌─────────────────────────────────────────────────────────────┐            │
│  │  originex-core:                                              │            │
│  │    requests.cpu: "64"                                        │            │
│  │    requests.memory: "128Gi"                                  │            │
│  │    limits.cpu: "128"                                         │            │
│  │    limits.memory: "256Gi"                                    │            │
│  │    pods: "200"                                               │            │
│  │    services: "50"                                            │            │
│  └─────────────────────────────────────────────────────────────┘            │
│                                                                              │
│  NETWORK POLICIES:                                                           │
│  • Default deny all ingress/egress per namespace                             │
│  • Explicit allow rules for required communication                           │
│  • Cross-namespace communication via Istio AuthorizationPolicy               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. CI/CD Pipeline Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CI/CD PIPELINE (GitOps)                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  DEVELOPER WORKFLOW:                                                         │
│                                                                              │
│  ┌────────┐  Push   ┌────────┐  CI    ┌────────┐  Push   ┌────────┐       │
│  │  Dev   │────────►│ GitHub │───────►│GitHub  │────────►│  ECR   │       │
│  │  (PR)  │         │  Repo  │        │Actions │  Image  │(Registry)│       │
│  └────────┘         └────────┘        └───┬────┘         └────────┘       │
│                                           │                                 │
│                                           │ Update image tag                │
│                                           ▼                                 │
│                                    ┌────────────┐                           │
│                                    │ GitOps Repo│  (Helm values)            │
│                                    │ (manifests)│                           │
│                                    └─────┬──────┘                           │
│                                          │                                  │
│                                          │ Sync                             │
│                                          ▼                                  │
│                                    ┌────────────┐                           │
│                                    │   ArgoCD   │                           │
│                                    │ (GitOps)   │                           │
│                                    └─────┬──────┘                           │
│                                          │                                  │
│                          ┌───────────────┼───────────────┐                  │
│                          ▼               ▼               ▼                  │
│                    ┌──────────┐   ┌──────────┐   ┌──────────┐             │
│                    │   Dev    │   │  Staging  │   │   Prod   │             │
│                    │ Cluster  │   │  Cluster  │   │  Cluster │             │
│                    └──────────┘   └──────────┘   └──────────┘             │
│                                                                              │
│  PROMOTION STRATEGY:                                                         │
│  Dev → Staging: Automatic (on merge to main)                                 │
│  Staging → Prod: Manual approval + canary deployment                         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.1 CI Pipeline Stages

```yaml
# .github/workflows/service-ci.yml (conceptual)
stages:
  1_validate:
    - lint (Spotless check)
    - compile
    - unit-tests
    - architecture-tests (ArchUnit)
    - static-analysis (SonarQube)
    
  2_security:
    - secret-scan (GitLeaks)
    - dependency-cve-scan (OWASP)
    - container-scan (Trivy)
    - sast (Semgrep)
    
  3_test:
    - integration-tests (Testcontainers)
    - contract-tests (Pact)
    - api-compatibility-check (OpenAPI diff)
    
  4_build:
    - build-image (JIB, distroless base)
    - sign-image (Cosign)
    - push-to-ecr
    - generate-sbom (Syft)
    
  5_deploy:
    - update-gitops-repo (image tag)
    - ArgoCD sync (automatic for dev/staging)
    
  6_post_deploy:
    - smoke-tests
    - integration-verification
    - performance-baseline
```

### 3.2 Deployment Strategies

| Environment | Strategy | Rollback | Validation |
|-------------|----------|----------|-----------|
| Dev | Rolling update | Automatic (failed health check) | Unit + integration tests |
| Staging | Blue-Green | Manual or automatic | Full E2E + performance baseline |
| Production | Canary (5% → 25% → 50% → 100%) | Automatic on SLO breach | Real traffic + metrics |

**Canary Deployment Configuration (Istio):**

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: lms-service-canary
spec:
  hosts:
  - lms-service
  http:
  - route:
    - destination:
        host: lms-service
        subset: stable
      weight: 95
    - destination:
        host: lms-service
        subset: canary
      weight: 5
---
# Automated promotion via Flagger
apiVersion: flagger.app/v1beta1
kind: Canary
metadata:
  name: lms-service
spec:
  targetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: lms-service
  progressDeadlineSeconds: 600
  analysis:
    interval: 60s
    threshold: 5        # Max failed checks before rollback
    maxWeight: 50       # Max canary traffic %
    stepWeight: 10      # Increment per interval
    metrics:
    - name: request-success-rate
      thresholdRange:
        min: 99.5
    - name: request-duration
      thresholdRange:
        max: 500        # p99 < 500ms
```

---

## 4. Terraform Module Structure

```
terraform/
├── modules/
│   ├── vpc/
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   ├── outputs.tf
│   │   └── versions.tf
│   ├── eks/
│   │   ├── main.tf
│   │   ├── node-groups.tf
│   │   ├── addons.tf
│   │   ├── irsa.tf
│   │   └── variables.tf
│   ├── rds/
│   │   ├── main.tf          # Multi-AZ, encryption, parameter groups
│   │   ├── replicas.tf      # Cross-region read replicas
│   │   └── variables.tf
│   ├── elasticache/
│   │   ├── main.tf          # Redis cluster mode
│   │   └── variables.tf
│   ├── kafka/                # Strimzi operator + custom resources
│   │   ├── main.tf
│   │   └── topics.tf
│   ├── opensearch/
│   │   ├── main.tf
│   │   └── variables.tf
│   ├── s3/
│   │   ├── main.tf
│   │   └── replication.tf
│   ├── kms/
│   │   ├── main.tf          # CMKs per service
│   │   └── policies.tf
│   ├── networking/
│   │   ├── security-groups.tf
│   │   ├── nacls.tf
│   │   └── endpoints.tf     # VPC endpoints (S3, KMS, STS)
│   └── observability/
│       ├── prometheus.tf
│       ├── grafana.tf
│       └── logging.tf
├── environments/
│   ├── dev/
│   │   ├── main.tf
│   │   ├── terraform.tfvars
│   │   └── backend.tf
│   ├── staging/
│   │   ├── main.tf
│   │   ├── terraform.tfvars
│   │   └── backend.tf
│   └── production/
│       ├── mumbai/
│       │   ├── main.tf
│       │   ├── terraform.tfvars
│       │   └── backend.tf
│       └── hyderabad/
│           ├── main.tf
│           ├── terraform.tfvars
│           └── backend.tf
└── global/
    ├── route53.tf
    ├── cloudfront.tf
    ├── waf.tf
    ├── iam.tf
    └── ecr.tf
```

---

## 5. Disaster Recovery Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    DISASTER RECOVERY STRATEGY                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  MODEL: Warm Standby (Active-Passive with fast promotion)                    │
│                                                                              │
│  ┌────────────────────────┐        ┌────────────────────────┐              │
│  │  Mumbai (ACTIVE)        │        │  Hyderabad (STANDBY)   │              │
│  │  • All traffic          │        │  • No production traffic│              │
│  │  • Full compute         │        │  • Reduced compute (50%)│              │
│  │  • Primary databases    │        │  • Read replicas        │              │
│  │  • Full Kafka cluster   │        │  • Mirrored Kafka       │              │
│  └────────────┬───────────┘        └────────────┬───────────┘              │
│               │                                   │                          │
│               │     REPLICATION                    │                          │
│               ├───────────────────────────────────┤                          │
│               │  • RDS: Async (< 1s lag)          │                          │
│               │  • Kafka: MirrorMaker 2           │                          │
│               │  • S3: CRR (minutes)              │                          │
│               │  • Redis: Global Datastore        │                          │
│               └───────────────────────────────────┘                          │
│                                                                              │
│  FAILOVER PROCEDURE (Automated):                                             │
│  ═══════════════════════════════                                              │
│  1. Health check fails for Mumbai (3 consecutive, 10s interval)              │
│  2. Route 53 health check triggers DNS failover (30s TTL)                    │
│  3. RDS: Promote read replica to primary (< 2 minutes)                       │
│  4. EKS Hyderabad: Scale up to full capacity (< 3 minutes)                   │
│  5. Kafka: Consumers switch to local mirrored topics                         │
│  6. Alert: PagerDuty P1 incident created                                     │
│  7. Validation: Smoke tests run against Hyderabad                            │
│                                                                              │
│  TOTAL RTO: < 5 MINUTES                                                     │
│  RPO: 0 for Ledger (sync replication), < 1s for others                       │
│                                                                              │
│  FAILBACK PROCEDURE (Manual):                                                │
│  ═══════════════════════════                                                  │
│  1. Mumbai infrastructure restored and validated                             │
│  2. Data resync from Hyderabad back to Mumbai                                │
│  3. Canary traffic shifted to Mumbai (5%)                                    │
│  4. Full validation with production traffic                                  │
│  5. Gradual traffic shift (25% → 50% → 100%)                               │
│  6. Hyderabad returned to standby mode                                       │
│                                                                              │
│  DR TESTING:                                                                 │
│  • Quarterly: Full failover drill (scheduled maintenance window)             │
│  • Monthly: Partial failover (single service)                                │
│  • Weekly: Backup restoration verification                                   │
│  • Continuous: Chaos engineering (Litmus)                                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Auto-Scaling Configuration

| Service | Metric | Target | Min Pods | Max Pods | Scale-Up | Scale-Down |
|---------|--------|--------|----------|----------|----------|-----------|
| los-service | CPU + Queue depth | 70% / 100 msgs | 3 | 20 | 30s | 300s |
| lms-service | CPU + Kafka lag | 70% / 1000 msgs | 4 | 30 | 30s | 300s |
| ledger-service | TPS (custom metric) | 10K TPS | 4 | 40 | 15s | 300s |
| payment-service | Queue depth | 50 pending | 3 | 20 | 15s | 300s |
| bre-service | Latency p99 | < 50ms | 3 | 15 | 15s | 300s |
| notification-service | Queue depth | 500 pending | 2 | 20 | 30s | 120s |
| reporting-service | CPU | 80% | 2 | 10 | 60s | 300s |

**KEDA (Kubernetes Event-Driven Autoscaling) for Kafka consumers:**

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: lms-consumer-scaler
spec:
  scaleTargetRef:
    name: lms-service
  minReplicaCount: 4
  maxReplicaCount: 30
  triggers:
  - type: kafka
    metadata:
      bootstrapServers: kafka-cluster:9092
      consumerGroup: lms-service-group
      topic: originex.lms.loans.commands
      lagThreshold: "1000"
      offsetResetPolicy: latest
```

---

## 7. Environment Strategy

| Environment | Purpose | Scale | Data | Refresh |
|-------------|---------|-------|------|---------|
| Local (Docker Compose) | Developer workstation | 1 replica | Synthetic seed | On-demand |
| Dev (EKS) | Integration testing | 1 replica | Synthetic | Nightly reset |
| Staging (EKS) | Pre-production validation | 2 replicas | Anonymized prod snapshot | Weekly |
| Production - Mumbai | Primary traffic | Auto-scaled | Real | N/A |
| Production - Hyderabad | DR standby | 50% of primary | Replicated | Real-time |
| Performance (EKS) | Load testing | Production-equivalent | Generated | Per test run |

---

## 8. Zero-Downtime Deployment

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ZERO-DOWNTIME DEPLOYMENT CHECKLIST                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  DATABASE MIGRATIONS:                                                        │
│  • Expand-Contract pattern (never drop columns in same release)              │
│  • Step 1: Add new column (nullable) — deploy with backward compat code     │
│  • Step 2: Migrate data — backfill new column                                │
│  • Step 3: Update code to use new column — deploy                            │
│  • Step 4: Drop old column (next release cycle)                              │
│  • Flyway for versioned migrations                                           │
│  • No locking DDL in production (CREATE INDEX CONCURRENTLY)                  │
│                                                                              │
│  APPLICATION DEPLOYMENT:                                                     │
│  • Rolling update with maxSurge=25%, maxUnavailable=0                        │
│  • Readiness probe must pass before receiving traffic                        │
│  • Liveness probe: /actuator/health/liveness                                 │
│  • Readiness probe: /actuator/health/readiness (includes DB + Kafka)         │
│  • Graceful shutdown: 30s drain period                                       │
│  • PreStop hook: sleep 5 (allow LB to deregister)                            │
│                                                                              │
│  KAFKA CONSUMERS:                                                            │
│  • Cooperative rebalancing (incremental, no stop-the-world)                  │
│  • Static group membership (reduces rebalance frequency)                     │
│  • Consumer graceful shutdown: commit offsets, leave group                    │
│                                                                              │
│  API BACKWARD COMPATIBILITY:                                                 │
│  • New version serves both old and new request formats                       │
│  • Feature flags for gradual rollout                                         │
│  • API Gateway routes old/new versions simultaneously                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 9. Cost Estimation (Production — Monthly)

| Component | Instance/Size | Quantity | Estimated Monthly Cost |
|-----------|--------------|----------|----------------------|
| EKS Control Plane | Managed | 2 clusters | $146 |
| EC2 (core-services) | m6i.2xlarge | 12 | $3,456 |
| EC2 (data-intensive) | r6i.2xlarge | 6 | $2,160 |
| EC2 (system) | m6i.xlarge | 6 | $864 |
| RDS PostgreSQL | db.r6g.2xlarge Multi-AZ | 8 instances | $8,640 |
| ElastiCache Redis | cache.r6g.xlarge | 6 nodes | $1,944 |
| OpenSearch | r6g.2xlarge.search | 6 nodes | $3,888 |
| S3 | Various classes | ~50 TB | $1,150 |
| Data Transfer | Cross-AZ + Internet | Variable | $2,000 |
| KMS | CMK + API calls | 14 keys | $200 |
| CloudFront + WAF | Standard | 1 distribution | $500 |
| Route 53 | Hosted zones + queries | 5 zones | $50 |
| **Total Estimated** | | | **~$25,000/month** |

*Note: Excludes DR region (add ~40% for warm standby), reserved instance discounts (30-40% savings), and Savings Plans.*
