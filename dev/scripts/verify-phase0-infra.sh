#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# verify-phase0-infra.sh
#
# Repeatable, non-destructive check of the infra-level portion of the Phase 0
# verification checklist (see dev/PHASE0_VERIFICATION.md):
#   1. docker compose startup + container health
#   2. database creation verification (actual vs. expected per-service DBs)
#   3. Kafka broker reachability + topic listing vs. infra/kafka/topics.yaml
#
# This script intentionally does NOT start any of the 9 Spring Boot services
# or run the Customer -> LOS -> LMS -> Ledger smoke flow — those steps
# require built JARs and are covered separately in the checklist doc, since
# they depend on which Phase 0 commits have already landed.
#
# Usage:
#   dev/scripts/verify-phase0-infra.sh up      # start stack, wait healthy, report
#   dev/scripts/verify-phase0-infra.sh status  # report only, don't start anything
#   dev/scripts/verify-phase0-infra.sh down    # stop the stack
#
# Safe to run before AND after each Phase 0 commit — it only reports drift,
# it never fails hard on a known-not-yet-fixed gap (those are printed as
# "KNOWN GAP" rather than a script failure) so it can serve as a before/after
# diff tool.
# ═══════════════════════════════════════════════════════════════════════════
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/dev/docker-compose.yml"
TOPICS_YAML="$REPO_ROOT/infra/kafka/topics.yaml"

MODE="${1:-up}"

# Every service database the 9 Maven-module services actually expect,
# derived from each service's application.yml spring.datasource.url.
EXPECTED_DBS=(
  originex_customer
  originex_los
  originex_lms
  originex_ledger
  originex_partner
  originex_payment
  originex_notification
  originex_bre
  originex_template
)

PASS=0
FAIL=0
KNOWN_GAP=0

pass() { echo "  [PASS] $1"; PASS=$((PASS+1)); }
fail() { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
gap()  { echo "  [KNOWN GAP] $1"; KNOWN_GAP=$((KNOWN_GAP+1)); }
info() { echo "  [INFO] $1"; }

section() { echo; echo "── $1 ──"; }

if [[ "$MODE" == "down" ]]; then
  docker compose -f "$COMPOSE_FILE" down
  exit 0
fi

if [[ "$MODE" == "up" ]]; then
  section "1. docker compose startup"
  docker compose -f "$COMPOSE_FILE" up -d
fi

section "1. Container health"
for svc in originex-postgres originex-kafka originex-schema-registry originex-redis originex-kafka-ui; do
  status=$(docker inspect --format='{{.State.Status}}' "$svc" 2>/dev/null || echo "absent")
  if [[ "$status" != "running" ]]; then
    if [[ "$MODE" == "status" ]]; then
      fail "$svc is not running (status=$status) — run with 'up' to start the stack"
      continue
    fi
    info "$svc is $status, waiting up to 60s for it to report running..."
    for i in $(seq 1 30); do
      status=$(docker inspect --format='{{.State.Status}}' "$svc" 2>/dev/null || echo "absent")
      [[ "$status" == "running" ]] && break
      sleep 2
    done
  fi
  health=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$svc" 2>/dev/null || echo "absent")
  if [[ "$status" == "running" && ( "$health" == "healthy" || "$health" == "no-healthcheck" ) ]]; then
    pass "$svc running (health=$health)"
  else
    fail "$svc not healthy (status=$status, health=$health)"
  fi
done

section "2. Database creation verification (actual vs. expected)"
ACTUAL_DBS=$(docker exec originex-postgres psql -U originex -d originex_dev -tAc \
  "SELECT datname FROM pg_database WHERE datname LIKE 'originex_%' ORDER BY 1;" 2>/dev/null)

if [[ -z "$ACTUAL_DBS" ]]; then
  fail "Could not read database list from originex-postgres (is it healthy yet?)"
else
  info "Databases actually present: $(echo "$ACTUAL_DBS" | tr '\n' ' ')"
  for db in "${EXPECTED_DBS[@]}"; do
    if echo "$ACTUAL_DBS" | grep -qx "$db"; then
      pass "database exists: $db"
    else
      gap "database MISSING: $db (fixed by Phase 0 commit 3 — dev database initialization)"
    fi
  done
  for db in $ACTUAL_DBS; do
    match=false
    for exp in "${EXPECTED_DBS[@]}" originex_dev; do
      [[ "$db" == "$exp" ]] && match=true && break
    done
    if [[ "$match" == "false" ]]; then
      info "extra/unexpected database present: $db (belongs to an unbuilt service — harmless, not required)"
    fi
  done
fi

section "3. Kafka broker reachability + topic listing"
if docker exec originex-kafka kafka-broker-api-versions --bootstrap-server localhost:29092 >/dev/null 2>&1; then
  pass "kafka broker reachable"
  ACTUAL_TOPICS=$(docker exec originex-kafka kafka-topics --bootstrap-server localhost:29092 --list 2>/dev/null | grep -v '^__' || true)
  info "topics currently on the local broker: $(echo "$ACTUAL_TOPICS" | tr '\n' ' ' | sed 's/^ *$/(none yet — created lazily on first publish)/')"

  if [[ -f "$TOPICS_YAML" ]]; then
    DEFINED_TOPICS=$(grep '^  name:' "$TOPICS_YAML" | awk '{print $2}')
    TOPIC_COUNT=$(echo "$DEFINED_TOPICS" | grep -c .)
    info "infra/kafka/topics.yaml defines $TOPIC_COUNT Strimzi KafkaTopic CRDs (informational — these apply to the EKS/Strimzi cluster, NOT to this local docker-compose broker, which auto-creates topics on first publish)"
    echo "$DEFINED_TOPICS" | while read -r t; do
      [[ -z "$t" ]] && continue
      if echo "$ACTUAL_TOPICS" | grep -qx "$t"; then
        info "  present locally: $t"
      fi
    done
  fi
else
  fail "kafka broker not reachable on localhost:29092 (via docker exec)"
fi

section "Summary"
echo "  PASS=$PASS  FAIL=$FAIL  KNOWN_GAP=$KNOWN_GAP"
if [[ $FAIL -gt 0 ]]; then
  echo "  Result: INFRA NOT HEALTHY — investigate FAIL items above."
  exit 1
fi
echo "  Result: infra layer OK. KNOWN_GAP items above are open Phase 0 backlog items, not regressions."
exit 0
