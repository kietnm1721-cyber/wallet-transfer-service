package com.wallet.outbox.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {

    @Query("SELECT e FROM OutboxEventEntity e WHERE e.status = 'PENDING' AND e.attempts < :maxAttempts")
    List<OutboxEventEntity> findPending(@Param("maxAttempts") int maxAttempts);
}
