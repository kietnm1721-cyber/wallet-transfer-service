# Code Walkthrough — 3 Key Workflows

This document is a guide for tech reviewers and interviewers.
It maps the 3 most critical workflows to exact file paths and line numbers.

---

## Architecture Overview

```
HTTP Request
    │
    ▼
adapter/in/web/          ← Controllers (HTTP layer only, no business logic)
    │
    ▼
port/in/                 ← Use case interfaces (domain defines the contract)
    │
    ▼
domain/                  ← Pure Java business logic (no Spring, no JPA)
    │
    ▼
port/out/                ← Repository/service interfaces (domain defines, infra implements)
    │
    ▼
adapter/out/persistence/ ← JPA repositories, entity mapping
adapter/out/fraud/       ← External fraud check client
```

Domain core has zero framework annotations — testable in isolation, infrastructure is swappable.

---

## Workflow 1: Transfer Critical Path

**Entry point:** `POST /api/v1/transfers`

**File:** `src/main/java/com/wallet/transfer/adapter/in/web/TransferController.java`
- Line 38: `@PostMapping` — receives request, validates input (`@Valid`)
- Line 43: delegates to `transferUseCase.transfer(...)` — controller knows nothing about business logic

**File:** `src/main/java/com/wallet/transfer/domain/TransferService.java`

```
Line 52  → Idempotency check (Layer 1 — application)
           findById(transferId): if found + same payload → return existing (no reprocessing)
                                 if found + different payload → throw 409 IDEMPOTENCY_CONFLICT

Line 67  → Fraud check
           fraudCheckPort.isFraudulent() — protected by @CircuitBreaker + @Retry in adapter layer

Line 71  → Lock wallets in ascending UUID order (deadlock prevention)
           firstId  = min(fromWalletId, toWalletId)
           secondId = max(fromWalletId, toWalletId)
           → Thread 1 and Thread 2 always lock in same order → no circular wait

Line 76  → Balance check AFTER acquiring locks
           computeBalance() reads ledger_entries while holding SELECT FOR UPDATE
           → guaranteed no other transaction can change balance between read and write

Line 81  → Save transfer record
           catch DataIntegrityViolationException → Layer 2 idempotency (concurrent race)

Line 88  → Write double-entry ledger
           DEBIT  (wallet_id=fromWallet, amount=X, transfer_id=T)
           CREDIT (wallet_id=toWallet,   amount=X, transfer_id=T)
           → 2 rows, both positive amounts, differentiated by type only

Line 92  → Write outbox event (same transaction)
           → If app crashes after COMMIT, event is still in DB for poller to pick up

Line 94  → COMMIT — all of lines 71-92 are atomic
           Any failure → full rollback, no rows inserted, balances unchanged
```

**DB writes (single transaction):**
```sql
INSERT INTO transfers      (id, from_wallet_id, to_wallet_id, amount, status)
INSERT INTO ledger_entries (wallet_id=from, type=DEBIT,  amount, transfer_id)
INSERT INTO ledger_entries (wallet_id=to,   type=CREDIT, amount, transfer_id)
INSERT INTO outbox_events  (transfer_id, event_type=TransferCompleted, status=PENDING)
```

---

## Workflow 2: Concurrency Safety

**The problem:** Two concurrent transfers from Wallet A (balance $100), each requesting $80.
Without locks: both read $100, both approve → balance goes to -$60. Overdraw.

**Solution: SELECT FOR UPDATE**

**File:** `src/main/java/com/wallet/wallet/adapter/out/persistence/WalletJpaRepository.java`
- Line 15: `@Lock(LockModeType.PESSIMISTIC_WRITE)` on `lockById()`
- This translates to `SELECT * FROM wallets WHERE id = ? FOR UPDATE` in SQL

**File:** `src/main/java/com/wallet/transfer/domain/TransferService.java`
- Line 71-74: lock ordering — always lock smaller UUID first

```java
UUID firstId  = fromWalletId.compareTo(toWalletId) < 0 ? fromWalletId : toWalletId;
UUID secondId = fromWalletId.compareTo(toWalletId) < 0 ? toWalletId   : fromWalletId;
walletRepository.lockById(firstId);   // blocks here if another thread holds the lock
walletRepository.lockById(secondId);
```

**Why lock ordering matters:**
```
Without ordering:
  Thread 1: lock A → waiting for B
  Thread 2: lock B → waiting for A
  → Deadlock (both wait forever)

With ascending UUID ordering:
  Thread 1: lock A (smaller) → lock B
  Thread 2: lock A (smaller) → BLOCKS → waits for Thread 1 to release
  → No deadlock, sequential access guaranteed
```

**File:** `src/main/java/com/wallet/wallet/adapter/out/persistence/WalletJpaRepository.java`
- Line 20-28: `computeBalance()` — native SQL, reads ledger_entries while lock is held

**Why balance check must happen AFTER lock:**
```
Without lock:
  Thread 1 reads balance = $100 ✓
  Thread 2 reads balance = $100 ✓   ← both read before either writes
  Thread 1 writes DEBIT $80 → balance now $20
  Thread 2 writes DEBIT $80 → balance now -$60 ← overdraw

With lock:
  Thread 1 acquires lock → reads $100 → writes DEBIT $80 → commits → releases lock
  Thread 2 acquires lock → reads $20  → $20 < $80 → 422 INSUFFICIENT_FUNDS
```

**Test:** `src/test/java/com/wallet/transfer/ConcurrencyTest.java`
- 10 threads, balance=$100, each tries $80 → exactly 1 succeeds, 9 fail with INSUFFICIENT_FUNDS

---

## Workflow 3: Idempotency — Two-Layer Defense

**The problem:** Network timeout or client retry sends same transfer twice. Money must move exactly once.

**Layer 1 — Application check (sequential retry)**

**File:** `src/main/java/com/wallet/transfer/domain/TransferService.java`
- Line 52-66:

```java
var existing = transferRepository.findById(transferId);
if (existing.isPresent()) {
    Transfer t = existing.get();
    if (t.fromWalletId().equals(fromWalletId)
            && t.toWalletId().equals(toWalletId)
            && t.amount().compareTo(amount) == 0) {
        return t;           // same payload → idempotent return, no reprocessing
    }
    throw new IdempotencyConflictException(transferId);  // different payload → 409
}
```

Handles: client retries after timeout, slow network, client restart.

**Layer 2 — DB UNIQUE constraint (concurrent race)**

**File:** `src/main/resources/db/migration/V2__create_transfers.sql`
- Line 8: `CONSTRAINT uq_transfer_idempotency UNIQUE (id, from_wallet_id, to_wallet_id, amount)`

**File:** `src/main/java/com/wallet/transfer/domain/TransferService.java`
- Line 84-89:

```java
try {
    transferRepository.save(transfer);
} catch (DataIntegrityViolationException e) {
    // Two concurrent requests with same transferId both passed Layer 1,
    // but only one INSERT wins — the other gets UNIQUE violation → 409
    throw new IdempotencyConflictException(transferId);
}
```

Handles: two requests arrive simultaneously, both pass Layer 1 before either commits.

**Why both layers are necessary:**
```
Layer 1 only:
  Request 1 → findById → not found → proceeds
  Request 2 → findById → not found → proceeds    ← race condition, both approved

Layer 2 only:
  Request 1 (retry after commit) → findById → not found → tries INSERT → UNIQUE violation → 409
  → Correct behavior for retry, but client gets 409 instead of 200 COMPLETED

Both layers:
  Sequential retry → Layer 1 catches it → 200 COMPLETED (correct)
  Concurrent race  → Layer 2 catches it → 409 IDEMPOTENCY_CONFLICT (correct)
```

**Crash recovery scenario:**
```
Request 1 → commits (transfer row in DB) → app crashes before returning response
Client retries with same transferId
Layer 1 → finds existing row → returns COMPLETED
Money moved: exactly once
```

**Why not Redis?**
Redis TTL expires → same transferId could be processed again after expiry.
DB UNIQUE constraint is permanent — no expiry risk.

**Test:** `src/test/java/com/wallet/transfer/IdempotencyTest.java`
- `sameTransferIdSubmittedTwice_moneyMovesOnce()` — verifies Layer 1
- `sameTransferIdDifferentAmount_throwsIdempotencyConflict()` — verifies conflict detection
