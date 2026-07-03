package com.wallet.transfer.adapter.out.persistence;

import com.wallet.transfer.domain.EntryType;
import com.wallet.transfer.domain.LedgerEntry;
import com.wallet.transfer.domain.Transfer;
import com.wallet.transfer.domain.TransferStatus;
import com.wallet.transfer.port.out.LedgerRepository;
import com.wallet.transfer.port.out.TransferRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class TransferPersistenceAdapter implements TransferRepository, LedgerRepository {

    private final TransferJpaRepository transferJpaRepository;
    private final LedgerJpaRepository ledgerJpaRepository;

    public TransferPersistenceAdapter(TransferJpaRepository transferJpaRepository,
                                       LedgerJpaRepository ledgerJpaRepository) {
        this.transferJpaRepository = transferJpaRepository;
        this.ledgerJpaRepository = ledgerJpaRepository;
    }

    @Override
    public Transfer save(Transfer transfer) {
        transferJpaRepository.save(new TransferEntity(
                transfer.id(), transfer.fromWalletId(), transfer.toWalletId(),
                transfer.amount(), transfer.status().name(), transfer.createdAt()
        ));
        return transfer;
    }

    @Override
    public Optional<Transfer> findById(UUID transferId) {
        return transferJpaRepository.findById(transferId).map(this::toDomain);
    }

    @Override
    public void save(LedgerEntry entry) {
        ledgerJpaRepository.save(new LedgerEntryEntity(
                entry.id(), entry.walletId(), entry.transferId(),
                entry.type().name(), entry.amount(), entry.createdAt()
        ));
    }

    @Override
    public List<LedgerEntry> findByWalletId(UUID walletId, Instant from, Instant to, int size, String cursor) {
        String fromStr = from != null ? from.toString() : null;
        String toStr   = to   != null ? to.toString()   : null;
        return ledgerJpaRepository.findByWalletIdPaginated(walletId, fromStr, toStr, size, cursor)
                .stream().map(this::toEntryDomain).toList();
    }

    private Transfer toDomain(TransferEntity e) {
        return new Transfer(e.getId(), e.getFromWalletId(), e.getToWalletId(),
                e.getAmount().setScale(2, java.math.RoundingMode.HALF_UP),
                TransferStatus.valueOf(e.getStatus()), e.getCreatedAt());
    }

    private LedgerEntry toEntryDomain(LedgerEntryEntity e) {
        return new LedgerEntry(e.getId(), e.getWalletId(), e.getTransferId(),
                EntryType.valueOf(e.getType()),
                e.getAmount().setScale(2, java.math.RoundingMode.HALF_UP),
                e.getCreatedAt());
    }
}
