package com.wallet.transfer.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LedgerJpaRepository extends JpaRepository<LedgerEntryEntity, UUID> {

    @Query(value = """
            SELECT * FROM ledger_entries
            WHERE wallet_id = :walletId
              AND (CAST(:from AS timestamptz) IS NULL OR created_at >= CAST(:from AS timestamptz))
              AND (CAST(:to AS timestamptz) IS NULL OR created_at <= CAST(:to AS timestamptz))
              AND (:cursor IS NULL OR (created_at, id) < (
                    SELECT created_at, id FROM ledger_entries WHERE id = CAST(:cursor AS uuid)
                  ))
            ORDER BY created_at DESC, id DESC
            LIMIT :size
            """, nativeQuery = true)
    List<LedgerEntryEntity> findByWalletIdPaginated(
            @Param("walletId") UUID walletId,
            @Param("from") String from,
            @Param("to") String to,
            @Param("size") int size,
            @Param("cursor") String cursor
    );
}
