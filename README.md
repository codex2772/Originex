# ═══════════════════════════════════════════════════════════════════════════
# Originex — Enterprise Lending-as-a-Service Platform
# ═══════════════════════════════════════════════════════════════════════════

## Quick Start (Local Development)

### Prerequisites
- Java 21 (Temurin recommended)
- Maven 3.9+
- Docker & Docker Compose

### 1. Start infrastructure
```bash
docker compose -f dev/docker-compose.yml up -d
```

### 2. Build all modules
```bash
mvn clean install -DskipITs
```

### 3. Run template service
```bash
cd services/template-service
mvn spring-boot:run
```

### 4. Test an endpoint
```bash
curl -X POST http://localhost:8080/v1/samples \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: 550e8400-e29b-41d4-a716-446655440000" \
  -d '{"name": "Test Sample", "description": "Hello Originex", "amount": "10000.00"}'
```

---

## Project Structure

```
├── pom.xml                         Root POM (dependency management, plugins)
├── proto/                          Protobuf schemas (events, gRPC contracts)
├── libs/
│   ├── common/                     Shared value objects (Money, TenantContext, DomainEvent)
│   └── spring-boot-starter/        Platform auto-configuration (tenant, kafka, error handling)
├── services/
│   └── template-service/           Reference hexagonal architecture service
├── infra/
│   ├── terraform/modules/          IaC modules (VPC, EKS, RDS, Redis)
│   ├── helm/originex-service/      Shared Helm chart for all services
│   └── kafka/                      Kafka topic definitions (Strimzi CRDs)
├── dev/
│   └── docker-compose.yml          Local development stack
├── .github/workflows/              CI/CD pipelines
└── docs/architecture/              Phase 1 architecture documentation
```

## Architecture

See [docs/architecture/00-index.md](docs/architecture/00-index.md) for the complete architecture documentation.

## Build Commands

| Command | Description |
|---------|-------------|
| `mvn clean install` | Build all modules + unit tests |
| `mvn verify -Pintegration-test` | Run integration tests (requires Docker) |
| `mvn spotless:apply` | Format all code |
| `mvn jib:dockerBuild -pl services/template-service` | Build container image |
| `mvn dependency-check:check` | CVE scan |

## Technology Stack

- **Java 21** (Virtual Threads enabled)
- **Spring Boot 3.4.x**
- **Apache Kafka** (Protobuf serialization)
- **PostgreSQL 16** (with Row-Level Security)
- **Redis 7** (caching, rate limiting)
- **Protobuf** (event/gRPC contracts)
- **Testcontainers** (integration testing)
- **AWS EKS + Terraform + ArgoCD** (deployment)
