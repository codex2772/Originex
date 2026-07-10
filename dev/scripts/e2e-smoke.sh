#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# e2e-smoke.sh — full-stack end-to-end smoke test of the lending journey.
#
# Orchestrates the real services and walks Customer → KYC → Application →
# Credit-check/BRE → Offer → Bank account → Accept → Disbursement → ACTIVE →
# (accrual) → Repayment reachability, asserting REST responses, DB rows, Kafka,
# and the transactional outbox/inbox at each step.
#
# PREREQUISITES (this script does NOT build/start services — see the guide):
#   1. Infra up:   docker compose -f dev/docker-compose.yml up -d
#      (on a host where postgres:5432 and the cp-kafka KRaft container are
#       actually healthy — see dev/PHASE0_VERIFICATION.md for known local
#       blockers; CI and clean hosts are fine)
#   2. All 8 services running on their ports (8081..8088). Build with
#      `mvn -s dev/settings.xml clean install -DskipTests` then run each
#      (`mvn -pl services/<name> spring-boot:run`) or their Jib images.
#
# This is the cross-service complement to LoanLifecycleIntegrationTest, which
# covers the LMS disbursement→accrual→repayment slice automatically in CI.
#
# Usage: dev/scripts/e2e-smoke.sh
# Exit 0 on success; non-zero (with the failing step) otherwise.
# ═══════════════════════════════════════════════════════════════════════════
set -uo pipefail

TENANT="00000000-0000-0000-0000-000000000001"   # sentinel tenant: BRE default rules + ledger GL + templates
H_TENANT=(-H "X-Tenant-Id: ${TENANT}")
H_JSON=(-H "Content-Type: application/json")
PSQL() { docker exec originex-postgres psql -U originex -tAc "$2" -d "$1"; }
PASS=0; FAIL=0
ok()   { echo "  [PASS] $1"; PASS=$((PASS+1)); }
bad()  { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
step() { echo; echo "── $1 ──"; }
jqr()  { python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',''))"; }

CUSTOMER=8081; LOS=8082; LMS=8083; LEDGER=8084; PARTNER=8085; PAYMENT=8086; BRE=8088

step "0. Health"
for p in $CUSTOMER $LOS $LMS $LEDGER $PARTNER $PAYMENT $BRE; do
  s=$(curl -s "http://localhost:$p/actuator/health" | jqr status)
  [ "$s" = "UP" ] && ok "service :$p UP" || { bad "service :$p not UP ($s)"; }
done

step "1. Register customer"
CID=$(curl -s -X POST "http://localhost:$CUSTOMER/v1/customers" "${H_TENANT[@]}" "${H_JSON[@]}" \
  -d '{"firstName":"Test","lastName":"Borrower","phone":"9999999999","email":"t@ex.com","panNumber":"ABCDE1234F","dateOfBirth":"1990-01-01"}' \
  | jqr id)
[ -n "$CID" ] && ok "customer created: $CID" || bad "customer create failed"
[ "$(PSQL originex_customer "select count(*) from customers where customer_id='$CID'")" = "1" ] \
  && ok "customers row present" || bad "customers row missing"
[ -n "$(PSQL originex_customer "select 1 from outbox_events where event_type='originex.customer.CustomerRegistered' limit 1")" ] \
  && ok "outbox CustomerRegistered present" || bad "outbox CustomerRegistered missing"

step "2. KYC (Aadhaar eKYC, sandbox)"
curl -s -o /dev/null -w "  kyc http %{http_code}\n" -X POST \
  "http://localhost:$CUSTOMER/v1/customers/$CID/kyc/aadhaar-ekyc" "${H_TENANT[@]}" "${H_JSON[@]}" \
  -d '{"aadhaarNumberOrVid":"123412341234","consentArtifactId":"c1","otpReference":"otp1"}'
[ "$(PSQL originex_customer "select count(*) from kyc_records where status='VERIFIED'")" -ge 1 ] \
  && ok "KYC VERIFIED" || bad "KYC not verified"

step "3. Add bank account (required for disbursement beneficiary)"
curl -s -o /dev/null -w "  bank-account http %{http_code}\n" -X POST \
  "http://localhost:$CUSTOMER/v1/customers/$CID/bank-accounts" "${H_TENANT[@]}" "${H_JSON[@]}" \
  -d '{"accountNumber":"1234567890","ifscCode":"SBIN0001234","accountHolderName":"Test Borrower","bankName":"SBI","accountType":"SAVINGS","primary":true}'
PRIMARY=$(curl -s "http://localhost:$CUSTOMER/v1/customers/$CID/bank-accounts/primary" "${H_TENANT[@]}" | jqr accountNumber)
[ "$PRIMARY" = "1234567890" ] && ok "primary bank account resolvable" || bad "primary bank account not resolvable ($PRIMARY)"

step "4. Submit loan application"
AID=$(curl -s -X POST "http://localhost:$LOS/v1/loan-applications" "${H_TENANT[@]}" "${H_JSON[@]}" \
  -d "{\"customerId\":\"$CID\",\"productCode\":\"PERSONAL_LOAN\",\"amount\":\"500000\",\"currency\":\"INR\",\"tenureMonths\":24,\"purpose\":\"Home renovation\",\"channel\":\"MOBILE_APP\",\"applicantName\":\"Test Borrower\",\"applicantPan\":\"ABCDE1234F\",\"employmentType\":\"SALARIED\",\"monthlyIncome\":\"75000\"}" \
  | jqr id)
[ -n "$AID" ] && ok "application: $AID (SUBMITTED)" || bad "application submit failed"

step "5. Credit check (bureau sandbox + BRE)"
DECISION=$(curl -s -X POST "http://localhost:$LOS/v1/loan-applications/$AID/credit-check" "${H_TENANT[@]}" "${H_JSON[@]}" \
  -d '{"consentArtifactId":"c1"}' | jqr status)
echo "  decision status: $DECISION"
case "$DECISION" in
  OFFER_PENDING) ok "auto-approved → OFFER_PENDING" ;;
  REFERRED) echo "  REFERRED → manual approve"; curl -s -o /dev/null -X POST \
     "http://localhost:$LOS/v1/loan-applications/$AID/approve" "${H_TENANT[@]}" "${H_JSON[@]}" \
     -d '{"sanctionedAmount":"450000","interestRate":"12.5","tenureMonths":24,"emi":"21250","processingFee":"4500","apr":"13.8","notes":"smoke"}'; ok "manually approved" ;;
  REJECTED) bad "application REJECTED — adjust income/amount to pass BRE"; ;;
  *) bad "unexpected decision: $DECISION" ;;
esac

step "6. Accept offer → DisbursementRequested"
curl -s -o /dev/null -w "  accept http %{http_code}\n" -X POST \
  "http://localhost:$LOS/v1/loan-applications/$AID/offer/accept" "${H_TENANT[@]}"
[ "$(PSQL originex_los "select status from loan_applications where application_id='$AID'")" = "DISBURSEMENT_REQUESTED" ] \
  && ok "application DISBURSEMENT_REQUESTED" || bad "application not DISBURSEMENT_REQUESTED"
[ -n "$(PSQL originex_los "select 1 from outbox_events where event_type='originex.los.DisbursementRequested' limit 1")" ] \
  && ok "outbox DisbursementRequested present" || bad "outbox DisbursementRequested missing"

step "7. LMS creates loan, initiates disbursement, publishes LoanDisbursed"
for i in $(seq 1 20); do
  LOAN=$(PSQL originex_lms "select loan_id from loans where application_id='$AID' limit 1"); [ -n "$LOAN" ] && break; sleep 1; done
[ -n "$LOAN" ] && ok "loan created: $LOAN" || bad "loan not created (LMS consumed DisbursementRequested?)"
[ -n "$(PSQL originex_lms "select 1 from outbox_events where event_type='originex.lms.LoanDisbursed' limit 1")" ] \
  && ok "outbox LoanDisbursed present" || bad "outbox LoanDisbursed missing"
[ -n "$(PSQL originex_lms "select 1 from inbox_events limit 1")" ] \
  && ok "inbox recorded the consumed event" || bad "inbox empty"

step "8. Payment (sandbox) → DisbursementCompleted → loan ACTIVE"
for i in $(seq 1 25); do
  ST=$(PSQL originex_lms "select status from loans where loan_id='$LOAN'"); [ "$ST" = "ACTIVE" ] && break; sleep 1; done
[ "$ST" = "ACTIVE" ] && ok "loan ACTIVE (payment→confirmDisbursementByPayment)" || bad "loan not ACTIVE (status=$ST)"
[ -n "$(PSQL originex_payment "select 1 from payment_orders where loan_id='$LOAN' limit 1")" ] \
  && ok "payment_order created" || bad "payment_order missing"

step "9. Ledger disbursement posting"
for i in $(seq 1 15); do
  JE=$(PSQL originex_ledger "select count(*) from journal_entries where entry_type='DISBURSEMENT'"); [ "$JE" -ge 1 ] && break; sleep 1; done
[ "${JE:-0}" -ge 1 ] && ok "ledger DISBURSEMENT journal entry posted" || bad "no ledger disbursement entry (GL accounts seeded for this tenant?)"

step "10. Repayment reachability"
curl -s -o /dev/null -w "  repayment http %{http_code}\n" -X POST \
  "http://localhost:$LMS/v1/loans/$LOAN/repayments" "${H_TENANT[@]}" "${H_JSON[@]}" \
  -d '{"amount":"10000","currency":"INR","paymentReference":"PAY-1"}'
[ -n "$(PSQL originex_lms "select 1 from outbox_events where event_type='originex.lms.RepaymentAllocated' limit 1")" ] \
  && ok "repayment allocated (RepaymentAllocated published)" || bad "repayment not allocated (loan ACTIVE?)"

echo; echo "══ RESULT: PASS=$PASS FAIL=$FAIL ══"
echo "Note: interest accrual runs on a daily cron (originex.lms.accrual.cron); it is"
echo "not exercised here. Verify it via LoanLifecycleIntegrationTest, or set a"
echo "near-future cron and re-query loans.outstanding_interest after it fires."
[ "$FAIL" -eq 0 ] || exit 1
