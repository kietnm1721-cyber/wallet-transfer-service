package com.wallet.transfer.port.out;

import java.math.BigDecimal;
import java.util.UUID;

public interface FraudCheckPort {
    boolean isFraudulent(UUID fromWalletId, UUID toWalletId, BigDecimal amount);
}
