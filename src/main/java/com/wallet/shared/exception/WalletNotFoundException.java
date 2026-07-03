package com.wallet.shared.exception;

import java.util.UUID;

public class WalletNotFoundException extends RuntimeException {
    public WalletNotFoundException(UUID walletId) {
        super("Wallet not found: " + walletId);
    }
}
