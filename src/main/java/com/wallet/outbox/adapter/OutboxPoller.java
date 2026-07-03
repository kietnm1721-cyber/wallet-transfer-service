package com.wallet.outbox.adapter;

import com.wallet.outbox.domain.OutboxEvent;
import com.wallet.transfer.port.out.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int MAX_ATTEMPTS = 5;

    private final OutboxRepository outboxRepository;

    public OutboxPoller(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Scheduled(fixedDelay = 5000)
    public void poll() {
        List<OutboxEvent> pending = outboxRepository.findPending(MAX_ATTEMPTS);
        for (OutboxEvent event : pending) {
            try {
                publish(event);
                outboxRepository.markPublished(event.id());
                log.info("outbox_published eventId={} transferId={}", event.id(), event.transferId());
            } catch (Exception ex) {
                outboxRepository.incrementAttempts(event.id());
                int nextAttempt = event.attempts() + 1;
                if (nextAttempt >= MAX_ATTEMPTS) {
                    outboxRepository.markDead(event.id());
                    log.error("outbox_dead eventId={} transferId={} attempts={}", event.id(), event.transferId(), nextAttempt);
                } else {
                    log.warn("outbox_retry eventId={} transferId={} attempt={} reason={}", event.id(), event.transferId(), nextAttempt, ex.getMessage());
                }
            }
        }
    }

    // In production: publish to Kafka/RabbitMQ via EventPublisher port
    // For this scope: structured log acts as the published event
    private void publish(OutboxEvent event) {
        log.info("outbox_event_published type={} transferId={} payload={}", event.eventType(), event.transferId(), event.payload());
    }
}
