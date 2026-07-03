package com.wallet.shared.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(UUID walletId, BigDecimal balance, BigDecimal amount) {
        super("Wallet " + walletId + " has insufficient funds: balance=" + balance + ", requested=" + amount);
    }
}
