package com.wallet.wallet.domain;

import com.wallet.shared.exception.WalletNotFoundException;
import com.wallet.transfer.domain.EntryType;
import com.wallet.transfer.domain.LedgerEntry;
import com.wallet.transfer.port.out.LedgerRepository;
import com.wallet.wallet.port.in.CreateWalletUseCase;
import com.wallet.wallet.port.in.GetWalletUseCase;
import com.wallet.wallet.port.out.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
public class WalletService implements CreateWalletUseCase, GetWalletUseCase {

    private final WalletRepository walletRepository;
    private final LedgerRepository ledgerRepository;

    public WalletService(WalletRepository walletRepository,
                         LedgerRepository ledgerRepository) {
        this.walletRepository = walletRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Override
    @Transactional
    public Wallet createWallet(String ownerName, String currency, BigDecimal initialBalance) {
        UUID walletId = UUID.randomUUID();
        Wallet wallet = new Wallet(walletId, ownerName, currency, BigDecimal.ZERO, Instant.now());
        walletRepository.save(wallet);

        // Seed initial balance as a direct CREDIT ledger entry — no transfer record needed.
        // transfer_id is null for seed entries (initial deposit, not a wallet-to-wallet transfer).
        if (initialBalance.compareTo(BigDecimal.ZERO) > 0) {
            ledgerRepository.save(new LedgerEntry(UUID.randomUUID(), walletId, null, EntryType.CREDIT, initialBalance, Instant.now()));
        }

        return walletRepository.findById(walletId).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Wallet getWallet(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }
}
