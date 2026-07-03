package com.wallet.transfer.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntryEntity {

    @Id
    private UUID id;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntryEntity() {}

    public LedgerEntryEntity(UUID id, UUID walletId, UUID transferId, String type, BigDecimal amount, Instant createdAt) {
        this.id = id;
        this.walletId = walletId;
        this.transferId = transferId;
        this.type = type;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getWalletId() { return walletId; }
    public UUID getTransferId() { return transferId; }
    public String getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public Instant getCreatedAt() { return createdAt; }
}
