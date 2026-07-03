package com.wallet.transfer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(
        UUID id,
        UUID walletId,
        UUID transferId,
        EntryType type,
        BigDecimal amount,
        Instant createdAt
) {}
