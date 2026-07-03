package com.wallet.transfer.domain;

import com.wallet.outbox.domain.OutboxEvent;
import com.wallet.shared.exception.FraudRejectedException;
import com.wallet.shared.exception.IdempotencyConflictException;
import com.wallet.shared.exception.InsufficientFundsException;
import com.wallet.shared.exception.TransferNotFoundException;
import com.wallet.shared.exception.WalletNotFoundException;
import com.wallet.transfer.port.in.GetTransferUseCase;
import com.wallet.transfer.port.in.TransferUseCase;
import com.wallet.transfer.port.out.FraudCheckPort;
import com.wallet.transfer.port.out.LedgerRepository;
import com.wallet.transfer.port.out.OutboxRepository;
import com.wallet.transfer.port.out.TransferRepository;
import com.wallet.wallet.port.out.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TransferService implements TransferUseCase, GetTransferUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final int MAX_OUTBOX_ATTEMPTS = 5;

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final LedgerRepository ledgerRepository;
    private final OutboxRepository outboxRepository;
    private final FraudCheckPort fraudCheckPort;

    public TransferService(
            WalletRepository walletRepository,
            TransferRepository transferRepository,
            LedgerRepository ledgerRepository,
            OutboxRepository outboxRepository,
            FraudCheckPort fraudCheckPort) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.ledgerRepository = ledgerRepository;
        this.outboxRepository = outboxRepository;
        this.fraudCheckPort = fraudCheckPort;
    }

    @Override
    @Transactional
    public Transfer transfer(UUID transferId, UUID fromWalletId, UUID toWalletId, BigDecimal amount) {
        log.info("transfer_initiated transferId={} from={} to={} amount={}", transferId, fromWalletId, toWalletId, amount);

        // Idempotency check — return existing if already processed
        var existing = transferRepository.findById(transferId);
        if (existing.isPresent()) {
            Transfer t = existing.get();
            // Same payload — idempotent return
            if (t.fromWalletId().equals(fromWalletId)
                    && t.toWalletId().equals(toWalletId)
                    && t.amount().compareTo(amount) == 0) {
                log.info("idempotency_hit transferId={}", transferId);
                return t;
            }
            // Different payload — conflict
            log.warn("idempotency_conflict transferId={}", transferId);
            throw new IdempotencyConflictException(transferId);
        }

        // Fraud check — protected by circuit breaker in adapter layer
        if (fraudCheckPort.isFraudulent(fromWalletId, toWalletId, amount)) {
            throw new FraudRejectedException(transferId);
        }

        // Lock wallets in ascending UUID order to prevent deadlock
        UUID firstId  = fromWalletId.compareTo(toWalletId) < 0 ? fromWalletId : toWalletId;
        UUID secondId = fromWalletId.compareTo(toWalletId) < 0 ? toWalletId : fromWalletId;

        walletRepository.lockById(firstId).orElseThrow(() -> new WalletNotFoundException(firstId));
        walletRepository.lockById(secondId).orElseThrow(() -> new WalletNotFoundException(secondId));

        // Balance check — computed from ledger while holding lock
        BigDecimal balance = walletRepository.computeBalance(fromWalletId);
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(fromWalletId, balance, amount);
        }

        // Save transfer record — UNIQUE constraint enforces idempotency at DB level
        Transfer transfer = new Transfer(transferId, fromWalletId, toWalletId, amount, TransferStatus.COMPLETED, Instant.now());
        try {
            transferRepository.save(transfer);
        } catch (DataIntegrityViolationException e) {
            // Concurrent request with same transferId — return existing
            log.info("idempotency_hit_concurrent transferId={}", transferId);
            throw new IdempotencyConflictException(transferId);
        }

        // Write double-entry ledger — both entries in same transaction
        ledgerRepository.save(new LedgerEntry(UUID.randomUUID(), fromWalletId, transferId, EntryType.DEBIT, amount, Instant.now()));
        ledgerRepository.save(new LedgerEntry(UUID.randomUUID(), toWalletId, transferId, EntryType.CREDIT, amount, Instant.now()));

        // Write outbox event — same transaction guarantees delivery
        outboxRepository.save(OutboxEvent.create(transferId, buildPayload(transfer)));

        log.info("transfer_completed transferId={} amount={}", transferId, amount);
        return transfer;
    }

    @Override
    @Transactional(readOnly = true)
    public Transfer getTransfer(UUID transferId) {
        return transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerEntry> getLedgerEntries(UUID walletId, Instant from, Instant to, int size, String cursor) {
        return ledgerRepository.findByWalletId(walletId, from, to, size, cursor);
    }

    private String buildPayload(Transfer transfer) {
        return String.format(
                "{\"transferId\":\"%s\",\"fromWalletId\":\"%s\",\"toWalletId\":\"%s\",\"amount\":\"%s\"}",
                transfer.id(), transfer.fromWalletId(), transfer.toWalletId(), transfer.amount()
        );
    }
}
