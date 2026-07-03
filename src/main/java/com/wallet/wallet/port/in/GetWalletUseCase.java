package com.wallet.wallet.port.in;

import com.wallet.wallet.domain.Wallet;

import java.util.UUID;

public interface GetWalletUseCase {
    Wallet getWallet(UUID walletId);
}
