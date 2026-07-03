package com.wallet.transfer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Transfer(
        UUID id,
        UUID fromWalletId,
        UUID toWalletId,
        BigDecimal amount,
        TransferStatus status,
        Instant createdAt
) {}
