# UAT Confirm — Wallet & Transfer Service

## Prerequisites

- Docker + Docker Compose installed
- App running: `docker-compose up --build`
- App ready when logs show: `Started WalletApplication`

---

## Run Automated Tests

```bash
# Full suite
./gradlew test --no-daemon

# Wallet namespace only
./gradlew test --tests "com.wallet.wallet.*" --no-daemon

# Transfer namespace only
./gradlew test --tests "com.wallet.transfer.*" --no-daemon

# Specific class
./gradlew test --tests "com.wallet.transfer.IdempotencyTest" --no-daemon
./gradlew test --tests "com.wallet.transfer.ConcurrencyTest" --no-daemon
./gradlew test --tests "com.wallet.transfer.ConcurrencyHttpTest" --no-daemon
./gradlew test --tests "com.wallet.transfer.TransferApiTest" --no-daemon
./gradlew test --tests "com.wallet.transfer.GetTransferApiTest" --no-daemon
./gradlew test --tests "com.wallet.wallet.CreateWalletApiTest" --no-daemon
./gradlew test --tests "com.wallet.wallet.GetWalletApiTest" --no-daemon

# Log behavior — verify each exception handler emits correct log level
./gradlew test --tests "com.wallet.shared.GlobalExceptionHandlerLoggingTest" --no-daemon

# Specific method
./gradlew test --tests "com.wallet.transfer.IdempotencyTest.sameTransferIdSubmittedTwice_moneyMovesOnce" --no-daemon
```

`GlobalExceptionHandlerLoggingTest` uses `@WebMvcTest` + `ListAppender` — no DB or Testcontainers needed, runs fast. Verifies:
- `WalletNotFoundException` → WARN `wallet_not_found`
- `InsufficientFundsException` → INFO `insufficient_funds`
- `IdempotencyConflictException` → WARN `idempotency_conflict`
- `FraudRejectedException` → WARN `fraud_rejected`
- `RuntimeException` (unexpected) → ERROR `unexpected_error`
- `MissingRequestHeaderException` → WARN `missing_header`
- `MethodArgumentNotValidException` → WARN `validation_error`

---

## Reset Data (run before each clean UAT session)

```bash
# Truncate all data, keep schema and Flyway history
docker exec -it wallet-postgres psql -U wallet_user -d wallet_db -c "
TRUNCATE outbox_events, ledger_entries, transfers, wallets RESTART IDENTITY CASCADE;"

# Full reset — drop and recreate schema (Flyway will re-migrate on next app start)
docker exec -it wallet-postgres psql -U wallet_user -d wallet_db -c "
DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
docker-compose restart app
```

---

## DB Inspection Queries

```bash
# All wallets with current balance
docker exec -it wallet-postgres psql -U wallet_user -d wallet_db -c "
SELECT w.id, w.owner_name, w.currency,
       COALESCE(SUM(CASE WHEN l.type='CREDIT' THEN l.amount ELSE -l.amount END), 0) AS balance
FROM wallets w
LEFT JOIN ledger_entries l ON l.wallet_id = w.id
GROUP BY w.id, w.owner_name, w.currency
ORDER BY w.created_at DESC;"

# Ledger entries for a specific wallet
docker exec -it wallet-postgres psql -U wallet_user -d wallet_db -c "
SELECT id, type, amount, transfer_id, created_at
FROM ledger_entries WHERE wallet_id = '<WALLET_ID>'
ORDER BY created_at DESC;"

# All transfers
docker exec -it wallet-postgres psql -U wallet_user -d wallet_db -c "
SELECT id, from_wallet_id, to_wallet_id, amount, status, created_at
FROM transfers ORDER BY created_at DESC;"

# Outbox events
docker exec -it wallet-postgres psql -U wallet_user -d wallet_db -c "
SELECT id, transfer_id, event_type, status, attempts, created_at, last_attempt_at
FROM outbox_events ORDER BY created_at DESC;"

# Double-entry invariant check — every transfer must have exactly 1 DEBIT + 1 CREDIT
# Excludes seed entries (transfer_id IS NULL)
docker exec -it wallet-postgres psql -U wallet_user -d wallet_db -c "
SELECT transfer_id,
       COUNT(*) as entries,
       SUM(CASE WHEN type='DEBIT'  THEN 1 ELSE 0 END) as debits,
       SUM(CASE WHEN type='CREDIT' THEN 1 ELSE 0 END) as credits
FROM ledger_entries
WHERE transfer_id IS NOT NULL
GROUP BY transfer_id
HAVING COUNT(*) <> 2
    OR SUM(CASE WHEN type='DEBIT'  THEN 1 ELSE 0 END) <> 1
    OR SUM(CASE WHEN type='CREDIT' THEN 1 ELSE 0 END) <> 1;"
# Empty result = invariant holds
```

---

## TC-01 — Create wallet with initial balance

```bash
curl -s -X POST http://localhost:8080/api/v1/wallets \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: tc-01" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" \
  -d '{"ownerName":"Alice","currency":"USD","initialBalance":"1000.00"}' | python3 -m json.tool
```

Expected:
- HTTP 201, `code = "0000"`
- `data.balance = "1000.00"` (string, not number)
- `data.id` is UUID — save as `ALICE_ID`

DB check:
```bash
docker exec -it wallet-postgres psql -U wallet_user -d wallet_db -c "
SELECT id, type, amount, transfer_id FROM ledger_entries WHERE wallet_id = '<ALICE_ID>';"
# 1 row: type=CREDIT, amount=1000.00, transfer_id=NULL (seed entry)
```

---

## TC-02 — Create wallet with zero balance

```bash
curl -s -X POST http://localhost:8080/api/v1/wallets \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: tc-02" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" \
  -d '{"ownerName":"Bob","currency":"USD","initialBalance":"0.00"}' | python3 -m json.tool
```

Expected: HTTP 201, `data.balance = "0.00"` — save as `BOB_ID`

DB check:
```bash
docker exec -it wallet-postgres psql -U wallet_user -d wallet_db -c "
SELECT COUNT(*) FROM ledger_entries WHERE wallet_id = '<BOB_ID>';"
# 0 rows — no seed entry for zero balance
```

---

## TC-03 — Validation: blank ownerName

```bash
curl -s -X POST http://localhost:8080/api/v1/wallets \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: tc-03" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" \
  -d '{"ownerName":"","currency":"USD","initialBalance":"0.00"}' | python3 -m json.tool
```

Expected: HTTP 400, `code = "4001"`, `error = "VALIDATION_ERROR"`

---

## TC-04 — GET wallet exists

```bash
curl -s http://localhost:8080/api/v1/wallets/<ALICE_ID> \
  -H "X-Request-Id: tc-04" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" | python3 -m json.tool
```

Expected: HTTP 200, `code = "0000"`, `data.balance = "1000.00"`

---

## TC-05 — GET wallet not found

```bash
curl -s http://localhost:8080/api/v1/wallets/00000000-0000-0000-0000-000000000000 \
  -H "X-Request-Id: tc-05" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" | python3 -m json.tool
```

Expected: HTTP 404, `code = "4004"`, `error = "WALLET_NOT_FOUND"`

---

## TC-06 — Transfer success

```bash
TRANSFER_ID=$(python3 -c "import uuid; print(uuid.uuid4())")
echo "transferId: $TRANSFER_ID"

curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: tc-06" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" \
  -d "{\"transferId\":\"$TRANSFER_ID\",\"fromWalletId\":\"<ALICE_ID>\",\"toWalletId\":\"<BOB_ID>\",\"amount\":\"500.00\"}" | python3 -m json.tool
```

Expected: HTTP 200, `code = "0000"`, `data.status = "COMPLETED"`, `data.amount = "500.00"`

DB check:
```bash
docker exec -it wallet-postgres psql -U wallet_user -d wallet_db -c "
SELECT type, amount, wallet_id FROM ledger_entries WHERE transfer_id = '$TRANSFER_ID';"
# 2 rows: DEBIT 500 from Alice, CREDIT 500 to Bob

docker exec -it wallet-postgres psql -U wallet_user -d wallet_db -c "
SELECT status, attempts FROM outbox_events WHERE transfer_id = '$TRANSFER_ID';"
# 1 row: status=PENDING or PUBLISHED
```

---

## TC-07 — GET transfer by ID

```bash
curl -s http://localhost:8080/api/v1/transfers/<TRANSFER_ID> \
  -H "X-Request-Id: tc-07" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" | python3 -m json.tool
```

Expected: HTTP 200, `data.status = "COMPLETED"`, correct wallets and amount

---

## TC-08 — Ledger entries after transfer

```bash
# Alice should have CREDIT (seed) + DEBIT (transfer)
curl -s "http://localhost:8080/api/v1/wallets/<ALICE_ID>/transactions" \
  -H "X-Request-Id: tc-08a" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" | python3 -m json.tool
# entries: DEBIT 500 visible, balance = 500

# Bob should have CREDIT (transfer)
curl -s "http://localhost:8080/api/v1/wallets/<BOB_ID>/transactions" \
  -H "X-Request-Id: tc-08b" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" | python3 -m json.tool
# entries: CREDIT 500 visible, balance = 500
```

---

## TC-09 — Idempotency: same transferId submitted twice

```bash
IDEM_ID=$(python3 -c "import uuid; print(uuid.uuid4())")

# Request 1
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: tc-09a" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" \
  -d "{\"transferId\":\"$IDEM_ID\",\"fromWalletId\":\"<ALICE_ID>\",\"toWalletId\":\"<BOB_ID>\",\"amount\":\"100.00\"}" | python3 -m json.tool

# Request 2 — identical body
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: tc-09b" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" \
  -d "{\"transferId\":\"$IDEM_ID\",\"fromWalletId\":\"<ALICE_ID>\",\"toWalletId\":\"<BOB_ID>\",\"amount\":\"100.00\"}" | python3 -m json.tool
```

Expected: both return HTTP 200 `COMPLETED`. Money moves exactly once.

DB check:
```bash
docker exec -it wallet-postgres psql -U wallet_user -d wallet_db -c "
SELECT COUNT(*) FROM ledger_entries WHERE transfer_id = '$IDEM_ID';"
# 2 (not 4)
```

---

## TC-10 — Idempotency conflict: same transferId, different amount

```bash
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: tc-10" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" \
  -d "{\"transferId\":\"$IDEM_ID\",\"fromWalletId\":\"<ALICE_ID>\",\"toWalletId\":\"<BOB_ID>\",\"amount\":\"999.00\"}" | python3 -m json.tool
```

Expected: HTTP 409, `code = "4009"`, `error = "IDEMPOTENCY_CONFLICT"`

---

## TC-11 — Insufficient funds

```bash
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: tc-11" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" \
  -d "{\"transferId\":\"$(python3 -c 'import uuid; print(uuid.uuid4())')\",\"fromWalletId\":\"<ALICE_ID>\",\"toWalletId\":\"<BOB_ID>\",\"amount\":\"99999.00\"}" | python3 -m json.tool
```

Expected: HTTP 422, `code = "4221"`, `error = "INSUFFICIENT_FUNDS"`

DB check: ledger entry count unchanged.

---

## TC-12 — Wallet not found

```bash
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: tc-12" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" \
  -d "{\"transferId\":\"$(python3 -c 'import uuid; print(uuid.uuid4())')\",\"fromWalletId\":\"00000000-0000-0000-0000-000000000000\",\"toWalletId\":\"<BOB_ID>\",\"amount\":\"100.00\"}" | python3 -m json.tool
```

Expected: HTTP 404, `code = "4004"`, `error = "WALLET_NOT_FOUND"`

---

## TC-13 — Amount = 0 (validation)

```bash
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: tc-13" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" \
  -d "{\"transferId\":\"$(python3 -c 'import uuid; print(uuid.uuid4())')\",\"fromWalletId\":\"<ALICE_ID>\",\"toWalletId\":\"<BOB_ID>\",\"amount\":\"0.00\"}" | python3 -m json.tool
```

Expected: HTTP 400, `code = "4001"`, `error = "VALIDATION_ERROR"`

---

## TC-14 — Self-transfer (fromWalletId = toWalletId)

```bash
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: tc-14" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" \
  -d "{\"transferId\":\"$(python3 -c 'import uuid; print(uuid.uuid4())')\",\"fromWalletId\":\"<ALICE_ID>\",\"toWalletId\":\"<ALICE_ID>\",\"amount\":\"100.00\"}" | python3 -m json.tool
```

Expected: HTTP 400, `code = "4001"`, `error = "VALIDATION_ERROR"`

---

## TC-15 — Missing required header

```bash
curl -s -X POST http://localhost:8080/api/v1/wallets \
  -H "Content-Type: application/json" \
  -H "X-Requestor-Id: uat" \
  -H "X-Request-Time: 2026-07-02T10:00:00Z" \
  -d '{"ownerName":"Test","currency":"USD","initialBalance":"0.00"}' | python3 -m json.tool
```

Expected: HTTP 400, `code = "4001"`, `error = "VALIDATION_ERROR"`, description contains `X-Request-Id`

---

## TC-16 — Concurrency: 10 simultaneous requests, no overdraw

```bash
FROM_ID=$(curl -s -X POST http://localhost:8080/api/v1/wallets \
  -H "Content-Type: application/json" -H "X-Request-Id: setup-1" \
  -H "X-Requestor-Id: uat" -H "X-Request-Time: 2026-07-02T10:00:00Z" \
  -d '{"ownerName":"Concurrent-From","currency":"USD","initialBalance":"100.00"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

TO_ID=$(curl -s -X POST http://localhost:8080/api/v1/wallets \
  -H "Content-Type: application/json" -H "X-Request-Id: setup-2" \
  -H "X-Requestor-Id: uat" -H "X-Request-Time: 2026-07-02T10:00:00Z" \
  -d '{"ownerName":"Concurrent-To","currency":"USD","initialBalance":"0.00"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

echo "FROM: $FROM_ID  TO: $TO_ID"

# Fire 10 concurrent requests, each tries to transfer 80 (only 1 can succeed)
for i in $(seq 1 10); do
  TID=$(python3 -c "import uuid; print(uuid.uuid4())")
  curl -s -X POST http://localhost:8080/api/v1/transfers \
    -H "Content-Type: application/json" -H "X-Request-Id: conc-$i" \
    -H "X-Requestor-Id: uat" -H "X-Request-Time: 2026-07-02T10:00:00Z" \
    -d "{\"transferId\":\"$TID\",\"fromWalletId\":\"$FROM_ID\",\"toWalletId\":\"$TO_ID\",\"amount\":\"80.00\"}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('code'), d.get('data',{}).get('status') if d.get('data') else d.get('error'))" &
done
wait
```

Expected: exactly 1 line `0000 COMPLETED`, 9 lines `4221 INSUFFICIENT_FUNDS`

DB check:
```bash
docker exec -it wallet-postgres psql -U wallet_user -d wallet_db -c "
SELECT COALESCE(SUM(CASE WHEN type='CREDIT' THEN amount ELSE -amount END), 0) AS balance
FROM ledger_entries WHERE wallet_id = '$FROM_ID';"
# 20.00 — never goes negative
```

---

## TC-17 — Outbox event created after transfer

```bash
docker exec -it wallet-postgres psql -U wallet_user -d wallet_db -c "
SELECT transfer_id, event_type, status, attempts
FROM outbox_events ORDER BY created_at DESC LIMIT 5;"
# After each successful transfer: 1 row TransferCompleted
```

---

## TC-18 — Outbox poller marks PUBLISHED (wait 10 seconds)

```bash
sleep 10
docker exec -it wallet-postgres psql -U wallet_user -d wallet_db -c "
SELECT status, COUNT(*) FROM outbox_events GROUP BY status;"
# PUBLISHED: N rows, PENDING: 0
```

---

## Result Checklist

| TC | Description | Expected | Result |
|----|-------------|----------|--------|
| TC-01 | Create wallet with balance | 201, balance="1000.00" | ⬜ |
| TC-02 | Create wallet zero balance | 201, balance="0.00", no ledger entry | ⬜ |
| TC-03 | Blank ownerName | 400 VALIDATION_ERROR | ⬜ |
| TC-04 | GET wallet exists | 200, correct balance | ⬜ |
| TC-05 | GET wallet not found | 404 WALLET_NOT_FOUND | ⬜ |
| TC-06 | Transfer success | 200 COMPLETED, 2 ledger entries | ⬜ |
| TC-07 | GET transfer by ID | 200 COMPLETED | ⬜ |
| TC-08 | Ledger entries after transfer | DEBIT Alice + CREDIT Bob | ⬜ |
| TC-09 | Idempotency: retry same payload | Money moves once, 2 ledger entries | ⬜ |
| TC-10 | Idempotency conflict | 409 IDEMPOTENCY_CONFLICT | ⬜ |
| TC-11 | Insufficient funds | 422 INSUFFICIENT_FUNDS | ⬜ |
| TC-12 | Wallet not found | 404 WALLET_NOT_FOUND | ⬜ |
| TC-13 | Amount = 0 | 400 VALIDATION_ERROR | ⬜ |
| TC-14 | Self-transfer | 400 VALIDATION_ERROR | ⬜ |
| TC-15 | Missing header | 400 VALIDATION_ERROR | ⬜ |
| TC-16 | 10 concurrent requests | 1 success, 9 INSUFFICIENT_FUNDS, balance=20 | ⬜ |
| TC-17 | Outbox event created | 1 row TransferCompleted | ⬜ |
| TC-18 | Outbox poller publishes | PUBLISHED after 10s | ⬜ |
