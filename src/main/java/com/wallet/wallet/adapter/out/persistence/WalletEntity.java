package com.wallet.wallet.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallets")
public class WalletEntity {

    @Id
    private UUID id;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WalletEntity() {}

    public WalletEntity(UUID id, String ownerName, String currency, Instant createdAt) {
        this.id = id;
        this.ownerName = ownerName;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getOwnerName() { return ownerName; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
