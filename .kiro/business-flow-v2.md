# Business Flow — Wallet & Transfer Service

## Core Objective

Safe money movement with four non-negotiable guarantees:

| Guarantee | Mechanism |
|-----------|-----------|
| Atomicity | `@Transactional` — all DB writes commit together or full rollback |
| Idempotency | `transferId` UNIQUE constraint — same request N times moves money once |
| Data integrity | Ledger is source of truth — balance computed, never stored |
| Concurrency safety | `SELECT FOR UPDATE` — no overdraw under simultaneous transfers |

---

## Domain Entities

### Wallet
- Owner name, currency (USD), created timestamp
- No `balance` column — balance computed at query time from `ledger_entries`
- Makes incorrect balance states structurally impossible

### Ledger Entry
- Immutable DEBIT or CREDIT record against a wallet
- Every transfer produces exactly 2 entries: one DEBIT (sender) + one CREDIT (receiver), same amount
- Append-only — never updated or deleted after insert

### Transfer
- Record of a completed fund movement between two wallets
- `status` field: `COMPLETED` | `FAILED`
- **In current implementation status is always `COMPLETED`** — a row is only inserted when the full transaction commits. Failed transfers throw an exception → full rollback → no row inserted. `FAILED` is reserved for future async flows.

### Outbox Event
- Written in the same transaction as the transfer
- Represents `TransferCompleted` to be published asynchronously to downstream systems
- Guarantees event is never lost even if app crashes after DB commit

---

## Flow 1: Create Wallet

```
POST /wallets
       │
       ▼
1. VALIDATE
   ownerName not blank, currency not blank
   → FAIL 400 VALIDATION_ERROR

       │
       ▼
2. GENERATE ID
   UUID generated server-side

       │
       ▼
3. PERSIST
   INSERT wallets (id, owner_name, currency, created_at)

       │
       ▼
4. SEED INITIAL BALANCE (if initialBalance > 0)
   INSERT ledger_entries (wallet_id, type=CREDIT, amount, transfer_id=NULL)
   transfer_id is NULL — initial deposit is not a wallet-to-wallet transfer

       │
       ▼
5. RETURN 201
   {id, ownerName, currency, balance, createdAt}
   balance = recomputed from ledger immediately after seed insert
```

| Failure | HTTP | code | error |
|---------|------|------|-------|
| Blank ownerName | 400 | 4001 | VALIDATION_ERROR |
| Missing required header | 400 | 4001 | VALIDATION_ERROR |

---

## Flow 2: Get Wallet Balance

```
GET /wallets/{id}
       │
       ▼
1. LOAD WALLET
   SELECT * FROM wallets WHERE id = ?
   → FAIL 404 WALLET_NOT_FOUND if missing

       │
       ▼
2. COMPUTE BALANCE
   SELECT COALESCE(SUM(CASE WHEN type='CREDIT' THEN amount ELSE -amount END), 0)
   FROM ledger_entries WHERE wallet_id = ?

       │
       ▼
3. RETURN 200
   {id, ownerName, currency, balance, createdAt}
```

| Failure | HTTP | code | error |
|---------|------|------|-------|
| Wallet not found | 404 | 4004 | WALLET_NOT_FOUND |

---

## Flow 3: Get Ledger Entries (Transaction History)

```
GET /wallets/{id}/transactions?size=N&cursor=...
       │
       ▼
1. QUERY LEDGER (keyset pagination)
   SELECT * FROM ledger_entries
   WHERE wallet_id = ?
     AND (from/to date filters if provided)
     AND (created_at, id) < cursor row   ← skip already-seen entries
   ORDER BY created_at DESC, id DESC
   LIMIT size

       │
       ▼
2. COMPUTE nextCursor
   If rows returned = size → nextCursor = id of last row
   If rows returned < size → nextCursor = null (last page)

       │
       ▼
3. RETURN 200
   {walletId, entries: [{id, type, amount, transferId, createdAt}], nextCursor}
```

> `transferId` is `null` for seed entries (initial deposit — no transfer record exists).
> Pagination uses `(created_at, id)` UUID comparison — consistent tiebreaker, no drift.

| Failure | HTTP | code | error |
|---------|------|------|-------|
| Wallet not found | 404 | 4004 | WALLET_NOT_FOUND |

---

## Flow 4: Transfer Funds (Critical Path)

```
POST /transfers
Body: { transferId, fromWalletId, toWalletId, amount }
```

```
Step 1: INPUT VALIDATION
        amount >= 0.01
        fromWalletId ≠ toWalletId
        → FAIL 400 VALIDATION_ERROR

Step 2: IDEMPOTENCY CHECK
        SELECT * FROM transfers WHERE id = transferId
        If found → return existing transfer immediately (no further processing)
        → HIT: 200 COMPLETED (money was already moved)

Step 3: WALLET EXISTENCE CHECK
        Load fromWallet and toWallet
        → FAIL 404 WALLET_NOT_FOUND

Step 4: FRAUD CHECK (external call, circuit breaker protected)
        Call fraud service: timeout=2s, retry=2x, circuit breaker (Resilience4j)
        If REJECTED → abort
        If circuit open → fail-closed (reject transfer)
        → FAIL 422 FRAUD_REJECTED

Step 5: ACQUIRE LOCKS  ← DB transaction begins here
        SELECT FOR UPDATE on both wallets
        Lock order: ascending UUID to prevent deadlock
           firstId  = min(fromWalletId, toWalletId)
           secondId = max(fromWalletId, toWalletId)
        Thread blocks here if another transfer holds lock on same wallet

Step 6: BALANCE CHECK (while holding locks)
        balance = SUM(CREDIT) - SUM(DEBIT) from ledger_entries WHERE wallet_id = fromWalletId
        balance < amount → FAIL 422 INSUFFICIENT_FUNDS

Step 7: WRITE TRANSFER RECORD
        INSERT transfers (id=transferId, from_wallet_id, to_wallet_id, amount, status=COMPLETED)
        DB UNIQUE constraint on (id, from_wallet_id, to_wallet_id, amount)
        Concurrent duplicate → DataIntegrityViolationException → 409 IDEMPOTENCY_CONFLICT

Step 8: WRITE LEDGER ENTRIES
        INSERT ledger_entries (wallet_id=fromWalletId, type=DEBIT,  amount, transfer_id=transferId)
        INSERT ledger_entries (wallet_id=toWalletId,   type=CREDIT, amount, transfer_id=transferId)

Step 9: WRITE OUTBOX EVENT
        INSERT outbox_events (transfer_id=transferId, event_type=TransferCompleted, status=PENDING)

Step 10: COMMIT
         Steps 5–9 commit atomically
         Any failure → full rollback, no row inserted, balances unchanged

Step 11: RETURN 200
         {transferId, status=COMPLETED, fromWalletId, toWalletId, amount, createdAt}
```

| Failure | HTTP | code | error |
|---------|------|------|-------|
| amount <= 0, self-transfer, missing field | 400 | 4001 | VALIDATION_ERROR |
| Wallet not found | 404 | 4004 | WALLET_NOT_FOUND |
| Same transferId, different payload | 409 | 4009 | IDEMPOTENCY_CONFLICT |
| Balance < amount | 422 | 4221 | INSUFFICIENT_FUNDS |
| Fraud check rejected | 422 | 4222 | FRAUD_REJECTED |

---

## Flow 5: Get Transfer (Reconciliation)

```
GET /transfers/{transferId}
       │
       ▼
1. LOAD TRANSFER
   SELECT * FROM transfers WHERE id = transferId
   → FAIL 404 TRANSFER_NOT_FOUND if missing

       │
       ▼
2. RETURN 200
   {transferId, status, fromWalletId, toWalletId, amount, createdAt}
```

Use after timeout or network failure to verify actual DB state before deciding to retry.

| Failure | HTTP | code | error |
|---------|------|------|-------|
| Transfer not found | 404 | 4041 | TRANSFER_NOT_FOUND |

---

## Concurrency: Why SELECT FOR UPDATE Matters

```
Wallet A balance: $100

Thread 1: POST /transfers amount=$80     Thread 2: POST /transfers amount=$60
              │                                          │
    SELECT FOR UPDATE wallet A               SELECT FOR UPDATE wallet A
         (acquires lock)                          (BLOCKS — waiting)
              │
    balance check: $100 >= $80 ✓
    INSERT DEBIT $80
    COMMIT → ledger balance now $20
    lock released
                                                         │
                                               (unblocks, reads fresh ledger)
                                               balance check: $20 >= $60 ✗
                                               → 422 INSUFFICIENT_FUNDS
```

Without locks: both threads read $100 simultaneously, both approve → wallet balance goes to -$40.
With locks: sequential access enforced at DB level → balance never goes negative.

---

## Idempotency: Two-Layer Defense

**Layer 1 — Application check (fast path):**
```
transferRepository.findById(transferId)
→ found: return existing transfer immediately, skip all processing
→ not found: proceed
```

**Layer 2 — DB constraint (race condition safety):**
```
UNIQUE (id, from_wallet_id, to_wallet_id, amount) on transfers table
→ concurrent duplicate INSERT → DataIntegrityViolationException → 409 IDEMPOTENCY_CONFLICT
```

**Crash recovery scenario:**
```
Request 1 → processes → commits → app crashes before sending response
Client retries with same transferId
Layer 1 finds existing row → returns COMPLETED
Money moved: exactly once
```

---

## Outbox: Reliable Event Publishing

```
Transfer commits
       │
       ▼
outbox_events row: {transfer_id, event_type=TransferCompleted, status=PENDING, attempts=0}
       │
       ▼
@Scheduled poller every 5s:
   SELECT * FROM outbox_events WHERE status='PENDING' AND attempts < 5
       │
       ├─ publish success → UPDATE status='PUBLISHED'
       │
       └─ publish failure → UPDATE attempts=attempts+1, last_attempt_at=now()
                                │
                                └─ attempts >= 5 → UPDATE status='DEAD'
                                                   ERROR log → manual intervention
```

Event is written in the same transaction as the transfer — guaranteed to exist if transfer committed, never exists if transfer rolled back.

---

## DB Invariants (enforced at DB level)

| Invariant | Mechanism |
|-----------|-----------|
| amount > 0 | CHECK constraint on `transfers.amount` and `ledger_entries.amount` |
| No self-transfer | Application validation (`@AssertTrue`) |
| Idempotency | UNIQUE constraint on `transfers(id, from_wallet_id, to_wallet_id, amount)` |
| Ledger references valid transfer | FK `ledger_entries.transfer_id → transfers.id` |
| Ledger references valid wallet | FK `ledger_entries.wallet_id → wallets.id` |
| Outbox references valid transfer | FK `outbox_events.transfer_id → transfers.id` |
| Entry type is DEBIT or CREDIT only | CHECK constraint on `ledger_entries.type` |

## Business Invariants (must always hold)

1. Every committed transfer has exactly 1 DEBIT entry + 1 CREDIT entry, same amount
2. No wallet balance ever goes below 0
3. Sum of all DEBIT amounts = Sum of all CREDIT amounts across all wallets
4. A `transferId` maps to exactly one transfer result (idempotency)
5. An outbox event exists for every committed transfer
