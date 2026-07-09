# CI/CD & GitOps Strategy

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. GitOps Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    GITOPS REPOSITORY STRUCTURE                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SOURCE REPOSITORIES (One per service):                                      │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │  originex-lms-service/                                          │         │
│  │  ├── src/main/java/...                                          │         │
│  │  ├── src/test/...                                               │         │
│  │  ├── build.gradle.kts                                           │         │
│  │  ├── Dockerfile (or JIB config)                                 │         │
│  │  ├── .github/workflows/ci.yml                                   │         │
│  │  └── README.md                                                  │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  INFRASTRUCTURE REPOSITORY (Single source of truth for infra):               │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │  originex-infrastructure/                                       │         │
│  │  ├── terraform/                                                 │         │
│  │  │   ├── modules/                                               │         │
│  │  │   └── environments/                                          │         │
│  │  ├── kubernetes/                                                │         │
│  │  │   ├── base/              (Kustomize base manifests)          │         │
│  │  │   ├── overlays/                                              │         │
│  │  │   │   ├── dev/                                               │         │
│  │  │   │   ├── staging/                                           │         │
│  │  │   │   └── production/                                        │         │
│  │  │   └── charts/           (Helm charts)                        │         │
│  │  └── .github/workflows/terraform.yml                            │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  DEPLOYMENT REPOSITORY (GitOps — ArgoCD watches this):                       │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │  originex-deployments/                                          │         │
│  │  ├── apps/                                                      │         │
│  │  │   ├── dev/                                                   │         │
│  │  │   │   ├── lms-service.yaml       (ArgoCD Application)       │         │
│  │  │   │   ├── los-service.yaml                                   │         │
│  │  │   │   └── ...                                                │         │
│  │  │   ├── staging/                                               │         │
│  │  │   └── production/                                            │         │
│  │  ├── values/                                                    │         │
│  │  │   ├── dev/                                                   │         │
│  │  │   │   ├── lms-service-values.yaml  (image tag, replicas)     │         │
│  │  │   │   └── ...                                                │         │
│  │  │   ├── staging/                                               │         │
│  │  │   └── production/                                            │         │
│  │  └── app-of-apps.yaml      (ArgoCD App of Apps pattern)        │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  SHARED REPOSITORIES:                                                        │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │  originex-proto/           (Protobuf definitions)               │         │
│  │  originex-common-lib/      (Shared Java libraries)              │         │
│  │  originex-spring-starter/  (Custom Spring Boot starter)         │         │
│  │  originex-helm-charts/     (Shared Helm chart templates)        │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. CI Pipeline (GitHub Actions)

### 2.1 Service CI Workflow

```yaml
# .github/workflows/ci.yml (applied to every service repo)
name: Service CI Pipeline
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

env:
  REGISTRY: <account-id>.dkr.ecr.ap-south-1.amazonaws.com
  IMAGE_NAME: originex/${{ github.event.repository.name }}

jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Validate code formatting
        run: ./gradlew spotlessCheck
      - name: Compile
        run: ./gradlew compileJava
      - name: Architecture tests
        run: ./gradlew archTest
      - name: Unit tests
        run: ./gradlew test
      - name: SonarQube analysis
        run: ./gradlew sonar

  security:
    runs-on: ubuntu-latest
    needs: validate
    steps:
      - name: Secret scanning
        uses: gitleaks/gitleaks-action@v2
      - name: Dependency CVE scan
        run: ./gradlew dependencyCheckAnalyze
      - name: SAST (Semgrep)
        uses: returntocorp/semgrep-action@v1

  integration-test:
    runs-on: ubuntu-latest
    needs: validate
    services:
      postgres:
        image: postgres:16
      redis:
        image: redis:7
    steps:
      - name: Integration tests (Testcontainers)
        run: ./gradlew integrationTest
      - name: Contract tests (Pact)
        run: ./gradlew pactVerify

  build-and-push:
    runs-on: ubuntu-latest
    needs: [security, integration-test]
    if: github.ref == 'refs/heads/main'
    steps:
      - name: Build container image (JIB)
        run: ./gradlew jib --image=${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:${{ github.sha }}
      - name: Scan image (Trivy)
        uses: aquasecurity/trivy-action@master
        with:
          image-ref: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:${{ github.sha }}
          severity: 'CRITICAL,HIGH'
          exit-code: '1'
      - name: Sign image (Cosign)
        run: cosign sign ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:${{ github.sha }}
      - name: Generate SBOM
        run: syft ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:${{ github.sha }} -o spdx-json

  deploy-dev:
    runs-on: ubuntu-latest
    needs: build-and-push
    steps:
      - name: Update GitOps repo (dev image tag)
        uses: actions/github-script@v7
        with:
          script: |
            // Update values/dev/service-values.yaml with new image tag
            // Commit to originex-deployments repo
            // ArgoCD auto-syncs dev environment
```

### 2.2 Promotion Pipeline

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    PROMOTION FLOW                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PR Merged → Build → Push Image → Update Dev Values → ArgoCD Sync Dev      │
│                                                                              │
│  [Automatic] Dev deployed ✓                                                  │
│       │                                                                      │
│       ▼                                                                      │
│  Smoke tests pass in Dev ✓                                                   │
│       │                                                                      │
│       ▼                                                                      │
│  [Automatic] Update Staging values → ArgoCD Sync Staging                     │
│       │                                                                      │
│       ▼                                                                      │
│  Full E2E + Performance tests in Staging ✓                                   │
│       │                                                                      │
│       ▼                                                                      │
│  [MANUAL APPROVAL] Production promotion request                              │
│       │                                                                      │
│       ▼                                                                      │
│  Update Production values (canary: 5%) → ArgoCD Sync Prod (canary)           │
│       │                                                                      │
│       ▼                                                                      │
│  [Flagger] Monitor SLOs (5 min) → Pass? → Increment (10%, 25%, 50%, 100%)  │
│       │                                          │                           │
│       ▼ (SLO breach)                             ▼                           │
│  Auto-rollback to previous version          Full production deployment ✓     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. ArgoCD Configuration

### 3.1 App of Apps Pattern

```yaml
# app-of-apps.yaml — Root application that manages all other applications
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: originex-platform
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/originex/originex-deployments
    path: apps/production
    targetRevision: main
  destination:
    server: https://kubernetes.default.svc
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

### 3.2 Per-Service Application

```yaml
# apps/production/lms-service.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: lms-service
  namespace: argocd
  annotations:
    notifications.argoproj.io/subscribe.on-sync-succeeded.slack: platform-deploys
    notifications.argoproj.io/subscribe.on-sync-failed.slack: platform-alerts
spec:
  project: originex-core
  source:
    repoURL: https://github.com/originex/originex-helm-charts
    chart: originex-service
    targetRevision: 1.5.0
    helm:
      valueFiles:
        - https://github.com/originex/originex-deployments/raw/main/values/production/lms-service-values.yaml
  destination:
    server: https://eks-prod.originex.internal
    namespace: originex-core
  syncPolicy:
    automated:
      prune: false  # Production: no auto-prune
      selfHeal: true
    syncOptions:
      - RespectIgnoreDifferences=true
```

---

## 4. Terraform Workflow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    TERRAFORM CI/CD                                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PR Created → terraform fmt check → terraform validate → terraform plan      │
│       │                                                                      │
│       ▼                                                                      │
│  Plan output posted as PR comment (reviewers can see changes)                │
│       │                                                                      │
│       ▼                                                                      │
│  PR Approved + Merged                                                        │
│       │                                                                      │
│       ▼                                                                      │
│  terraform apply (auto for dev, manual approval for staging/prod)            │
│       │                                                                      │
│       ▼                                                                      │
│  State stored in S3 + DynamoDB lock                                          │
│                                                                              │
│  SAFETY CONTROLS:                                                            │
│  • Sentinel policies: Prevent destroy of production databases                │
│  • Blast radius check: No more than 5 resources modified per apply           │
│  • Cost estimation: Infracost check on every PR                              │
│  • Drift detection: Weekly terraform plan with alert on drift                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Release Management

### 5.1 Versioning Strategy

| Component | Versioning | Example |
|-----------|-----------|---------|
| Services | SemVer (git tag) | v1.5.2 |
| Protobuf schemas | SemVer | v2.1.0 |
| Helm charts | SemVer | 1.5.0 |
| Terraform modules | SemVer | 3.2.1 |
| Container images | Git SHA + SemVer tag | sha-abc123 + v1.5.2 |
| API versions | Major only in URL | /v1/, /v2/ |

### 5.2 Release Cadence

| Environment | Cadence | Approval |
|-------------|---------|----------|
| Dev | On every merge to main | Automatic |
| Staging | Daily (batch of dev changes) | Automatic |
| Production | 2-3 times per week | Manual (Tech Lead + SRE) |
| Hotfix | As needed | Emergency approval (any 2 senior engineers) |

### 5.3 Rollback Procedure

```
ROLLBACK STEPS (< 2 minutes):
1. ArgoCD: Revert to previous sync revision (1-click)
   OR
2. GitOps: Revert image tag commit in deployments repo
   → ArgoCD auto-syncs to previous version
   
DATABASE ROLLBACK:
• Flyway does NOT support automatic rollback
• Forward-fix preferred (deploy fix, not rollback DB)
• If DB rollback needed: Manual migration script + approval
• Expand-Contract ensures old code works with new schema
```

---

## 6. Environment Parity

| Concern | How We Maintain Parity |
|---------|----------------------|
| Same container image | Image built once, promoted across environments |
| Same Kubernetes manifests | Helm chart with environment-specific values only |
| Same Kafka topics | Topic naming identical; different cluster endpoints |
| Same database schema | Flyway migrations applied in order everywhere |
| Different secrets | Vault namespaces per environment |
| Different scale | Values file: replica count, resource limits |
| Different data | Dev/Staging: synthetic; Prod: real |
