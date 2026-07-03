package com.wallet.outbox.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {

    @Id
    private UUID id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String payload;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    protected OutboxEventEntity() {}

    public OutboxEventEntity(UUID id, UUID transferId, String eventType, String payload,
                              String status, int attempts, Instant createdAt, Instant lastAttemptAt) {
        this.id = id;
        this.transferId = transferId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = status;
        this.attempts = attempts;
        this.createdAt = createdAt;
        this.lastAttemptAt = lastAttemptAt;
    }

    public UUID getId() { return id; }
    public UUID getTransferId() { return transferId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }

    public void setStatus(String status) { this.status = status; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
}
