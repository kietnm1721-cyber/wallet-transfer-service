package com.wallet.wallet.adapter.out.persistence;

import com.wallet.wallet.domain.Wallet;
import com.wallet.wallet.port.out.WalletRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Component
public class WalletPersistenceAdapter implements WalletRepository {

    private final WalletJpaRepository jpaRepository;

    public WalletPersistenceAdapter(WalletJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Wallet save(Wallet wallet) {
        WalletEntity entity = new WalletEntity(wallet.id(), wallet.ownerName(), wallet.currency(), wallet.createdAt());
        jpaRepository.save(entity);
        return wallet;
    }

    @Override
    public Optional<Wallet> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<Wallet> lockById(UUID id) {
        return jpaRepository.lockById(id).map(this::toDomain);
    }

    @Override
    public BigDecimal computeBalance(UUID walletId) {
        return jpaRepository.computeBalance(walletId);
    }

    private Wallet toDomain(WalletEntity e) {
        BigDecimal raw = jpaRepository.computeBalance(e.getId());
        BigDecimal balance = (raw != null ? raw : BigDecimal.ZERO).setScale(2, java.math.RoundingMode.HALF_UP);
        return new Wallet(e.getId(), e.getOwnerName(), e.getCurrency(), balance, e.getCreatedAt());
    }
}
