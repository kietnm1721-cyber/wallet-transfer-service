package com.wallet.transfer.port.out;

import com.wallet.transfer.domain.Transfer;

import java.util.Optional;
import java.util.UUID;

public interface TransferRepository {
    Transfer save(Transfer transfer);
    Optional<Transfer> findById(UUID transferId);
}
