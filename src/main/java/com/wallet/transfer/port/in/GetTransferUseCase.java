package com.wallet.transfer.port.in;

import com.wallet.transfer.domain.LedgerEntry;
import com.wallet.transfer.domain.Transfer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GetTransferUseCase {
    Transfer getTransfer(UUID transferId);
    List<LedgerEntry> getLedgerEntries(UUID walletId, Instant from, Instant to, int size, String cursor);
}
