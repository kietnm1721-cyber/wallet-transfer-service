package com.wallet.outbox.adapter.out.persistence;

import com.wallet.outbox.domain.OutboxEvent;
import com.wallet.outbox.domain.OutboxStatus;
import com.wallet.transfer.port.out.OutboxRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OutboxPersistenceAdapter implements OutboxRepository {

    private final OutboxJpaRepository jpaRepository;

    public OutboxPersistenceAdapter(OutboxJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(OutboxEvent event) {
        jpaRepository.save(new OutboxEventEntity(
                event.id(), event.transferId(), event.eventType(), event.payload(),
                event.status().name(), event.attempts(), event.createdAt(), event.lastAttemptAt()
        ));
    }

    @Override
    public List<OutboxEvent> findPending(int maxAttempts) {
        return jpaRepository.findPending(maxAttempts).stream().map(this::toDomain).toList();
    }

    @Override
    public void markPublished(UUID eventId) {
        jpaRepository.findById(eventId).ifPresent(e -> {
            e.setStatus(OutboxStatus.PUBLISHED.name());
            jpaRepository.save(e);
        });
    }

    @Override
    public void incrementAttempts(UUID eventId) {
        jpaRepository.findById(eventId).ifPresent(e -> {
            e.setAttempts(e.getAttempts() + 1);
            e.setLastAttemptAt(Instant.now());
            jpaRepository.save(e);
        });
    }

    @Override
    public void markDead(UUID eventId) {
        jpaRepository.findById(eventId).ifPresent(e -> {
            e.setStatus(OutboxStatus.DEAD.name());
            jpaRepository.save(e);
        });
    }

    private OutboxEvent toDomain(OutboxEventEntity e) {
        return new OutboxEvent(e.getId(), e.getTransferId(), e.getEventType(), e.getPayload(),
                OutboxStatus.valueOf(e.getStatus()), e.getAttempts(), e.getCreatedAt(), e.getLastAttemptAt());
    }
}
