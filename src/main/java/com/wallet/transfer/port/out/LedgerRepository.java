package com.wallet.transfer.port.out;

import com.wallet.transfer.domain.LedgerEntry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LedgerRepository {
    void save(LedgerEntry entry);
    List<LedgerEntry> findByWalletId(UUID walletId, Instant from, Instant to, int size, String cursor);
}
