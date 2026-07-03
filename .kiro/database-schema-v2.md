# Database Schema — Wallet & Transfer Service

## Tables Overview

| Table | Migration | Purpose |
|-------|-----------|---------|
| `wallets` | V1 | Wallet profile — owner, currency. No balance column |
| `transfers` | V2 | Transfer record — idempotency key, status, audit |
| `ledger_entries` | V3 | Append-only DEBIT/CREDIT records — source of truth for balance |
| `outbox_events` | V4 | Outbox events to be published asynchronously |

**Flyway migrations:** V1 → V2 → V3 → V4 (4 files, no gaps)

---

## V1 — wallets

```sql
CREATE TABLE wallets (
    id          UUID            PRIMARY KEY,
    owner_name  VARCHAR(255)    NOT NULL,
    currency    VARCHAR(3)      NOT NULL DEFAULT 'USD',
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW()
);
```

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | Server-generated, primary key |
| owner_name | VARCHAR(255) | Not blank |
| currency | VARCHAR(3) | Fixed USD for this system — VARCHAR(3) for ISO 4217 extensibility |
| created_at | TIMESTAMP | Set at insert, never updated |

> No `balance` column — balance is always computed from `ledger_entries`. Makes incorrect balance states structurally impossible.

---

## V2 — transfers

```sql
CREATE TABLE transfers (
    id              UUID            PRIMARY KEY,
    from_wallet_id  UUID            NOT NULL REFERENCES wallets(id),
    to_wallet_id    UUID            NOT NULL REFERENCES wallets(id),
    amount          NUMERIC(19,4)   NOT NULL CHECK (amount > 0),
    status          VARCHAR(20)     NOT NULL DEFAULT 'COMPLETED',
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_transfer_idempotency
        UNIQUE (id, from_wallet_id, to_wallet_id, amount)
);

CREATE INDEX idx_transfers_from_wallet ON transfers(from_wallet_id, created_at DESC);
CREATE INDEX idx_transfers_to_wallet   ON transfers(to_wallet_id,   created_at DESC);
```

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | Client-generated = Correlation ID = idempotency key |
| from_wallet_id | UUID | FK → wallets(id) |
| to_wallet_id | UUID | FK → wallets(id) |
| amount | NUMERIC(19,4) | CHECK > 0 enforced at DB level |
| status | VARCHAR(20) | Always `COMPLETED` in current impl — see note below |
| created_at | TIMESTAMP | Set at insert |

**Idempotency constraint:** `UNIQUE (id, from_wallet_id, to_wallet_id, amount)`
- Same `transferId` + same payload → duplicate INSERT skipped at app level (findById check)
- Same `transferId` + different payload → UNIQUE violation → 409 IDEMPOTENCY_CONFLICT

**Status note:** A transfer row only exists when the full transaction commits. Failed transfers (insufficient funds, fraud rejected, wallet not found) cause full rollback — no row inserted. `FAILED` is reserved for future async flows.

**Indexes:** support audit queries by sender and receiver ordered by time.

---

## V3 — ledger_entries

```sql
CREATE TABLE ledger_entries (
    id          UUID            PRIMARY KEY,
    wallet_id   UUID            NOT NULL REFERENCES wallets(id),
    transfer_id UUID            REFERENCES transfers(id),
    type        VARCHAR(10)     NOT NULL CHECK (type IN ('DEBIT', 'CREDIT')),
    amount      NUMERIC(19,4)   NOT NULL CHECK (amount > 0),
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_wallet_created
    ON ledger_entries(wallet_id, created_at DESC, id);
```

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | Server-generated |
| wallet_id | UUID | FK → wallets(id) |
| transfer_id | UUID | FK → transfers(id), **nullable** — NULL for seed/initial deposit entries |
| type | VARCHAR(10) | `DEBIT` or `CREDIT` — CHECK constraint enforced at DB level |
| amount | NUMERIC(19,4) | CHECK > 0 |
| created_at | TIMESTAMP | Immutable — append-only table |

**Append-only:** never UPDATE or DELETE after insert.

**Every transfer produces exactly 2 rows:**
```
wallet_id=fromWallet, type=DEBIT,  amount=X, transfer_id=T
wallet_id=toWallet,   type=CREDIT, amount=X, transfer_id=T
```

**Seed entries** (initial deposit at wallet creation): `transfer_id = NULL` — money from outside the system, no source wallet.

**Balance computation:**

```sql
-- CREDIT entries add to balance (money in)
-- DEBIT entries subtract from balance (money out)
-- COALESCE returns 0 for wallets with no entries (zero-balance, no seed)
SELECT COALESCE(SUM(CASE WHEN type = 'CREDIT' THEN amount ELSE -amount END), 0)
FROM ledger_entries WHERE wallet_id = :walletId;
```

Java: `WalletJpaRepository.computeBalance()` — called inside `toDomain()` mapping on every `findById()` and `lockById()` call.

**Index on `(wallet_id, created_at DESC, id)`:**
- Covers balance computation (`WHERE wallet_id = ?`)
- Covers paginated history (`ORDER BY created_at DESC, id DESC`)
- `id` as tiebreaker ensures stable cursor pagination when entries share same timestamp

---

## V4 — outbox_events

```sql
CREATE TABLE outbox_events (
    id              UUID        PRIMARY KEY,
    transfer_id     UUID        NOT NULL REFERENCES transfers(id),
    event_type      VARCHAR(50) NOT NULL,
    payload         JSONB       NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts        INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    last_attempt_at TIMESTAMP
);

CREATE INDEX idx_outbox_pending
    ON outbox_events(status, attempts)
    WHERE status = 'PENDING';
```

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | Server-generated |
| transfer_id | UUID | FK → transfers(id) |
| event_type | VARCHAR(50) | `TransferCompleted` |
| payload | JSONB | `{transferId, fromWalletId, toWalletId, amount}` |
| status | VARCHAR(20) | `PENDING` → `PUBLISHED` \| `DEAD` |
| attempts | INT | Incremented on each failed publish attempt |
| last_attempt_at | TIMESTAMP | Updated on each attempt |

**Lifecycle:**
```
INSERT status=PENDING, attempts=0   ← same transaction as transfer
    ↓
Poller picks up (attempts < 5)
    ↓ success → status=PUBLISHED
    ↓ fail    → attempts++, last_attempt_at=now()
    ↓ attempts >= 5 → status=DEAD → ERROR log → manual intervention
```

**Partial index** on `(status, attempts) WHERE status='PENDING'` — only scans actionable rows, not published/dead events.

---

## Concurrency — Lock Order

```sql
-- Always lock in ascending UUID order to prevent deadlock
SELECT * FROM wallets WHERE id = :firstId  FOR UPDATE;  -- min(fromId, toId)
SELECT * FROM wallets WHERE id = :secondId FOR UPDATE;  -- max(fromId, toId)
```

Both wallets locked before any balance check or ledger write. Enforced in `TransferService.java`.

---

## DB-Level Invariants

| Invariant | Enforced By |
|-----------|-------------|
| amount > 0 | CHECK on `transfers.amount` and `ledger_entries.amount` |
| Entry type is DEBIT or CREDIT only | CHECK on `ledger_entries.type` |
| Idempotency | UNIQUE on `transfers(id, from_wallet_id, to_wallet_id, amount)` |
| Ledger → valid transfer | FK `ledger_entries.transfer_id → transfers.id` |
| Ledger → valid wallet | FK `ledger_entries.wallet_id → wallets.id` |
| Outbox → valid transfer | FK `outbox_events.transfer_id → transfers.id` |

---

## Flyway Migration Files

```
src/main/resources/db/migration/
    V1__create_wallets.sql
    V2__create_transfers.sql
    V3__create_ledger_entries.sql    ← transfer_id nullable (seed entries)
    V4__create_outbox_events.sql
```
