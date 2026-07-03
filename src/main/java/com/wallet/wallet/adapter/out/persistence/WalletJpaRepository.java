package com.wallet.wallet.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface WalletJpaRepository extends JpaRepository<WalletEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletEntity w WHERE w.id = :id")
    Optional<WalletEntity> lockById(@Param("id") UUID id);

    @Query(value = """
            SELECT COALESCE(
                SUM(CASE WHEN type = 'CREDIT' THEN amount ELSE -amount END),
                0.00
            )
            FROM ledger_entries
            WHERE wallet_id = :walletId
            """, nativeQuery = true)
    BigDecimal computeBalance(@Param("walletId") UUID walletId);
}
