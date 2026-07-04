package com.wallet.transfer;

import com.wallet.BaseIntegrationTest;
import com.wallet.shared.exception.IdempotencyConflictException;
import com.wallet.transfer.adapter.out.persistence.LedgerJpaRepository;
import com.wallet.transfer.domain.EntryType;
import com.wallet.transfer.port.in.TransferUseCase;
import com.wallet.wallet.port.in.CreateWalletUseCase;
import com.wallet.wallet.port.out.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-layer tests for idempotency guarantees.
 * Verifies: same transferId moves money exactly once,
 * same transferId with different payload throws conflict.
 */
class IdempotencyTest extends BaseIntegrationTest {

    @Autowired CreateWalletUseCase createWalletUseCase;
    @Autowired TransferUseCase transferUseCase;
    @Autowired WalletRepository walletRepository;
    @Autowired LedgerJpaRepository ledgerJpaRepository;

    @Test
    void sameTransferIdSubmittedTwice_moneyMovesOnce() {
        var from = createWalletUseCase.createWallet("Alice", "USD", new BigDecimal("1000.00"));
        var to   = createWalletUseCase.createWallet("Bob",   "USD", new BigDecimal("0.00"));
        UUID transferId = UUID.randomUUID();

        var first  = transferUseCase.transfer(transferId, from.id(), to.id(), new BigDecimal("500.00"));
        var second = transferUseCase.transfer(transferId, from.id(), to.id(), new BigDecimal("500.00"));

        // Both return same transfer record
        assertThat(first.id()).isEqualTo(transferId);
        assertThat(second.id()).isEqualTo(transferId);

        // Money deducted exactly once: 1000 - 500 = 500
        assertThat(walletRepository.computeBalance(from.id()))
                .isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(walletRepository.computeBalance(to.id()))
                .isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    void sameTransferIdDifferentAmount_throwsIdempotencyConflict() {
        var from = createWalletUseCase.createWallet("Alice", "USD", new BigDecimal("1000.00"));
        var to   = createWalletUseCase.createWallet("Bob",   "USD", new BigDecimal("0.00"));
        UUID transferId = UUID.randomUUID();

        // First call succeeds
        transferUseCase.transfer(transferId, from.id(), to.id(), new BigDecimal("100.00"));

        // Second call with different amount — must throw conflict
        assertThatThrownBy(() ->
                transferUseCase.transfer(transferId, from.id(), to.id(), new BigDecimal("999.00"))
        ).isInstanceOf(IdempotencyConflictException.class);

        // Balance unchanged after conflict
        assertThat(walletRepository.computeBalance(from.id()))
                .isEqualByComparingTo(new BigDecimal("900.00"));
    }

    @Test
    void multipleTransferIds_eachMovesMoneyIndependently() {
        var from = createWalletUseCase.createWallet("Alice", "USD", new BigDecimal("1000.00"));
        var to   = createWalletUseCase.createWallet("Bob",   "USD", new BigDecimal("0.00"));

        transferUseCase.transfer(UUID.randomUUID(), from.id(), to.id(), new BigDecimal("100.00"));
        transferUseCase.transfer(UUID.randomUUID(), from.id(), to.id(), new BigDecimal("200.00"));
        transferUseCase.transfer(UUID.randomUUID(), from.id(), to.id(), new BigDecimal("300.00"));

        // Total deducted: 600
        assertThat(walletRepository.computeBalance(from.id()))
                .isEqualByComparingTo(new BigDecimal("400.00"));
        assertThat(walletRepository.computeBalance(to.id()))
                .isEqualByComparingTo(new BigDecimal("600.00"));
    }

    @Test
    void retryAfterInsufficientFunds_noMoneyMoved() {
        var from = createWalletUseCase.createWallet("Poor", "USD", new BigDecimal("50.00"));
        var to   = createWalletUseCase.createWallet("Rich", "USD", new BigDecimal("0.00"));
        UUID transferId = UUID.randomUUID();

        // Both calls fail — balance never changes
        try { transferUseCase.transfer(transferId, from.id(), to.id(), new BigDecimal("999.00")); } catch (Exception ignored) {}
        try { transferUseCase.transfer(transferId, from.id(), to.id(), new BigDecimal("999.00")); } catch (Exception ignored) {}

        assertThat(walletRepository.computeBalance(from.id()))
                .isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void doubleEntryInvariant_exactlyOneDebitAndOneCreditPerTransfer() {
        var from = createWalletUseCase.createWallet("Alice", "USD", new BigDecimal("1000.00"));
        var to   = createWalletUseCase.createWallet("Bob",   "USD", new BigDecimal("0.00"));

        // Submit same transferId 3 times — only 1 should produce ledger entries
        UUID transferId = UUID.randomUUID();
        transferUseCase.transfer(transferId, from.id(), to.id(), new BigDecimal("100.00"));
        transferUseCase.transfer(transferId, from.id(), to.id(), new BigDecimal("100.00"));
        transferUseCase.transfer(transferId, from.id(), to.id(), new BigDecimal("100.00"));

        // Query DB directly — must be exactly 2 entries for this transferId
        var entries = ledgerJpaRepository.findAll().stream()
                .filter(e -> transferId.equals(e.getTransferId()))
                .toList();

        assertThat(entries).hasSize(2);

        long debits  = entries.stream().filter(e -> EntryType.DEBIT.name().equals(e.getType())).count();
        long credits = entries.stream().filter(e -> EntryType.CREDIT.name().equals(e.getType())).count();

        assertThat(debits).isEqualTo(1);
        assertThat(credits).isEqualTo(1);

        // Amounts must match — balanced entry
        var debit  = entries.stream().filter(e -> EntryType.DEBIT.name().equals(e.getType())).findFirst().orElseThrow();
        var credit = entries.stream().filter(e -> EntryType.CREDIT.name().equals(e.getType())).findFirst().orElseThrow();
        assertThat(debit.getAmount()).isEqualByComparingTo(credit.getAmount());

        // Balance confirms only 1 deduction despite 3 submissions
        assertThat(walletRepository.computeBalance(from.id()))
                .isEqualByComparingTo(new BigDecimal("900.00"));
    }
}
