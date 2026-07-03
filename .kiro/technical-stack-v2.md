# Technical Stack — Wallet & Transfer Service

## Stack Summary

| Area | Decision | Where in code |
|------|----------|---------------|
| Language | Java 21 (LTS) | `build.gradle.kts` toolchain |
| Framework | Spring Boot 3.3.0 | `build.gradle.kts` |
| Architecture | Hexagonal (Ports & Adapters) | `domain/port/adapter` packages |
| Database | PostgreSQL 16 | `docker-compose.yml` |
| Migrations | Flyway V1–V4 | `src/main/resources/db/migration/` |
| Concurrency | `SELECT FOR UPDATE` — ascending UUID lock order | `WalletJpaRepository.lockById()` |
| Idempotency | DB UNIQUE constraint on `(id, from_wallet_id, to_wallet_id, amount)` | `V2__create_transfers.sql` |
| API Docs | SpringDoc OpenAPI 2.5.0 | `build.gradle.kts` → `/swagger-ui.html` |
| Testing | JUnit 5 + Testcontainers + real PostgreSQL | `BaseIntegrationTest.java` |
| Build | Gradle | `build.gradle.kts` |
| Run | Docker + docker-compose | `Dockerfile`, `docker-compose.yml` |
| Logging | SLF4J + Logback JSON + MDC | `logback-spring.xml`, `MdcFilter.java` |
| Stretch: Events | Outbox Pattern — `TransferCompleted` | `OutboxPoller`, `OutboxPersistenceAdapter` |
| Stretch: Resilience | Resilience4j 2.2.0 — circuit breaker on fraud check | `FraudCheckClient` |
| Stretch: FX | Skipped | — |

---

## 1. Language — Java 21

LTS release with virtual threads (Project Loom). Spring Boot 3.x requires minimum Java 17; Java 21 adds record patterns used across domain layer. Toolchain pinned in `build.gradle.kts` — ensures consistent JDK regardless of developer machine.

---

## 2. Framework — Spring Boot 3.3.0

Spring Data JPA for persistence, Spring TX (`@Transactional`) for atomicity, Spring Validation (`@Valid`, `@AssertTrue`) for input validation, Spring Web for REST. Mature ecosystem — no need for lighter alternatives (Quarkus, Micronaut) at this scope.

---

## 3. Architecture — Hexagonal (Ports & Adapters)

```
adapter/in/web           ← REST controllers, request/response DTOs
    │
    ▼
domain                   ← pure Java, no framework annotations
  port/in                  ← use case interfaces (TransferUseCase, GetWalletUseCase...)
  port/out                 ← repository interfaces (WalletRepository, LedgerRepository...)
    │
    ▼
adapter/out/persistence  ← JPA entities, Spring Data repositories
adapter/out/fraud        ← HTTP client, Resilience4j circuit breaker
```

`TransferService` and `WalletService` have zero Spring/JPA imports — pure Java business logic. Testable without Spring context. Infrastructure swappable without touching domain (e.g. swap JPA → JDBC, swap fraud HTTP → mock).

Layered architecture not chosen: `@Entity`, `@Repository` bleed into service layer — framework couples to business logic.

---

## 4. Database — PostgreSQL 16

ACID transactions, row-level locking (`SELECT FOR UPDATE`), JSONB for outbox payload. `NUMERIC(19,4)` for monetary amounts — exact decimal arithmetic, no floating point rounding errors.

MySQL not chosen: weaker default isolation levels, less predictable locking behavior for `SELECT FOR UPDATE` patterns.

---

## 5. Migrations — Flyway (V1–V4)

```
V1__create_wallets.sql
V2__create_transfers.sql
V3__create_ledger_entries.sql    ← transfer_id nullable for seed entries
V4__create_outbox_events.sql
```

Runs automatically on app startup. Checksums each file — refuses to start if a committed migration is modified. Schema changes always go in a new numbered file.

**Zero-downtime NOT NULL column — 3-step pattern:**
```
V10__add_col_nullable.sql    → ADD COLUMN description VARCHAR NULL
V11__backfill_col.sql        → UPDATE SET description = 'legacy' WHERE description IS NULL
V12__add_col_not_null.sql    → ALTER COLUMN description SET NOT NULL
```
Three separate deployments. No table rewrite lock, reads unblocked throughout. Never add `NOT NULL` with `DEFAULT` in a single migration on a large table — PostgreSQL rewrites the full table under lock.

---

## 6. Concurrency — Pessimistic Locking

`SELECT FOR UPDATE` acquires row-level lock on wallet before balance check. No overdraw possible — second thread blocks until first commits, then reads updated balance.

**Deadlock prevention — ascending UUID lock order:**
```java
// TransferService.java
UUID firstId  = fromWalletId.compareTo(toWalletId) < 0 ? fromWalletId : toWalletId;
UUID secondId = fromWalletId.compareTo(toWalletId) < 0 ? toWalletId   : fromWalletId;
walletRepository.lockById(firstId);   // always lock smaller UUID first
walletRepository.lockById(secondId);
```

Without lock order: Thread 1 locks A then B, Thread 2 locks B then A → circular wait → deadlock.

Optimistic locking (`@Version`) not chosen: retry loop under high contention on hot wallets leads to starvation.

---

## 7. Idempotency — DB UNIQUE Constraint

```sql
CONSTRAINT uq_transfer_idempotency UNIQUE (id, from_wallet_id, to_wallet_id, amount)
```

**Two-layer defense:**

| Layer | Mechanism | Handles |
|-------|-----------|---------|
| Application | `transferRepository.findById(transferId)` | Normal retry — returns existing COMPLETED |
| DB constraint | UNIQUE violation → `DataIntegrityViolationException` | Concurrent race — two requests arrive simultaneously |

Same `transferId` + different payload → constraint violation → 409 IDEMPOTENCY_CONFLICT.

Redis not chosen: TTL expiry risks double-charge after cache eviction — unacceptable for fintech.

---

## 8. API Documentation — SpringDoc OpenAPI 2.5.0

Auto-generates OpenAPI 3.0 spec from `@Operation`, `@Tag` annotations on controllers. Swagger UI at `http://localhost:8080/swagger-ui.html` — no extra configuration needed. Spec stays in sync with code automatically on every build.

---

## 9. Testing — JUnit 5 + Testcontainers

Real PostgreSQL in tests via Docker — not H2. H2 does not support `SELECT FOR UPDATE` row locking or PostgreSQL-specific SQL — concurrency and constraint tests would be meaningless.

| Test class | What it covers |
|------------|---------------|
| `BaseIntegrationTest` | Shared Testcontainers setup, single container per suite |
| `IdempotencyTest` | Same `transferId` submitted twice — money moves exactly once |
| `ConcurrencyTest` | 10 threads, balance=100, each tries amount=80 — exactly 1 succeeds |
| `ConcurrencyHttpTest` | Same concurrency scenario via full HTTP stack |
| `TransferApiTest` | Full HTTP integration — success, insufficient funds, validation |
| `GetTransferApiTest` | Reconciliation endpoint, 404 on missing |
| `CreateWalletApiTest` | Wallet creation, seed ledger entry, validation |
| `GetWalletApiTest` | Balance computation, pagination, cursor correctness |

---

## 10. Build — Gradle

```kotlin
// build.gradle.kts
plugins {
    java
    id("org.springframework.boot") version "3.3.0"
    id("io.spring.dependency-management") version "1.1.5"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
}
```

Dependencies cached at `~/.gradle/caches/` — workspace stays lean, git repo small.

---

## 11. Logging — SLF4J + Logback JSON + MDC

`logstash-logback-encoder:7.4` outputs structured JSON — ingestible by ELK/Datadog/CloudWatch without log parsing.

`MdcFilter.java` injects `X-Request-Id`, `X-Requestor-Id` into MDC at HTTP entry point — propagates to every log entry for that request automatically, no manual passing through method calls.

```json
{
  "level": "INFO",
  "logger": "TransferService",
  "timestamp": "2026-07-02T10:00:00Z",
  "message": "transfer_completed",
  "requestId": "abc-123",
  "requestorId": "mobile-app",
  "transferId": "xyz-456"
}
```

### Log levels per event

| Level | Event | Where |
|-------|-------|-------|
| INFO | `transfer_initiated`, `transfer_completed`, `idempotency_hit` | `TransferService` |
| INFO | `insufficient_funds` | `GlobalExceptionHandler` |
| WARN | `wallet_not_found`, `transfer_not_found` | `GlobalExceptionHandler` |
| WARN | `idempotency_conflict`, `fraud_rejected` | `GlobalExceptionHandler` |
| WARN | `missing_header`, `validation_error` | `GlobalExceptionHandler` |
| WARN | `fraud_check_timeout`, `circuit_breaker_open`, `retry_attempt` | `FraudCheckClient` |
| ERROR | `unexpected_error` (HTTP 500) | `GlobalExceptionHandler` |
| ERROR | `outbox_dead_letter` (attempts >= 5) | `OutboxPoller` |
| DEBUG | `wallet_locked`, `ledger_entries_created` | domain layer (dev profile only) |

`insufficient_funds` logged at INFO — normal business flow, high frequency in production, not actionable by ops.
All 4xx exceptions logged at WARN — unexpected client behavior worth monitoring but not alerting.
Only HTTP 500 and outbox dead letter logged at ERROR — these require ops intervention.

### Log output — file + console

`logback-spring.xml` configures two appenders wrapped in `AsyncAppender`:

```
AsyncAppender (queueSize=512, discardingThreshold=0)
    ├── ConsoleAppender  → stdout → docker logs wallet-app-1
    └── RollingFileAppender → /var/log/wallet/app.log
            rotation: daily
            filename: app.{yyyy-MM-dd}.log.gz  (gzip compressed)
            retention: 30 days
            total size cap: 1GB
```

`discardingThreshold=0` — never drops log entries even when queue is full (Logback default drops TRACE/DEBUG at 80% capacity).

File path `/var/log/wallet/` is mounted as a Docker named volume (`app_logs`) in `docker-compose.yml` — survives container restarts.

**Read logs:**
```bash
# Real-time from Docker stdout
docker logs -f wallet-app-1

# From mounted volume file
tail -f $(docker volume inspect wallet_app_logs --format '{{.Mountpoint}}')/app.log
```

---

## 12. Stretch: Outbox Pattern

`TransferCompleted` event written in same DB transaction as transfer — guaranteed to exist if committed, never exists if rolled back. No distributed transaction needed.

`@Scheduled` poller every 5s:
- Picks up `status=PENDING AND attempts < 5`
- Success → `status=PUBLISHED`
- Failure → `attempts++`, `last_attempt_at=now()`
- `attempts >= 5` → `status=DEAD` + ERROR log → manual intervention

`EventPublisher` port is swappable to Kafka/RabbitMQ without touching domain code — Hexagonal advantage.

---

## 13. Stretch: Fraud Check + Resilience4j

`FraudCheckClient` wraps external fraud service with:
- Timeout: 2s
- Retry: 2x on timeout
- Circuit breaker: opens after threshold failures, fail-closed (rejects transfer when open)

`@CircuitBreaker(name = "fraudCheck")` on adapter method — configured in `application.yml`.

---

## 14. Deferred: Ledger Snapshot Pattern

Current balance query aggregates all ledger entries per wallet — degrades as history grows over months/years.

Production mitigation (not implemented — out of scope):
```
balance_snapshots(wallet_id, balance, snapshot_at)
→ monthly snapshot job during off-peak hours
→ live balance = snapshot_balance + SUM(entries after snapshot_at)
```

Reduces full-history scan to ~1 month of entries. Deferred to keep scope focused on correctness over performance.
