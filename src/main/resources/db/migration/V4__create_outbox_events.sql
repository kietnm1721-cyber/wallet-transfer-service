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
