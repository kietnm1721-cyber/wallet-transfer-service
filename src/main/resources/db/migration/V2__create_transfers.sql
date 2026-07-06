CREATE TABLE transfers (
    id              UUID            PRIMARY KEY,
    from_wallet_id  UUID            NOT NULL REFERENCES wallets(id),
    to_wallet_id    UUID            NOT NULL REFERENCES wallets(id),
    amount          NUMERIC(19,4)   NOT NULL CHECK (amount > 0),
    status          VARCHAR(20)     NOT NULL DEFAULT 'COMPLETED',
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transfers_from_wallet ON transfers(from_wallet_id, created_at DESC);
CREATE INDEX idx_transfers_to_wallet   ON transfers(to_wallet_id,   created_at DESC);
