package com.wallet.wallet.port.out;

import com.wallet.wallet.domain.Wallet;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository {
    Wallet save(Wallet wallet);
    Optional<Wallet> findById(UUID id);
    // Lock wallet row for update — prevents concurrent overdraw
    Optional<Wallet> lockById(UUID id);
    BigDecimal computeBalance(UUID walletId);
}
