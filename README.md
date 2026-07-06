# Wallet & Transfer Service

Hi, thanks for taking the time to review this project.

This is a production-ready fintech backend built for the take-home assessment. It implements safe money movement between wallets with strong correctness guarantees — idempotency, concurrency safety, double-entry ledger, and async event publishing via the outbox pattern.

## Quick Start

```bash
git clone https://github.com/kietnm1721-cyber/wallet-transfer-service.git
cd wallet-transfer-service
./gradlew bootJar -x test
docker compose up --build
```

`./gradlew bootJar` builds the JAR locally (requires JDK 21). Docker then packages and runs it — no JDK needed inside the container.

| | |
|---|---|
| App | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| DB host | localhost:5432 |
| Database | wallet_db |
| Username | wallet_user |
| Password | wallet_pass |

Flyway migrations run automatically on startup. The app is ready when you see `Started WalletApplication` in the logs.

## Demo Script

Run the end-to-end demo script to verify all API operations automatically:

```bash
chmod +x demo-v2.sh
./demo-v2.sh
```

The script will:
1. Truncate DB to ensure clean state
2. Create 5 wallets with different balances
3. GET each wallet and verify balance is computed from ledger
4. Submit 30 transfers across wallets
5. Verify balances updated after transfers
6. Reconcile transfers via GET /transfers/{id}
7. Check ledger entries via GET /wallets/{id}/transactions
8. Verify idempotency — same transferId submitted twice moves money once
9. Verify double-entry invariant via DB query

Expected output: all checks green, `✓ All checks passed`

## Running the Test Suite

Requires JDK 21 and Docker running (tests use Testcontainers):

```bash
./gradlew test
```

The suite covers idempotency, concurrency, and all API endpoints against a real PostgreSQL instance.

---

## 1. Architecture Overview

**Hexagonal Architecture (Ports & Adapters)**

```
┌─────────────────────────────────────────┐
│           Inbound Adapters              │
│     (REST Controllers, HTTP layer)      │
└──────────────────┬──────────────────────┘
                   │
┌──────────────────▼──────────────────────┐
│            Domain Core                  │
│  (WalletService, TransferService)       │
│  Pure Java — no Spring, no JPA         │
│  Defines ports (interfaces)             │
└──────────┬───────────────┬──────────────┘
           │               │
┌──────────▼───┐    ┌──────▼───────────────┐
│  Outbound    │    │  Outbound Adapter     │
│  Adapter     │    │  (FraudCheckClient)   │
│  (JPA/PG)    │    │  + Circuit Breaker    │
└──────────────┘    └───────────────────────┘
```

**Why Hexagonal?**
Domain logic (balance computation, transfer rules, overdraw check) lives in pure Java — no framework annotations, no DB imports. Testable in isolation without Spring context or database. Infrastructure (PostgreSQL, fraud service) is swappable without touching domain code.

**Layers:**
- `adapter/in/web` — REST controllers, request/response mapping
- `domain` — core business logic, pure Java interfaces (ports)
- `adapter/out/persistence` — JPA repositories, Flyway migrations
- `adapter/out/fraud` — fraud check client with Resilience4j

---

## 2. Idempotency & Overdraw Prevention

### Idempotency

Enforced at **database level** via PRIMARY KEY constraint on `transfers.id`:

```sql
-- transfers.id is UUID PRIMARY KEY — duplicate INSERT on same transferId
-- violates PK constraint, caught as DataIntegrityViolationException
id UUID PRIMARY KEY
```

Client supplies `transferId` (UUID) in request body. Two-layer defense:

- Layer 1 (application): `findById` check handles sequential retries — returns existing transfer, no reprocessing
- Layer 2 (DB PRIMARY KEY): handles concurrent race — two requests arriving simultaneously both pass Layer 1 before either commits; only one INSERT wins, the other gets a PK violation → caught and returned as 409

See: `transfer/domain/TransferService.java` — idempotency check at top of `transfer()` method, and `DataIntegrityViolationException` catch block for concurrent race condition.

### Overdraw Prevention

Enforced at **database level** via `SELECT FOR UPDATE`:

```java
// WalletJpaRepository.java — locks wallet row before any balance check
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM WalletEntity w WHERE w.id = :id")
Optional<WalletEntity> lockById(@Param("id") UUID id);
```

Wallets locked in ascending UUID order to prevent deadlock — see `TransferService.java`:
```java
UUID firstId  = fromWalletId.compareTo(toWalletId) < 0 ? fromWalletId : toWalletId;
UUID secondId = fromWalletId.compareTo(toWalletId) < 0 ? toWalletId : fromWalletId;
walletRepository.lockById(firstId);
walletRepository.lockById(secondId);
```

Balance check before any ledger writes:
```java
BigDecimal balance = walletRepository.computeBalance(fromWalletId);
if (balance.compareTo(amount) < 0) throw new InsufficientFundsException(...);
```

### Double-Entry Ledger

Every transfer writes exactly 2 ledger entries in the same transaction — one DEBIT from the sender, one CREDIT to the receiver. Both entries use positive amounts, differentiated by `type` only. A wallet's balance is always computed live from the ledger (`SUM(CREDIT) - SUM(DEBIT)`), never stored as a mutable field.

**Note on initial balance:** When a wallet is created with an initial balance, a seed CREDIT entry is written with `transfer_id = NULL`. This is an intentional design simplification — the assessment scope does not require a "system float" account. The double-entry guarantee holds for all wallet-to-wallet transfers: every transfer produces exactly 1 DEBIT + 1 CREDIT with matching amounts, verified by `IdempotencyTest.doubleEntryInvariant_exactlyOneDebitAndOneCreditPerTransfer()`.

See: `transfer/domain/TransferService.java` — ledger writes after balance check.
See: `wallet/adapter/out/persistence/WalletJpaRepository.java` — `computeBalance()` native SQL.
See: `transfer/domain/TransferService.java` — lock ordering and balance check.

---

## 3. What I Would Do Differently

**Balance snapshot pattern** — currently `GET /wallets/{id}` aggregates all ledger entries to compute balance. For a wallet with years of history this degrades over time.

With more time I would add:
- `balance_snapshots` table with periodic (monthly) pre-computed balances
- Snapshot job runs during off-peak hours
- Balance query = `latest_snapshot + SUM(entries after snapshot)`
- Reduces aggregate scan from full history to ~1 month of entries

This is a common pattern in fintech ledger systems and the right production answer — deferred here to keep scope focused on correctness over performance.

---

## 4. Zero-Downtime NOT NULL Column

To add a new NOT NULL column to `ledger_entries` in production without downtime:

**3-step Flyway migration across 3 deployments:**

```
V10__add_description_nullable.sql
→ ALTER TABLE ledger_entries ADD COLUMN description VARCHAR NULL
→ Deploy — app works, column exists but nullable

V11__backfill_description.sql
→ UPDATE ledger_entries SET description = 'legacy' WHERE description IS NULL
→ Deploy — all existing rows backfilled

V12__add_description_not_null.sql
→ ALTER TABLE ledger_entries ALTER COLUMN description SET NOT NULL
→ Deploy — constraint now enforced, no nulls exist
```

Never add a NOT NULL column with a default in a single migration on a large table — PostgreSQL rewrites the entire table and holds a lock, causing downtime.

---

## Design Documents

| Document | Description |
|----------|-------------|
| `.kiro/technical-stack-v2.md` | All technology decisions with reasoning |
| `.kiro/business-flow-v2.md` | Step-by-step transfer flow, failure modes, invariants |
| `.kiro/api-contract-v2.md` | Request/response contracts for all 5 endpoints |
| `.kiro/database-schema-v2.md` | Table definitions, constraints, indexes |
| `.kiro/uat-confirmed-v2.md` | Manual UAT test cases with curl commands and DB verification queries |
| `.kiro/project-checklist-v2.md` | Full requirements and implementation checklist |

---

## Key Design Decisions

| Concern | Decision | Why |
|---------|----------|-----|
| Idempotency | DB UNIQUE constraint | Survives restarts, no TTL risk, enforced unconditionally |
| Concurrency | SELECT FOR UPDATE | DB-level lock, no application mutex, deterministic |
| Balance | Computed from ledger | No mutable field, audit trail built-in, reconcilable |
| Events | Outbox pattern | Same transaction as transfer, no distributed transaction needed |
| Resilience | Resilience4j circuit breaker | Fraud service failure doesn't block transfers |
