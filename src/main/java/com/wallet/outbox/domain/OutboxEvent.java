package com.wallet.outbox.domain;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
        UUID id,
        UUID transferId,
        String eventType,
        String payload,
        OutboxStatus status,
        int attempts,
        Instant createdAt,
        Instant lastAttemptAt
) {
    public static OutboxEvent create(UUID transferId, String payload) {
        return new OutboxEvent(
                UUID.randomUUID(),
                transferId,
                "TransferCompleted",
                payload,
                OutboxStatus.PENDING,
                0,
                Instant.now(),
                null
        );
    }
}
