# Project Checklist — Wallet & Transfer Service

## Functional Requirements

| Requirement | Design Doc | Code |
|-------------|-----------|------|
| Create wallet API | api-contract-v2.md | WalletController + WalletService |
| Query wallet balance | api-contract-v2.md | WalletService → WalletJpaRepository.computeBalance() |
| Wallet transaction history (paginated) | api-contract-v2.md | WalletController → LedgerJpaRepository.findByWalletIdPaginated() |
| Transfer funds (atomic) | api-contract-v2.md + business-flow-v2.md | TransferService @Transactional |
| Idempotency via transferId | api-contract-v2.md + business-flow-v2.md | UNIQUE constraint + TransferService.findById() |
| Double-entry ledger | business-flow-v2.md | LedgerEntry DEBIT+CREDIT in TransferService |
| Concurrency safety | business-flow-v2.md | WalletJpaRepository.lockById() SELECT FOR UPDATE |
| Consistent error model | api-contract-v2.md | GlobalExceptionHandler |

---

## Technical Requirements

| Requirement | Design Doc | Code |
|-------------|-----------|------|
| Hexagonal Architecture | technical-stack-v2.md | domain/port/adapter package structure |
| PostgreSQL + Flyway V1-V4 | technical-stack-v2.md + database-schema-v2.md | src/main/resources/db/migration/ |
| DB transactions on transfer path | business-flow-v2.md | @Transactional on TransferService |
| Idempotency test | technical-stack-v2.md | IdempotencyTest.java |
| Concurrency test | technical-stack-v2.md | ConcurrencyTest.java + ConcurrencyHttpTest.java |
| Docker Compose | technical-stack-v2.md | docker-compose.yml + Dockerfile |
| SpringDoc OpenAPI / Swagger UI | technical-stack-v2.md | springdoc-openapi-starter-webmvc-ui:2.5.0 |
| Structured logging (JSON + MDC) | technical-stack-v2.md | logback-spring.xml + MdcFilter.java |

---

## Stretch Goals

| Goal | Design Doc | Code |
|------|-----------|------|
| Outbox Pattern (TransferCompleted) | business-flow-v2.md | OutboxPoller + OutboxPersistenceAdapter |
| Fraud check + Circuit Breaker | business-flow-v2.md | FraudCheckClient @CircuitBreaker (Resilience4j 2.2.0) |
| FX Conversion | Skipped | Skipped |

---

## Scoring Criteria

| Criteria | How addressed |
|----------|--------------|
| Correctness under stress | SELECT FOR UPDATE + UNIQUE constraint — verified by ConcurrencyTest + IdempotencyTest |
| Data integrity | Double-entry ledger, no stored balance — verified by DB invariants in database-schema-v2.md |
| Engineering discipline | Testcontainers (real PG), Hexagonal, clean port/adapter separation |
| Production thinking | Flyway migrations, structured JSON logging, MDC correlation, docker-compose |
| Communication | README.md — how to run, architecture, idempotency, snapshot pattern, zero-downtime migration |

---

## Design Documents

| File | Status | Notes |
|------|--------|-------|
| api-contract-v2.md | Complete | Full request/response/DB mapping per operation |
| business-flow-v2.md | Complete | 5 flows with step-by-step, failure tables, invariants |
| database-schema-v2.md | Complete | V1-V4 only, column tables, DB invariants |
| technical-stack-v2.md | Complete | All decisions with code references, actual versions |
| README.md | Complete | How to run, architecture, idempotency, snapshot, zero-downtime |
| UAT-CONFIRM.md | Complete | 18 test cases with curl commands + DB verification queries |

---

## Key Design Decisions

| Concern | Decision | Rationale |
|---------|----------|-----------|
| Idempotency | DB UNIQUE constraint on (id, from_wallet_id, to_wallet_id, amount) | Survives restarts, no TTL risk, enforced unconditionally |
| Concurrency | SELECT FOR UPDATE, ascending UUID lock order | DB-level lock, deterministic, no deadlock |
| Balance | Computed from ledger, never stored | No mutable field, audit trail built-in, reconcilable |
| Atomicity | @Transactional — transfer + ledger + outbox in one commit | All-or-nothing, no partial state |
| Events | Outbox pattern — same transaction as transfer | Guaranteed delivery, no distributed transaction |
| Resilience | Resilience4j circuit breaker on fraud check | Fraud failure doesn't block transfers |
| Status | transfer.status always COMPLETED | Row only exists if commit succeeded — failed transfers rollback |
