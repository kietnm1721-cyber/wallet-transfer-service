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
