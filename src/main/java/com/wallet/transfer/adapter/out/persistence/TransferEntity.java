package com.wallet.transfer.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfers")
public class TransferEntity {

    @Id
    private UUID id;

    @Column(name = "from_wallet_id", nullable = false)
    private UUID fromWalletId;

    @Column(name = "to_wallet_id", nullable = false)
    private UUID toWalletId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TransferEntity() {}

    public TransferEntity(UUID id, UUID fromWalletId, UUID toWalletId, BigDecimal amount, String status, Instant createdAt) {
        this.id = id;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getFromWalletId() { return fromWalletId; }
    public UUID getToWalletId() { return toWalletId; }
    public BigDecimal getAmount() { return amount; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
