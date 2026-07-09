# API Strategy — External REST API Design

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. API Design Philosophy

| Principle | Implementation |
|-----------|---------------|
| API-First | OpenAPI 3.1 spec written before code; code generated from spec |
| Contract-First | API contract is the source of truth; backward compatible changes only |
| Resource-Oriented | REST resources map to domain aggregates |
| Consistent | Uniform patterns across all services |
| Secure | OAuth2 + tenant isolation + rate limiting on every endpoint |
| Idempotent | State-changing operations support idempotency keys |
| Versioned | URL path versioning (/v1/, /v2/) for breaking changes |
| Documented | OpenAPI spec + developer portal + examples |

---

## 2. URL Convention

```
Base URL: https://api.originex.io/{tenant-slug}/v{version}/{resource}

Examples:
  POST   https://api.originex.io/acme-bank/v1/loan-applications
  GET    https://api.originex.io/acme-bank/v1/loan-applications/{id}
  GET    https://api.originex.io/acme-bank/v1/loans?status=ACTIVE&page[cursor]=xxx
  POST   https://api.originex.io/acme-bank/v1/loans/{id}/disbursements
  GET    https://api.originex.io/acme-bank/v1/loans/{id}/repayment-schedule
  POST   https://api.originex.io/acme-bank/v1/payments/orders
  GET    https://api.originex.io/acme-bank/v1/ledger/accounts/{id}/statements

Internal (gRPC):
  originex.los.ApplicationService/SubmitApplication
  originex.lms.LoanService/GetLoan
  originex.bre.RuleService/EvaluateEligibility
  originex.ledger.AccountService/GetBalance
```

---

## 3. Request/Response Standards

### 3.1 Standard Request Headers

| Header | Required | Description |
|--------|----------|-------------|
| Authorization | Yes | Bearer {JWT access token} |
| X-Tenant-Id | Yes (or path) | Tenant identifier (validated against token claims) |
| X-Request-Id | Yes | Unique request ID (UUIDv4) for tracing |
| X-Idempotency-Key | Conditional | Required for POST/PUT/PATCH operations |
| Content-Type | Yes | application/json |
| Accept | Optional | application/json (default) |
| X-Api-Version | Optional | Override URL version (future-proofing) |
| If-Match | Conditional | ETag for optimistic locking on updates |

### 3.2 Standard Success Response

```json
{
  "data": {
    "id": "loan-app-7f3a8b2c-1234-5678-9abc-def012345678",
    "type": "loan-application",
    "attributes": {
      "status": "SUBMITTED",
      "applicant_name": "Rajesh Kumar",
      "requested_amount": {
        "value": "500000.00",
        "currency": "INR"
      },
      "created_at": "2026-07-08T10:30:00.000Z",
      "updated_at": "2026-07-08T10:30:00.000Z"
    },
    "relationships": {
      "customer": {
        "data": { "type": "customer", "id": "cust-abc123" }
      }
    },
    "links": {
      "self": "/acme-bank/v1/loan-applications/loan-app-7f3a8b2c-1234-5678-9abc-def012345678",
      "documents": "/acme-bank/v1/loan-applications/loan-app-7f3a8b2c-1234-5678-9abc-def012345678/documents"
    }
  },
  "meta": {
    "request_id": "req-uuid-here",
    "timestamp": "2026-07-08T10:30:00.123Z",
    "version": "v1"
  }
}
```

### 3.3 Standard Error Response (RFC 7807 Problem Details)

```json
{
  "type": "https://api.originex.io/problems/validation-error",
  "title": "Validation Failed",
  "status": 422,
  "detail": "The loan application contains invalid fields",
  "instance": "/acme-bank/v1/loan-applications",
  "errors": [
    {
      "field": "requested_amount.value",
      "code": "AMOUNT_EXCEEDS_PRODUCT_LIMIT",
      "message": "Requested amount exceeds maximum product limit of 10,000,000.00 INR",
      "meta": {
        "max_allowed": "10000000.00",
        "requested": "15000000.00"
      }
    }
  ],
  "meta": {
    "request_id": "req-uuid-here",
    "timestamp": "2026-07-08T10:30:00.123Z",
    "correlation_id": "corr-uuid-here"
  }
}
```

### 3.4 HTTP Status Code Usage

| Code | Meaning | When to Use |
|------|---------|------------|
| 200 | OK | Successful GET, PUT, PATCH |
| 201 | Created | Successful POST (resource created) |
| 202 | Accepted | Async operation accepted (disbursement, payment) |
| 204 | No Content | Successful DELETE |
| 400 | Bad Request | Malformed request syntax |
| 401 | Unauthorized | Missing/invalid/expired token |
| 403 | Forbidden | Valid token but insufficient permissions |
| 404 | Not Found | Resource doesn't exist (or tenant can't see it) |
| 409 | Conflict | Duplicate idempotency key in-flight; version conflict |
| 422 | Unprocessable Entity | Business validation failure |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Internal Server Error | Unexpected server error |
| 502 | Bad Gateway | Upstream service unavailable |
| 503 | Service Unavailable | Service temporarily unavailable |

---

## 4. Pagination (Cursor-Based)

**Why cursor-based over offset-based:**
- Consistent results even as data changes
- No performance degradation at high offsets
- No skipped/duplicate results during pagination
- Better for real-time data feeds

```json
// Request
GET /v1/loans?page[size]=25&page[after]=eyJsb2FuX2lkIjoiYWJjMTIzIn0=

// Response
{
  "data": [...],
  "links": {
    "self": "/v1/loans?page[size]=25&page[after]=eyJsb2FuX2lkIjoiYWJjMTIzIn0=",
    "next": "/v1/loans?page[size]=25&page[after]=eyJsb2FuX2lkIjoiZGVmNDU2In0=",
    "prev": "/v1/loans?page[size]=25&page[before]=eyJsb2FuX2lkIjoiYWJjMTIzIn0="
  },
  "meta": {
    "page": {
      "size": 25,
      "has_next": true,
      "has_prev": true,
      "total_count": 1523
    }
  }
}
```

**Cursor encoding:** Base64-encoded JSON object containing sort key values. Opaque to client.

---

## 5. Filtering & Sorting

```
GET /v1/loans?filter[status]=ACTIVE,OVERDUE
             &filter[disbursement_date][gte]=2026-01-01
             &filter[disbursement_date][lte]=2026-06-30
             &filter[outstanding_principal][gt]=100000
             &sort=-created_at,+loan_id
             &fields[loans]=loan_id,status,outstanding_principal
```

| Operator | Meaning | Example |
|----------|---------|---------|
| (none) | Equals / In | filter[status]=ACTIVE,OVERDUE |
| [gt] | Greater than | filter[amount][gt]=100000 |
| [gte] | Greater than or equal | filter[date][gte]=2026-01-01 |
| [lt] | Less than | filter[amount][lt]=500000 |
| [lte] | Less than or equal | filter[date][lte]=2026-12-31 |
| [contains] | Text search | filter[name][contains]=Kumar |

---

## 6. API Versioning Strategy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    VERSIONING STRATEGY                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  METHOD: URL Path Versioning (/v1/, /v2/)                                    │
│                                                                              │
│  WHY PATH over Header/Query/Media-Type:                                      │
│  • Most visible and discoverable                                             │
│  • Easy to route at API gateway level                                        │
│  • Clear in documentation and logs                                           │
│  • Industry standard for B2B APIs                                            │
│                                                                              │
│  VERSIONING RULES:                                                           │
│  • Minor changes: Add fields (non-breaking, no version bump)                 │
│  • Major changes: Remove fields, change semantics → new version              │
│  • Support N-1: Always maintain current and previous major version           │
│  • Sunset policy: 12 months after new version GA                             │
│  • Sunset header: Sunset: Sat, 01 Jul 2027 00:00:00 GMT                     │
│  • Deprecation header: Deprecation: true                                     │
│                                                                              │
│  NON-BREAKING CHANGES (no version bump):                                     │
│  ✓ Adding a new field to response                                            │
│  ✓ Adding a new optional query parameter                                     │
│  ✓ Adding a new endpoint                                                     │
│  ✓ Adding a new enum value to response                                       │
│  ✓ Making a required request field optional                                  │
│                                                                              │
│  BREAKING CHANGES (require new version):                                     │
│  ✗ Removing a field from response                                            │
│  ✗ Changing a field's type                                                   │
│  ✗ Renaming a field                                                          │
│  ✗ Making an optional request field required                                 │
│  ✗ Changing response status code for same scenario                           │
│  ✗ Changing error code semantics                                             │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Rate Limiting

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    RATE LIMITING STRATEGY                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  LAYERS:                                                                     │
│  1. Global (WAF/CloudFront): 10,000 req/min per IP                          │
│  2. Tenant (API Gateway): Configurable per plan                              │
│  3. Endpoint (Service): Critical endpoints have lower limits                 │
│                                                                              │
│  TENANT PLANS:                                                               │
│  ┌─────────────┬──────────────┬──────────────┬──────────────┐              │
│  │    Plan     │  Standard    │   Premium    │  Enterprise  │              │
│  ├─────────────┼──────────────┼──────────────┼──────────────┤              │
│  │  req/min    │    1,000     │    5,000     │   50,000     │              │
│  │  req/day    │  100,000     │  500,000     │ 5,000,000    │              │
│  │  burst      │    100       │    500       │    5,000     │              │
│  └─────────────┴──────────────┴──────────────┴──────────────┘              │
│                                                                              │
│  ALGORITHM: Token Bucket (via Redis)                                         │
│  • Tokens replenish at configured rate                                       │
│  • Burst allows temporary spikes                                             │
│  • Distributed counter via Redis MULTI/EXEC                                  │
│                                                                              │
│  RESPONSE HEADERS:                                                           │
│  X-RateLimit-Limit: 1000                                                     │
│  X-RateLimit-Remaining: 950                                                  │
│  X-RateLimit-Reset: 1720440000 (Unix timestamp)                              │
│  Retry-After: 60 (seconds, only on 429)                                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Authentication & Authorization

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    API AUTHENTICATION FLOW                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Partner System                API Gateway              IAM Service           │
│       │                            │                        │                │
│       │ 1. POST /oauth/token       │                        │                │
│       │    (client_credentials)    │                        │                │
│       ├───────────────────────────►│                        │                │
│       │                            ├───────────────────────►│                │
│       │                            │     Validate creds     │                │
│       │                            │◄───────────────────────┤                │
│       │◄───────────────────────────┤    JWT (access_token)  │                │
│       │    { access_token, ...}    │                        │                │
│       │                            │                        │                │
│       │ 2. GET /v1/loans           │                        │                │
│       │    Authorization: Bearer   │                        │                │
│       ├───────────────────────────►│                        │                │
│       │                            │ Validate JWT (local)   │                │
│       │                            │ Extract tenant_id      │                │
│       │                            │ Check permissions      │                │
│       │                            │ Rate limit check       │                │
│       │                            ├───────────────────────►│ Service        │
│       │◄───────────────────────────┤                        │                │
│       │    200 OK + data           │                        │                │
│                                                                              │
│  JWT CLAIMS:                                                                 │
│  {                                                                           │
│    "sub": "api-client-uuid",                                                 │
│    "tenant_id": "tenant-uuid",                                               │
│    "tenant_slug": "acme-bank",                                               │
│    "roles": ["LOAN_ORIGINATOR", "LOAN_VIEWER"],                              │
│    "permissions": ["loans:create", "loans:read", "applications:*"],          │
│    "scope": "los lms payments",                                              │
│    "iss": "https://auth.originex.io",                                        │
│    "exp": 1720443600,                                                        │
│    "iat": 1720440000                                                         │
│  }                                                                           │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 9. Optimistic Locking

```
// Read with ETag
GET /v1/loans/loan-123
Response:
  ETag: "v5"
  Body: { "data": { "id": "loan-123", "version": 5, ... } }

// Update with If-Match
PUT /v1/loans/loan-123
Headers:
  If-Match: "v5"
Body: { "data": { ... } }

// If version matches → 200 OK, new ETag: "v6"
// If version conflict → 409 Conflict
{
  "type": "https://api.originex.io/problems/conflict",
  "title": "Version Conflict",
  "status": 409,
  "detail": "Resource was modified by another request. Please retry with latest version.",
  "meta": { "current_version": 6, "your_version": 5 }
}
```

---

## 10. Internal gRPC Design

### 10.1 Service Definitions

```protobuf
// los-service.proto
syntax = "proto3";
package originex.los;

service ApplicationService {
  rpc SubmitApplication(SubmitApplicationRequest) returns (SubmitApplicationResponse);
  rpc GetApplication(GetApplicationRequest) returns (Application);
  rpc ListApplications(ListApplicationsRequest) returns (ListApplicationsResponse);
  rpc UpdateApplicationStatus(UpdateStatusRequest) returns (Application);
}

message SubmitApplicationRequest {
  string tenant_id = 1;
  string customer_id = 2;
  string product_code = 3;
  Money requested_amount = 4;
  int32 requested_tenure_months = 5;
  string idempotency_key = 6;
  map<string, string> metadata = 7;
}

message Money {
  string value = 1;      // String representation of BigDecimal (e.g., "500000.00")
  string currency = 2;   // ISO 4217 (e.g., "INR")
}
```

### 10.2 gRPC Interceptors (Cross-Cutting)

| Interceptor | Purpose |
|-------------|---------|
| TenantInterceptor | Extract and validate tenant_id from metadata |
| AuthInterceptor | Validate service-to-service JWT/mTLS identity |
| TracingInterceptor | Propagate OpenTelemetry context |
| LoggingInterceptor | Request/response logging (PII masked) |
| MetricsInterceptor | Record latency, error rate per method |
| RateLimitInterceptor | Service-level rate limiting |
| DeadlineInterceptor | Enforce call deadline propagation |

---

## 11. Webhook Strategy (Outbound)

```json
// Webhook registration
POST /v1/webhooks
{
  "url": "https://partner.example.com/callbacks",
  "events": ["application.approved", "loan.disbursed", "payment.received"],
  "secret": "whsec_...",  // For signature verification
  "active": true
}

// Webhook delivery
POST https://partner.example.com/callbacks
Headers:
  X-Originex-Signature: sha256=abc123...
  X-Originex-Timestamp: 1720440000
  X-Originex-Event-Id: evt-uuid
  X-Originex-Event-Type: loan.disbursed
  Content-Type: application/json

Body:
{
  "id": "evt-uuid",
  "type": "loan.disbursed",
  "created_at": "2026-07-08T10:30:00.000Z",
  "data": {
    "loan_id": "loan-123",
    "amount": { "value": "500000.00", "currency": "INR" },
    "disbursed_at": "2026-07-08T10:30:00.000Z"
  }
}
```

**Webhook Delivery Guarantees:**
- At-least-once delivery (retries on failure)
- Retry schedule: 30s, 2min, 15min, 1h, 4h, 24h
- Disable after 7 consecutive days of failures
- Partner must respond with 2xx within 30 seconds
- Signature verification using HMAC-SHA256
