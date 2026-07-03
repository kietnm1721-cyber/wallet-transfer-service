package com.wallet.wallet.port.in;

import com.wallet.wallet.domain.Wallet;

import java.math.BigDecimal;

public interface CreateWalletUseCase {
    Wallet createWallet(String ownerName, String currency, BigDecimal initialBalance);
}
