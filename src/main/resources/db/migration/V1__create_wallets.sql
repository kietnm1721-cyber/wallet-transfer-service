CREATE TABLE wallets (
    id          UUID            PRIMARY KEY,
    owner_name  VARCHAR(255)    NOT NULL,
    currency    VARCHAR(3)      NOT NULL DEFAULT 'USD',
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW()
);
