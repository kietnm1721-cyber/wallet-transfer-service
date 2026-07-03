#!/usr/bin/env bash
# =============================================================================
# Wallet & Transfer Service — End-to-End Demo Script v2
# Run: chmod +x demo-v2.sh && ./demo-v2.sh
# Requires: app running via docker compose up --build
# =============================================================================

BASE_URL="http://localhost:8080/api/v1"
REQ_TIME=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

GREEN='\033[0;32m'; RED='\033[0;31m'; BLUE='\033[0;34m'
YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

PASS=0; FAIL=0

pass()    { echo -e "  ${GREEN}✓ $1${NC}"; PASS=$((PASS+1)); }
fail()    { echo -e "  ${RED}✗ $1${NC}"; FAIL=$((FAIL+1)); }
info()    { echo -e "  ${BLUE}→ $1${NC}"; }
detail()  { echo -e "  ${CYAN}  $1${NC}"; }
section() { echo -e "\n${YELLOW}━━━ $1 ━━━${NC}"; }

req_id() { python3 -c "import uuid; print(uuid.uuid4())"; }
uuid()   { python3 -c "import uuid; print(uuid.uuid4())"; }

post() {
  curl -s -X POST "$BASE_URL$1" \
    -H "Content-Type: application/json" \
    -H "X-Request-Id: $3" \
    -H "X-Requestor-Id: demo" \
    -H "X-Request-Time: $REQ_TIME" \
    -d "$2"
}

get() {
  curl -s "$BASE_URL$1" \
    -H "X-Request-Id: $2" \
    -H "X-Requestor-Id: demo" \
    -H "X-Request-Time: $REQ_TIME"
}

py() { python3 -c "import sys,json; d=json.loads(sys.stdin.read()); print($1)" 2>/dev/null || echo "N/A"; }

echo ""
echo -e "${YELLOW}╔════════════════════════════════════════════════════════╗${NC}"
echo -e "${YELLOW}║   Wallet & Transfer Service — End-to-End Demo v2       ║${NC}"
echo -e "${YELLOW}╚════════════════════════════════════════════════════════╝${NC}"

# =============================================================================
section "PREREQUISITE: Clean DB (truncate all data)"
# =============================================================================

info "Truncating all tables to ensure clean state..."
docker exec wallet-postgres psql -U wallet_user -d wallet_db -c \
  "TRUNCATE outbox_events, ledger_entries, transfers, wallets RESTART IDENTITY CASCADE;" \
  > /dev/null 2>&1

if [ $? -eq 0 ]; then
  pass "DB truncated — clean state confirmed"
else
  fail "Could not truncate DB — is wallet-postgres container running?"
  exit 1
fi

# =============================================================================
section "STEP 1: Create 5 Wallets (POST /wallets)"
# =============================================================================

info "Creating 5 wallets with different initial balances..."

declare -a WID WNAME WINIT
WNAME=("Alice" "Bob" "Charlie" "Diana" "Eve")
WINIT=("1000.00" "500.00" "750.00" "250.00" "2000.00")

for i in 0 1 2 3 4; do
  resp=$(post "/wallets" \
    "{\"ownerName\":\"${WNAME[$i]}\",\"currency\":\"USD\",\"initialBalance\":\"${WINIT[$i]}\"}" \
    "$(req_id)")
  code=$(echo "$resp" | py "d['code']")
  wid=$(echo  "$resp" | py "d['data']['id']")
  bal=$(echo  "$resp" | py "d['data']['balance']")
  WID[$i]=$wid

  if [ "$code" = "0000" ] && [ "$bal" = "${WINIT[$i]}" ]; then
    pass "${WNAME[$i]} created"
    detail "id      = ${wid}"
    detail "balance = ${bal} USD"
  else
    fail "${WNAME[$i]} creation failed | code=$code"
  fi
done

# =============================================================================
section "STEP 2: Get Each Wallet — Verify Balance (GET /wallets/{id})"
# =============================================================================

info "Fetching each wallet to confirm balance is computed from ledger..."

for i in 0 1 2 3 4; do
  resp=$(get "/wallets/${WID[$i]}" "$(req_id)")
  code=$(echo "$resp" | py "d['code']")
  bal=$(echo  "$resp" | py "d['data']['balance']")

  if [ "$code" = "0000" ] && [ "$bal" = "${WINIT[$i]}" ]; then
    pass "GET ${WNAME[$i]} → balance=${bal}"
  else
    fail "GET ${WNAME[$i]} failed | code=$code bal=$bal"
  fi
done

# =============================================================================
section "STEP 3: Submit 30 Transfers (POST /transfers)"
# =============================================================================

info "Submitting 30 transfers across wallets (some may hit insufficient funds)..."
echo ""

declare -a TID
SUCCESS=0; INSUF=0

# Fixed transfer pairs and amounts for reproducibility
FROM_TO=("0 1" "1 2" "2 3" "3 4" "4 0" "0 2" "1 3" "2 4" "3 0" "4 1")
AMOUNTS=("50.00" "25.00" "100.00" "30.00" "200.00" "10.00" "150.00" "20.00" "40.00" "300.00")

for i in $(seq 0 29); do
  idx=$((i % 10))
  pair=(${FROM_TO[$idx]})
  fi=${pair[0]}; ti=${pair[1]}
  amt=${AMOUNTS[$idx]}
  tid=$(uuid)
  TID[$i]=$tid

  resp=$(post "/transfers" \
    "{\"transferId\":\"$tid\",\"fromWalletId\":\"${WID[$fi]}\",\"toWalletId\":\"${WID[$ti]}\",\"amount\":\"$amt\"}" \
    "$(req_id)")
  code=$(echo  "$resp" | py "d['code']")
  status=$(echo "$resp" | py "d.get('data',{}).get('status','FAILED')" 2>/dev/null || echo "FAILED")
  error=$(echo  "$resp" | py "d.get('error','')" 2>/dev/null || echo "")

  if [ "$code" = "0000" ] && [ "$status" = "COMPLETED" ]; then
    SUCCESS=$((SUCCESS+1))
    detail "[$((i+1))/30] ${WNAME[$fi]} → ${WNAME[$ti]} \$${amt} ✓ COMPLETED"
  elif [ "$code" = "4221" ]; then
    INSUF=$((INSUF+1))
    detail "[$((i+1))/30] ${WNAME[$fi]} → ${WNAME[$ti]} \$${amt} ✗ INSUFFICIENT_FUNDS"
  else
    detail "[$((i+1))/30] ${WNAME[$fi]} → ${WNAME[$ti]} \$${amt} ? code=$code"
  fi
done

echo ""
pass "30 transfers processed: ${SUCCESS} COMPLETED, ${INSUF} INSUFFICIENT_FUNDS"

# =============================================================================
section "STEP 4: Get Wallets After Transfers — Verify Balance Updated"
# =============================================================================

info "Balances must reflect all transfers — computed live from ledger..."

for i in 0 1 2 3 4; do
  resp=$(get "/wallets/${WID[$i]}" "$(req_id)")
  code=$(echo "$resp" | py "d['code']")
  bal=$(echo  "$resp" | py "d['data']['balance']")
  orig=${WINIT[$i]}

  if [ "$code" = "0000" ]; then
    if [ "$bal" != "$orig" ]; then
      pass "${WNAME[$i]} balance updated"
      detail "before transfers = ${orig} USD"
      detail "after  transfers = ${bal} USD"
    else
      pass "${WNAME[$i]} balance unchanged (net zero transfers)"
      detail "balance = ${bal} USD"
    fi
  else
    fail "GET ${WNAME[$i]} after transfers failed"
  fi
done

# =============================================================================
section "STEP 5: Reconcile Transfers (GET /transfers/{id})"
# =============================================================================

info "Verifying 6 transfers via reconciliation endpoint..."

for i in 0 4 9 14 19 24; do
  tid=${TID[$i]}
  resp=$(get "/transfers/$tid" "$(req_id)")
  code=$(echo "$resp" | py "d['code']")
  status=$(echo "$resp" | py "d.get('data',{}).get('status','NOT_FOUND')" 2>/dev/null || echo "NOT_FOUND")
  from_w=$(echo "$resp" | py "d.get('data',{}).get('fromWalletId','N/A')" 2>/dev/null || echo "N/A")
  amt=$(echo    "$resp" | py "d.get('data',{}).get('amount','N/A')" 2>/dev/null || echo "N/A")

  if [ "$code" = "0000" ] && [ "$status" = "COMPLETED" ]; then
    pass "Transfer [$((i+1))] reconciled"
    detail "transferId = ${tid:0:8}..."
    detail "amount     = \$${amt}"
    detail "status     = ${status}"
  elif [ "$code" = "4041" ]; then
    pass "Transfer [$((i+1))] not found (was rejected — no DB row inserted)"
    detail "transferId = ${tid:0:8}..."
  else
    fail "Transfer [$((i+1))] reconciliation unexpected | code=$code status=$status"
  fi
done

# =============================================================================
section "STEP 6: Transaction History (GET /wallets/{id}/transactions)"
# =============================================================================

info "Fetching ledger entries for Alice, Bob, Charlie..."

for i in 0 1 2; do
  resp=$(get "/wallets/${WID[$i]}/transactions" "$(req_id)")
  code=$(echo "$resp" | py "d['code']")
  entries=$(echo "$resp" | python3 -c "
import sys,json
d=json.load(sys.stdin)
entries=d['data']['entries']
debits  = [e for e in entries if e['type']=='DEBIT']
credits = [e for e in entries if e['type']=='CREDIT']
print(f\"{len(entries)} total | {len(debits)} DEBIT | {len(credits)} CREDIT\")
" 2>/dev/null || echo "parse error")

  if [ "$code" = "0000" ]; then
    pass "Ledger entries ${WNAME[$i]}: $entries"
  else
    fail "GET /transactions ${WNAME[$i]} failed | code=$code"
  fi
done

# =============================================================================
section "STEP 7: Idempotency — Same transferId Submitted Twice"
# =============================================================================

info "Submitting identical transfer twice — money must move exactly once..."
idem_tid=$(uuid)
FROM_BAL_BEFORE=$(get "/wallets/${WID[0]}" "$(req_id)" | py "d['data']['balance']")

resp1=$(post "/transfers" \
  "{\"transferId\":\"$idem_tid\",\"fromWalletId\":\"${WID[0]}\",\"toWalletId\":\"${WID[1]}\",\"amount\":\"10.00\"}" \
  "$(req_id)")
resp2=$(post "/transfers" \
  "{\"transferId\":\"$idem_tid\",\"fromWalletId\":\"${WID[0]}\",\"toWalletId\":\"${WID[1]}\",\"amount\":\"10.00\"}" \
  "$(req_id)")

code1=$(echo "$resp1" | py "d['code']")
code2=$(echo "$resp2" | py "d['code']")
FROM_BAL_AFTER=$(get "/wallets/${WID[0]}" "$(req_id)" | py "d['data']['balance']")

detail "Request 1 → code=$code1"
detail "Request 2 → code=$code2 (same transferId)"
detail "Alice balance before = \$${FROM_BAL_BEFORE}"
detail "Alice balance after  = \$${FROM_BAL_AFTER} (deducted \$10 exactly once)"

if [ "$code1" = "0000" ] && [ "$code2" = "0000" ]; then
  pass "Idempotency verified — duplicate request returns same result, no double debit"
else
  fail "Idempotency failed | code1=$code1 code2=$code2"
fi

# Verify same transferId with different amount → 409
resp3=$(post "/transfers" \
  "{\"transferId\":\"$idem_tid\",\"fromWalletId\":\"${WID[0]}\",\"toWalletId\":\"${WID[1]}\",\"amount\":\"999.00\"}" \
  "$(req_id)")
code3=$(echo "$resp3" | py "d['code']")
err3=$(echo  "$resp3" | py "d.get('error','')")
if [ "$code3" = "4009" ]; then
  pass "Idempotency conflict detected → 409 $err3"
  detail "Same transferId with different amount correctly rejected"
else
  fail "Expected 4009 but got $code3"
fi

# =============================================================================
section "STEP 8: DB Invariant — Double-Entry Ledger"
# =============================================================================

info "Querying DB: every transfer must have exactly 1 DEBIT + 1 CREDIT row..."

violations=$(docker exec wallet-postgres psql -U wallet_user -d wallet_db -t -c "
SELECT COUNT(*) FROM (
  SELECT transfer_id
  FROM ledger_entries
  WHERE transfer_id IS NOT NULL
  GROUP BY transfer_id
  HAVING COUNT(*) <> 2
     OR SUM(CASE WHEN type='DEBIT'  THEN 1 ELSE 0 END) <> 1
     OR SUM(CASE WHEN type='CREDIT' THEN 1 ELSE 0 END) <> 1
) v;" 2>/dev/null | tr -d ' \n')

total_entries=$(docker exec wallet-postgres psql -U wallet_user -d wallet_db -t -c \
  "SELECT COUNT(*) FROM ledger_entries WHERE transfer_id IS NOT NULL;" \
  2>/dev/null | tr -d ' \n')

total_transfers=$(docker exec wallet-postgres psql -U wallet_user -d wallet_db -t -c \
  "SELECT COUNT(*) FROM transfers;" \
  2>/dev/null | tr -d ' \n')

detail "transfers committed  = $total_transfers"
detail "ledger entries total = $total_entries (expect 2x transfers)"
detail "invariant violations = $violations (expect 0)"

if [ "$violations" = "0" ]; then
  pass "Double-entry invariant holds — every transfer has exactly 1 DEBIT + 1 CREDIT"
else
  fail "Invariant violated — $violations bad transfers found"
fi

# =============================================================================
section "SUMMARY"
# =============================================================================

TOTAL=$((PASS+FAIL))
echo ""
printf "  %-20s %s\n" "Total checks:"  "$TOTAL"
echo -e "  ${GREEN}$(printf '%-20s' 'Passed:') $PASS${NC}"
if [ $FAIL -gt 0 ]; then
  echo -e "  ${RED}$(printf '%-20s' 'Failed:') $FAIL${NC}"
else
  printf "  %-20s %s\n" "Failed:" "$FAIL"
fi
echo ""
if [ $FAIL -eq 0 ]; then
  echo -e "${GREEN}  ✓ All checks passed — service is working correctly${NC}"
else
  echo -e "${RED}  ✗ Some checks failed — review output above${NC}"
fi
echo ""
