package com.wallet.transfer;

import com.wallet.BaseIntegrationTest;
import com.wallet.transfer.port.in.TransferUseCase;
import com.wallet.wallet.port.in.CreateWalletUseCase;
import com.wallet.wallet.port.out.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Service-layer concurrency tests.
 *
 * Uses TransactionTemplate per thread — @Transactional does NOT propagate across threads.
 * Each thread owns its own transaction, committing independently.
 * Pool size set to 30 in application-test.yml to avoid HikariPool exhaustion.
 */
class ConcurrencyTest extends BaseIntegrationTest {

    @Autowired CreateWalletUseCase createWalletUseCase;
    @Autowired TransferUseCase transferUseCase;
    @Autowired WalletRepository walletRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void tenSimultaneousTransfers_onlyOneSucceeds_balanceNeverNegative() throws InterruptedException {
        var from = createWalletUseCase.createWallet("Alice", "USD", new BigDecimal("100.00"));
        var to   = createWalletUseCase.createWallet("Bob",   "USD", new BigDecimal("0.00"));

        int threads = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        runConcurrent(threads, () -> {
            transactionTemplate.execute(status -> {
                try {
                    transferUseCase.transfer(UUID.randomUUID(), from.id(), to.id(), new BigDecimal("80.00"));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                }
                return null;
            });
        });

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(9);

        BigDecimal balance = walletRepository.computeBalance(from.id());
        assertThat(balance).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(balance).isEqualByComparingTo(new BigDecimal("20.00"));
    }

    @Test
    void exactBalanceTransfers_allSucceed_balanceReachesZero() throws InterruptedException {
        // Each transfer = 100, balance = 500, 5 threads → all succeed
        var from = createWalletUseCase.createWallet("Alice", "USD", new BigDecimal("500.00"));
        var to   = createWalletUseCase.createWallet("Bob",   "USD", new BigDecimal("0.00"));

        int threads = 5;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        runConcurrent(threads, () -> {
            transactionTemplate.execute(status -> {
                try {
                    transferUseCase.transfer(UUID.randomUUID(), from.id(), to.id(), new BigDecimal("100.00"));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                }
                return null;
            });
        });

        assertThat(successCount.get()).isEqualTo(5);
        assertThat(failCount.get()).isEqualTo(0);
        assertThat(walletRepository.computeBalance(from.id())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(walletRepository.computeBalance(to.id())).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    void bidirectionalTransfers_noDeadlock() throws InterruptedException {
        // Thread 1: A→B, Thread 2: B→A simultaneously
        // Lock ordering by ascending UUID must prevent deadlock
        var walletA = createWalletUseCase.createWallet("A", "USD", new BigDecimal("500.00"));
        var walletB = createWalletUseCase.createWallet("B", "USD", new BigDecimal("500.00"));

        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> {
            try {
                startLatch.await();
                transactionTemplate.execute(status -> {
                    try {
                        transferUseCase.transfer(UUID.randomUUID(), walletA.id(), walletB.id(), new BigDecimal("100.00"));
                        successCount.incrementAndGet();
                    } catch (Exception ignored) {}
                    return null;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                transactionTemplate.execute(status -> {
                    try {
                        transferUseCase.transfer(UUID.randomUUID(), walletB.id(), walletA.id(), new BigDecimal("100.00"));
                        successCount.incrementAndGet();
                    } catch (Exception ignored) {}
                    return null;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        startLatch.countDown();
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // No deadlock — both complete within timeout
        assertThat(completed).isTrue();
        // Both can succeed (both have enough balance)
        assertThat(successCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void balanceIsNeverNegative_invariantHolds() throws InterruptedException {
        // 20 threads, each trying to transfer 60 from wallet with 100
        var from = createWalletUseCase.createWallet("Alice", "USD", new BigDecimal("100.00"));
        var to   = createWalletUseCase.createWallet("Bob",   "USD", new BigDecimal("0.00"));

        runConcurrent(20, () -> {
            transactionTemplate.execute(status -> {
                try {
                    transferUseCase.transfer(UUID.randomUUID(), from.id(), to.id(), new BigDecimal("60.00"));
                } catch (Exception ignored) {}
                return null;
            });
        });

        BigDecimal balance = walletRepository.computeBalance(from.id());
        assertThat(balance).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void runConcurrent(int threads, Runnable task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();
    }
}
