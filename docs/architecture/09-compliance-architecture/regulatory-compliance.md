# Regulatory Compliance Architecture

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. Regulatory Landscape

| Regulation | Authority | Applicability | Key Requirements |
|-----------|-----------|---------------|------------------|
| RBI Digital Lending Guidelines (2022) | Reserve Bank of India | All digital lending | Transparency, data privacy, fair practices |
| DPDPA 2023 | MeitY | All personal data processing | Consent, purpose limitation, data minimization |
| KYC/AML Guidelines | RBI | Customer onboarding | Identity verification, PEP/sanctions screening |
| Fair Practices Code | RBI | All lending | Communication standards, grievance redressal |
| IT Act 2000 + Amendments | MeitY | All electronic transactions | Data protection, cyber security |
| CERT-In Directives | CERT-In | All IT systems | Incident reporting within 6 hours |
| RBI Master Direction on IT Governance | RBI | Regulated entities | IT risk management, BCP, audit |
| NBFC Master Directions | RBI | Non-banking lenders | Capital adequacy, NPA norms, reporting |

---

## 2. RBI Digital Lending Guidelines Compliance

```
┌─────────────────────────────────────────────────────────────────────────────┐
│            RBI DIGITAL LENDING GUIDELINES — COMPLIANCE MAP                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  REQUIREMENT                          │ IMPLEMENTATION                       │
│  ═══════════════════════════════════════╪══════════════════════════════════   │
│                                        │                                     │
│  1. Disclosure of all-in-cost          │ • Offer Engine calculates APR       │
│     (APR) to borrower at              │ • Key Fact Statement (KFS) API      │
│     application stage                  │ • KFS displayed before acceptance   │
│                                        │ • Stored in loan record             │
│  ─────────────────────────────────────┼─────────────────────────────────    │
│  2. Key Fact Statement (KFS)          │ • Generated at offer stage           │
│     before loan agreement             │ • Includes: APR, EMI, total cost    │
│                                        │ • Cooling-off period enforced       │
│                                        │ • KFS template per product          │
│  ─────────────────────────────────────┼─────────────────────────────────    │
│  3. Cooling-off period (look-up       │ • Configurable per product          │
│     period) with option to exit       │ • Default: 3 days post-disbursal    │
│     without penalty                   │ • API: POST /loans/{id}/exit        │
│                                        │ • No foreclosure charges            │
│  ─────────────────────────────────────┼─────────────────────────────────    │
│  4. Disbursement directly to          │ • Payment Service validates          │
│     borrower's bank account           │   beneficiary = applicant           │
│     (no third-party pool)             │ • No pass-through accounts          │
│                                        │ • Account ownership verification    │
│  ─────────────────────────────────────┼─────────────────────────────────    │
│  5. No automatic increase in          │ • Credit limit changes require      │
│     credit limit without              │   explicit consent (Consent Service)│
│     explicit consent                  │ • Consent stored with timestamp     │
│                                        │ • Consent audit trail               │
│  ─────────────────────────────────────┼─────────────────────────────────    │
│  6. Data to be stored only in         │ • All production in AWS Mumbai      │
│     servers located in India          │ • DR in AWS Hyderabad               │
│                                        │ • No cross-border data transfer     │
│                                        │ • Geo-restriction at CDN level      │
│  ─────────────────────────────────────┼─────────────────────────────────    │
│  7. Access to mobile phone            │ • Platform does not access          │
│     resources restricted to           │   mobile resources directly         │
│     camera, microphone, location     │ • SDK guidelines for partners       │
│     only with consent                 │ • Consent recorded per resource     │
│  ─────────────────────────────────────┼─────────────────────────────────    │
│  8. Grievance redressal mechanism     │ • Grievance API endpoint            │
│     with escalation matrix            │ • SLA-based escalation              │
│                                        │ • Nodal officer integration         │
│                                        │ • RBI Ombudsman integration         │
│  ─────────────────────────────────────┼─────────────────────────────────    │
│  9. Reporting to CICs within          │ • Automated bureau reporting        │
│     specified timelines               │ • Monthly bureau file generation    │
│                                        │ • CIBIL, Experian, Equifax, CRIF   │
│                                        │ • Dispute management workflow       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. DPDPA 2023 Compliance

### 3.1 Data Classification

| Classification | Examples | Controls |
|---------------|----------|----------|
| **Personal Data** | Name, email, phone, address | Encrypted at rest; access logged; consent required |
| **Sensitive Personal Data** | Aadhaar, PAN, financial data, biometrics | Field-level encryption; tokenization; strict access control |
| **Non-Personal Data** | Aggregated statistics, anonymized analytics | Standard security controls |

### 3.2 Consent Management Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CONSENT MANAGEMENT                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  CONSENT LIFECYCLE:                                                          │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐             │
│  │  Request  │───►│  Granted │───►│  Active  │───►│ Withdrawn│             │
│  │  Consent  │    │          │    │          │    │          │             │
│  └──────────┘    └──────────┘    └──────────┘    └──────────┘             │
│                                                                              │
│  CONSENT TYPES:                                                              │
│  • Data collection consent (mandatory for processing)                        │
│  • Bureau check consent (mandatory before credit pull)                       │
│  • Communication consent (SMS/Email/WhatsApp preferences)                    │
│  • Marketing consent (optional, separate from service)                       │
│  • Data sharing consent (with specific third parties)                        │
│  • Auto-debit mandate consent (NACH/e-Mandate)                               │
│                                                                              │
│  CONSENT RECORD:                                                             │
│  {                                                                           │
│    "consent_id": "uuid",                                                     │
│    "customer_id": "uuid",                                                    │
│    "tenant_id": "uuid",                                                      │
│    "purpose": "CREDIT_BUREAU_CHECK",                                         │
│    "data_categories": ["CREDIT_HISTORY", "PERSONAL_IDENTITY"],               │
│    "granted_at": "2026-07-08T10:00:00Z",                                     │
│    "valid_until": "2026-08-08T10:00:00Z",                                    │
│    "consent_mode": "OTP_VERIFIED",                                           │
│    "consent_artifact": "signed-consent-document-url",                        │
│    "withdrawn_at": null,                                                     │
│    "ip_address": "203.0.113.45",                                             │
│    "device_fingerprint": "hash"                                              │
│  }                                                                           │
│                                                                              │
│  IMPLEMENTATION:                                                             │
│  • Consent Service as dedicated microservice                                 │
│  • Consent check as pre-condition for data processing                        │
│  • Consent withdrawal triggers data deletion workflow                        │
│  • Consent records are immutable (append-only)                               │
│  • Retention: consent lifetime + 1 year post-withdrawal                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.3 Data Principal Rights (DPDPA)

| Right | Implementation | SLA |
|-------|---------------|-----|
| Right to Access | API: GET /v1/customers/{id}/data-export | 72 hours |
| Right to Correction | API: PATCH /v1/customers/{id}/profile | Immediate |
| Right to Erasure | Anonymization workflow (where not legally required to retain) | 30 days |
| Right to Grievance Redressal | Grievance API + escalation workflow | 7 days initial response |
| Right to Nominate | Nominee management in customer profile | Immediate |

### 3.4 Data Retention & Deletion

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    DATA RETENTION POLICY                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  DATA TYPE              │ RETENTION        │ POST-RETENTION ACTION            │
│  ═══════════════════════╪══════════════════╪══════════════════════════════    │
│  Active loan data       │ Loan life + 8yr  │ Anonymize + archive             │
│  Closed loan data       │ Closure + 8yr    │ Anonymize + archive             │
│  KYC documents          │ Account + 5yr    │ Secure delete                   │
│  Financial transactions │ 8 years          │ Anonymize (keep aggregates)     │
│  Consent records        │ Withdrawal + 1yr │ Secure delete                   │
│  Communication logs     │ 3 years          │ Delete                          │
│  Audit trails           │ 8 years          │ Archive to Glacier              │
│  Application (rejected) │ 2 years          │ Anonymize                       │
│  Bureau reports         │ 3 years          │ Secure delete                   │
│  Session logs           │ 90 days          │ Delete                          │
│                                                                              │
│  ANONYMIZATION APPROACH:                                                     │
│  • Replace PII with irreversible tokens                                      │
│  • Retain only aggregated/statistical data                                   │
│  • k-anonymity guarantee (min group size = 5)                                │
│  • Automated anonymization pipeline (Flink job)                              │
│  • Verification: cannot re-identify from anonymized data                     │
│                                                                              │
│  LEGAL HOLD:                                                                 │
│  • Suspend deletion for data under legal dispute                             │
│  • Legal hold flag prevents automated deletion                               │
│  • Cleared only by authorized legal team                                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. KYC/AML Compliance

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    KYC/AML ARCHITECTURE                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  KYC LEVELS:                                                                 │
│  • Minimum KYC (Small value loans < ₹50,000)                                │
│    - OTP-verified mobile                                                     │
│    - PAN verification                                                        │
│  • Full KYC (Standard loans)                                                 │
│    - Video KYC / In-person verification                                      │
│    - PAN + Aadhaar (e-KYC via DigiLocker)                                    │
│    - Address verification                                                    │
│    - Income verification                                                     │
│  • Enhanced Due Diligence (High value / PEP)                                 │
│    - Full KYC + source of funds                                              │
│    - PEP screening                                                           │
│    - Sanctions list check (OFAC, UN, India)                                  │
│                                                                              │
│  AML CHECKS:                                                                 │
│  • Transaction monitoring (via Flink stream processing)                      │
│  • Suspicious Activity Report (SAR) generation                               │
│  • Cash Transaction Report (CTR) for > ₹10 lakh                            │
│  • Pattern detection (structuring, layering)                                 │
│  • Sanctions screening on every disbursement                                 │
│                                                                              │
│  INTEGRATION POINTS:                                                         │
│  ┌──────────────────────────────────────────────────────┐                   │
│  │  Partner Service (ACL)                                │                   │
│  │  ├── NSDL (PAN verification)                          │                   │
│  │  ├── UIDAI (Aadhaar e-KYC via DigiLocker)            │                   │
│  │  ├── CERSAI (property check)                          │                   │
│  │  ├── Sanctions databases (OFAC, UN, India)            │                   │
│  │  ├── PEP databases                                    │                   │
│  │  ├── Court/legal databases                            │                   │
│  │  └── Bank account verification (penny drop)           │                   │
│  └──────────────────────────────────────────────────────┘                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Regulatory Reporting

| Report | Frequency | Authority | Format | Automation |
|--------|-----------|-----------|--------|-----------|
| NPA Report | Monthly | RBI | XBRL | Fully automated |
| Sector-wise lending | Quarterly | RBI | CSV/XBRL | Automated |
| Interest rate disclosure | Monthly | Public | Website | Automated |
| Bureau reporting (CIBIL) | Monthly | Credit bureaus | Proprietary format | Automated |
| Fraud reporting | Ad-hoc | RBI | Online portal | Semi-automated |
| SAR (Suspicious Activity) | Ad-hoc | FIU-IND | FINnet | Semi-automated |
| CTR (Cash Transactions) | Monthly | FIU-IND | FINnet | Automated |
| Customer complaints | Quarterly | RBI | Excel | Automated |
| Cyber incident reporting | Within 6 hours | CERT-In | Email + Portal | Manual trigger |
| Fair practices compliance | Annual | RBI | Self-certification | Automated evidence |

---

## 6. Compliance Automation

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    COMPLIANCE-AS-CODE                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PRINCIPLE: Compliance requirements encoded as executable policies            │
│                                                                              │
│  TOOLS:                                                                      │
│  • OPA/Rego: Authorization and data access policies                          │
│  • Sentinel/Terraform: Infrastructure compliance                             │
│  • Custom Flink jobs: Transaction monitoring rules                           │
│  • ArchUnit: Architectural compliance in code                                │
│  • CI/CD gates: Compliance checks in pipeline                                │
│                                                                              │
│  AUTOMATED CHECKS:                                                           │
│  ┌──────────────────────────────────────────────────────────────┐           │
│  │  Build Time                                                   │           │
│  │  ├── ArchUnit: No BigDecimal.valueOf(double) usage            │           │
│  │  ├── ArchUnit: All entities have tenant_id field              │           │
│  │  ├── ArchUnit: PII fields annotated with @Sensitive           │           │
│  │  ├── SAST: No hardcoded secrets                               │           │
│  │  └── Dependency scan: No known CVEs                           │           │
│  └──────────────────────────────────────────────────────────────┘           │
│  ┌──────────────────────────────────────────────────────────────┐           │
│  │  Deploy Time                                                  │           │
│  │  ├── Terraform Sentinel: Data residency (ap-south only)       │           │
│  │  ├── Admission Controller: No privileged containers           │           │
│  │  ├── Image signing: Only signed images from private registry  │           │
│  │  └── Network policy: All ingress/egress explicitly defined    │           │
│  └──────────────────────────────────────────────────────────────┘           │
│  ┌──────────────────────────────────────────────────────────────┐           │
│  │  Runtime                                                      │           │
│  │  ├── OPA: Tenant data isolation verified on every request     │           │
│  │  ├── Consent check: Data processing blocked without consent   │           │
│  │  ├── Rate limiting: Fair usage per RBI guidelines             │           │
│  │  ├── Flink: AML transaction monitoring (real-time)            │           │
│  │  └── Audit: Every state change logged immutably               │           │
│  └──────────────────────────────────────────────────────────────┘           │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Compliance Dashboard & Evidence

| Metric | Source | Target | Alert Threshold |
|--------|--------|--------|-----------------|
| KYC completion rate | Customer Service | 100% for active loans | < 99.5% |
| Consent coverage | Consent Service | 100% for data processing | Any gap |
| Data residency | Infrastructure | 100% India | Any non-India resource |
| Audit log completeness | Audit Service | 100% state changes captured | Any gap detected |
| Encryption coverage | Security scans | 100% PII encrypted | Any plaintext PII |
| Regulatory report timeliness | Reporting Service | 100% on-time | Any delay |
| Grievance SLA adherence | Grievance Service | 100% within SLA | > 5% breach |
| Bureau reporting accuracy | Reporting Service | 99.9% match rate | > 0.5% discrepancy |
