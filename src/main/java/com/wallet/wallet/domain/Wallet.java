package com.wallet.wallet.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Wallet(
        UUID id,
        String ownerName,
        String currency,
        BigDecimal balance,
        Instant createdAt
) {}
