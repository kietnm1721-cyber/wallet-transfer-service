# API Contract — Wallet & Transfer Service

Base URL: `http://localhost:8080/api/v1`

---

## Standard Request Headers (All Operations)

| Header | Required | Description |
|--------|----------|-------------|
| X-Request-Id | Yes | Client-generated UUID, unique per request call |
| X-Requestor-Id | Yes | Consumer identity (e.g. mobile-app, web-portal, partner-api) |
| X-Request-Time | Yes | Client timestamp ISO8601 (e.g. 2026-07-02T10:00:00Z) |
| Content-Type | POST only | application/json |

---

## Standard Response Envelope

All responses share the same envelope. Null fields are omitted from JSON output (`@JsonInclude(NON_NULL)`).

**Success (HTTP 200 / 201)**
```json
{
  "requestId":    "a1b2c3d4-0000-0000-0000-000000000001",
  "requestorId":  "mobile-app",
  "requestTime":  "2026-07-02T10:00:00Z",
  "responseTime": "2026-07-02T10:00:01Z",
  "code":         "0000",
  "data": { }
}
```

**Error (HTTP 4xx / 5xx)** — `data` field absent
```json
{
  "requestId":    "a1b2c3d4-0000-0000-0000-000000000001",
  "requestorId":  "mobile-app",
  "requestTime":  "2026-07-02T10:00:00Z",
  "responseTime": "2026-07-02T10:00:01Z",
  "code":         "4004",
  "error":        "WALLET_NOT_FOUND",
  "description":  "Wallet with id 00000000-0000-0000-0000-000000000000 does not exist"
}
```

---

## System Response Codes

| HTTP | code | error | Scenario |
|------|------|-------|----------|
| 200/201 | 0000 | — | Success |
| 400 | 4001 | VALIDATION_ERROR | Blank field, amount <= 0, self-transfer, missing header |
| 404 | 4004 | WALLET_NOT_FOUND | Wallet UUID does not exist |
| 404 | 4041 | TRANSFER_NOT_FOUND | Transfer UUID does not exist |
| 409 | 4009 | IDEMPOTENCY_CONFLICT | Same transferId, different payload |
| 422 | 4221 | INSUFFICIENT_FUNDS | Sender balance < requested amount |
| 422 | 4222 | FRAUD_REJECTED | Fraud check rejected the transfer |
| 500 | 5000 | INTERNAL_ERROR | Unexpected server error |

---

## Operations Overview

| Method | Endpoint | HTTP | Purpose |
|--------|----------|------|---------|
| POST | /wallets | 201 | Create wallet |
| GET | /wallets/{id} | 200 | Get wallet + computed balance |
| GET | /wallets/{id}/transactions | 200 | Paginated ledger entries |
| POST | /transfers | 200 | Submit transfer (atomic + idempotent) |
| GET | /transfers/{transferId} | 200 | Get transfer status (reconciliation) |

---

## POST /wallets

> Create a new wallet. Server generates wallet ID. Initial balance seeded as a CREDIT ledger entry (`transfer_id = NULL`).

### Request

```
POST /api/v1/wallets
X-Request-Id:   a1b2c3d4-0001-0000-0000-000000000000
X-Requestor-Id: mobile-app
X-Request-Time: 2026-07-02T10:00:00Z
Content-Type:   application/json
```
```json
{
  "ownerName":      "Alice",
  "currency":       "USD",
  "initialBalance": "1000.00"
}
```

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| ownerName | string | Yes | Not blank |
| currency | string | Yes | Not blank (USD for all wallets in this system) |
| initialBalance | decimal | Yes | >= 0.00 |

### Response — HTTP 201

```json
{
  "requestId":    "a1b2c3d4-0001-0000-0000-000000000000",
  "requestorId":  "mobile-app",
  "requestTime":  "2026-07-02T10:00:00Z",
  "responseTime": "2026-07-02T10:00:01Z",
  "code":         "0000",
  "data": {
    "id":        "wal-aaaa-0001-0000-0000-000000000000",
    "ownerName": "Alice",
    "currency":  "USD",
    "balance":   "1000.00",
    "createdAt": "2026-07-02T10:00:01Z"
  }
}
```

### DB Writes

```
wallets        → INSERT (id=wal-aaaa-..., owner_name="Alice", currency="USD", created_at)

ledger_entries → INSERT (wallet_id=wal-aaaa-..., type=CREDIT, amount=1000.00, transfer_id=NULL)
                 skipped if initialBalance = 0.00
```

### Error Responses

**HTTP 400 — blank field:**
```json
{ "code": "4001", "error": "VALIDATION_ERROR", "description": "ownerName: must not be blank" }
```

**HTTP 400 — missing required header:**
```json
{ "code": "4001", "error": "VALIDATION_ERROR", "description": "Required header missing: X-Request-Id" }
```

**Possible error codes:** `4001`

---

## GET /wallets/{id}

> Get wallet details and current balance. Balance is NOT a stored field — computed live as `SUM(CREDIT) - SUM(DEBIT)` from `ledger_entries`.

### Request

```
GET /api/v1/wallets/wal-aaaa-0001-0000-0000-000000000000
X-Request-Id:   a1b2c3d4-0002-0000-0000-000000000000
X-Requestor-Id: mobile-app
X-Request-Time: 2026-07-02T10:05:00Z
```

### Response — HTTP 200

```json
{
  "requestId":    "a1b2c3d4-0002-0000-0000-000000000000",
  "requestorId":  "mobile-app",
  "requestTime":  "2026-07-02T10:05:00Z",
  "responseTime": "2026-07-02T10:05:00Z",
  "code":         "0000",
  "data": {
    "id":        "wal-aaaa-0001-0000-0000-000000000000",
    "ownerName": "Alice",
    "currency":  "USD",
    "balance":   "500.00",
    "createdAt": "2026-07-02T10:00:01Z"
  }
}
```

### DB Queries

```sql
-- 1. Load wallet
SELECT * FROM wallets WHERE id = :id

-- 2. Compute balance (called inside toDomain() mapping)
SELECT COALESCE(SUM(CASE WHEN type = 'CREDIT' THEN amount ELSE -amount END), 0)
FROM ledger_entries WHERE wallet_id = :id
```

### Error Responses

**HTTP 404:**
```json
{ "code": "4004", "error": "WALLET_NOT_FOUND", "description": "Wallet with id 00000000-... does not exist" }
```

**Possible error codes:** `4004`

---

## GET /wallets/{id}/transactions

> Paginated audit trail of raw DEBIT/CREDIT ledger entries for a wallet. Cursor-based keyset pagination on `(created_at DESC, id DESC)` — no offset drift.

### Request

```
GET /api/v1/wallets/wal-aaaa-0001-0000-0000-000000000000/transactions?size=2
X-Request-Id:   a1b2c3d4-0003-0000-0000-000000000000
X-Requestor-Id: mobile-app
X-Request-Time: 2026-07-02T10:10:00Z
```

| Query Param | Type | Required | Default | Description |
|-------------|------|----------|---------|-------------|
| from | ISO8601 instant | No | — | Filter entries created_at >= from |
| to | ISO8601 instant | No | — | Filter entries created_at <= to |
| size | int | No | 20 | Page size (max 100) |
| cursor | UUID string | No | — | `id` of last entry from previous page |

### Response — HTTP 200 (page 1)

```json
{
  "requestId":    "a1b2c3d4-0003-0000-0000-000000000000",
  "requestorId":  "mobile-app",
  "requestTime":  "2026-07-02T10:10:00Z",
  "responseTime": "2026-07-02T10:10:00Z",
  "code":         "0000",
  "data": {
    "walletId": "wal-aaaa-0001-0000-0000-000000000000",
    "entries": [
      {
        "id":         "led-0003-0000-0000-0000-000000000000",
        "type":       "DEBIT",
        "amount":     "200.00",
        "transferId": "txf-0002-0000-0000-0000-000000000000",
        "createdAt":  "2026-07-02T10:09:00Z"
      },
      {
        "id":         "led-0002-0000-0000-0000-000000000000",
        "type":       "DEBIT",
        "amount":     "300.00",
        "transferId": "txf-0001-0000-0000-0000-000000000000",
        "createdAt":  "2026-07-02T10:06:00Z"
      }
    ],
    "nextCursor": "led-0002-0000-0000-0000-000000000000"
  }
}
```

### Response — HTTP 200 (page 2, using cursor)

```
GET /transactions?size=2&cursor=led-0002-0000-0000-0000-000000000000
```
```json
{
  "requestId":    "a1b2c3d4-0003-0000-0000-000000000001",
  "requestorId":  "mobile-app",
  "requestTime":  "2026-07-02T10:10:01Z",
  "responseTime": "2026-07-02T10:10:01Z",
  "code":         "0000",
  "data": {
    "walletId": "wal-aaaa-0001-0000-0000-000000000000",
    "entries": [
      {
        "id":         "led-0001-0000-0000-0000-000000000000",
        "type":       "CREDIT",
        "amount":     "1000.00",
        "transferId": null,
        "createdAt":  "2026-07-02T10:00:01Z"
      }
    ],
    "nextCursor": null
  }
}
```

> `nextCursor = null` — last page. Seed entry has `transferId = null` (initial deposit, no transfer record).

### DB Query

```sql
SELECT * FROM ledger_entries
WHERE wallet_id = :walletId
  AND (:from   IS NULL OR created_at >= :from)
  AND (:to     IS NULL OR created_at <= :to)
  AND (:cursor IS NULL OR (created_at, id) < (
        SELECT created_at, id FROM ledger_entries WHERE id = CAST(:cursor AS uuid)
      ))
ORDER BY created_at DESC, id DESC
LIMIT :size
```

**Possible error codes:** `4004`, `4001`

---

## POST /transfers

> Transfer funds between two wallets. Fully atomic — transfer record + 2 ledger entries + outbox event all commit in one DB transaction. Client supplies `transferId` as Correlation ID and idempotency key.

### Request

```
POST /api/v1/transfers
X-Request-Id:   a1b2c3d4-0004-0000-0000-000000000000
X-Requestor-Id: mobile-app
X-Request-Time: 2026-07-02T10:06:00Z
Content-Type:   application/json
```
```json
{
  "transferId":   "txf-0001-0000-0000-0000-000000000000",
  "fromWalletId": "wal-aaaa-0001-0000-0000-000000000000",
  "toWalletId":   "wal-bbbb-0002-0000-0000-000000000000",
  "amount":       "300.00"
}
```

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| transferId | UUID | Yes | Client-generated, unique per transfer intent |
| fromWalletId | UUID | Yes | Must exist, must ≠ toWalletId |
| toWalletId | UUID | Yes | Must exist, must ≠ fromWalletId |
| amount | decimal | Yes | >= 0.01 |

### Response — HTTP 200

```json
{
  "requestId":    "a1b2c3d4-0004-0000-0000-000000000000",
  "requestorId":  "mobile-app",
  "requestTime":  "2026-07-02T10:06:00Z",
  "responseTime": "2026-07-02T10:06:01Z",
  "code":         "0000",
  "data": {
    "transferId":   "txf-0001-0000-0000-0000-000000000000",
    "status":       "COMPLETED",
    "fromWalletId": "wal-aaaa-0001-0000-0000-000000000000",
    "toWalletId":   "wal-bbbb-0002-0000-0000-000000000000",
    "amount":       "300.00",
    "createdAt":    "2026-07-02T10:06:01Z"
  }
}
```

> `status` is always `COMPLETED`. A transfer row only exists when the full transaction commits. Failed transfers (insufficient funds, fraud, wallet not found) result in an error response — no DB row inserted.

### DB Writes (single transaction)

```
transfers      → INSERT (id=txf-0001-..., from_wallet_id=wal-aaaa-..., to_wallet_id=wal-bbbb-...,
                          amount=300.00, status=COMPLETED)

ledger_entries → INSERT (wallet_id=wal-aaaa-..., type=DEBIT,  amount=300.00, transfer_id=txf-0001-...)
                 INSERT (wallet_id=wal-bbbb-..., type=CREDIT, amount=300.00, transfer_id=txf-0001-...)

outbox_events  → INSERT (transfer_id=txf-0001-..., event_type=TransferCompleted, status=PENDING)
```

### Idempotency

Same `transferId` submitted twice:
- First request → processes normally → returns COMPLETED
- Second request → `findById(transferId)` returns existing → returns same COMPLETED response, no DB writes

Same `transferId` with different payload (different amount/wallets):
- DB UNIQUE constraint on `(id, from_wallet_id, to_wallet_id, amount)` rejects INSERT → HTTP 409

### Client Retry Strategy

```
HTTP 200, code=0000  → stop, transfer completed
HTTP 409, code=4009  → stop, idempotency conflict — do not retry
HTTP 422, code=4221  → stop, insufficient funds — do not retry
HTTP 422, code=4222  → stop, fraud rejected — do not retry
HTTP 400, code=4001  → stop, fix request — do not retry
HTTP 5xx / timeout   → retry with same transferId
```

### Error Responses

**HTTP 400 — self-transfer:**
```json
{ "code": "4001", "error": "VALIDATION_ERROR", "description": "isNotSelfTransfer: fromWalletId and toWalletId must be different" }
```

**HTTP 400 — amount = 0:**
```json
{ "code": "4001", "error": "VALIDATION_ERROR", "description": "amount: must be greater than or equal to 0.01" }
```

**HTTP 404 — wallet not found:**
```json
{ "code": "4004", "error": "WALLET_NOT_FOUND", "description": "Wallet with id 00000000-... does not exist" }
```

**HTTP 409 — idempotency conflict:**
```json
{ "code": "4009", "error": "IDEMPOTENCY_CONFLICT", "description": "Transfer txf-0001-... already exists with different payload" }
```

**HTTP 422 — insufficient funds:**
```json
{ "code": "4221", "error": "INSUFFICIENT_FUNDS", "description": "Wallet wal-aaaa-... has balance 100.00, requested 300.00" }
```

**HTTP 422 — fraud rejected:**
```json
{ "code": "4222", "error": "FRAUD_REJECTED", "description": "Transfer txf-0001-... rejected by fraud check" }
```

**Possible error codes:** `4001`, `4004`, `4009`, `4221`, `4222`

---

## GET /transfers/{transferId}

> Reconciliation endpoint. Use after timeout or exhausted retries to check actual transfer state in DB.

### Request

```
GET /api/v1/transfers/txf-0001-0000-0000-0000-000000000000
X-Request-Id:   a1b2c3d4-0005-0000-0000-000000000000
X-Requestor-Id: mobile-app
X-Request-Time: 2026-07-02T10:07:00Z
```

### Response — HTTP 200

```json
{
  "requestId":    "a1b2c3d4-0005-0000-0000-000000000000",
  "requestorId":  "mobile-app",
  "requestTime":  "2026-07-02T10:07:00Z",
  "responseTime": "2026-07-02T10:07:00Z",
  "code":         "0000",
  "data": {
    "transferId":   "txf-0001-0000-0000-0000-000000000000",
    "status":       "COMPLETED",
    "fromWalletId": "wal-aaaa-0001-0000-0000-000000000000",
    "toWalletId":   "wal-bbbb-0002-0000-0000-000000000000",
    "amount":       "300.00",
    "createdAt":    "2026-07-02T10:06:01Z"
  }
}
```

### DB Query

```sql
SELECT * FROM transfers WHERE id = :transferId
```

### Client Reconciliation

```
HTTP 200, code=0000  → transfer completed, no action needed
HTTP 404, code=4041  → transfer not found, start new cycle with new transferId
```

> `status=FAILED` is never returned. If a transfer row exists, it is always COMPLETED.

### Error Responses

**HTTP 404:**
```json
{ "code": "4041", "error": "TRANSFER_NOT_FOUND", "description": "Transfer with id txf-0001-... does not exist" }
```

**Possible error codes:** `4041`
