package com.wallet.transfer.port.out;

import com.wallet.outbox.domain.OutboxEvent;

import java.util.List;

public interface OutboxRepository {
    void save(OutboxEvent event);
    List<OutboxEvent> findPending(int maxAttempts);
    void markPublished(java.util.UUID eventId);
    void incrementAttempts(java.util.UUID eventId);
    void markDead(java.util.UUID eventId);
}
