package com.wallet.transfer.port.in;

import com.wallet.transfer.domain.Transfer;

import java.math.BigDecimal;
import java.util.UUID;

public interface TransferUseCase {
    Transfer transfer(UUID transferId, UUID fromWalletId, UUID toWalletId, BigDecimal amount);
}
