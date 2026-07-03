package com.wallet.transfer;

import com.wallet.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency test via HTTP — validates the full stack end-to-end.
 * Uses RANDOM_PORT so each test run gets a fresh server.
 * Tests that 10 simultaneous HTTP requests cannot overdraw a wallet.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrencyHttpTest extends BaseIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void simultaneousHttpTransfers_cannotOverdrawWallet() throws InterruptedException {
        String base = "http://localhost:" + port + "/api/v1";

        // Create wallets via HTTP
        UUID fromId = createWallet(base, "Alice", "1000.00");
        UUID toId   = createWallet(base, "Bob",   "0.00");

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch  = new CountDownLatch(1);
        CountDownLatch doneLatch   = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    var body = Map.of(
                            "transferId",   UUID.randomUUID().toString(),
                            "fromWalletId", fromId.toString(),
                            "toWalletId",   toId.toString(),
                            "amount",       "800.00"
                    );
                    ResponseEntity<Map> resp = restTemplate.exchange(
                            base + "/transfers", HttpMethod.POST,
                            new HttpEntity<>(body, headers()), Map.class);

                    Map data = (Map) resp.getBody().get("data");
                    if (data != null && "COMPLETED".equals(data.get("status"))) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(9);

        // Verify balance via HTTP
        ResponseEntity<Map> walletResp = restTemplate.exchange(
                base + "/wallets/" + fromId, HttpMethod.GET,
                new HttpEntity<>(headers()), Map.class);
        Map data = (Map) walletResp.getBody().get("data");
        assertThat(new BigDecimal(data.get("balance").toString()))
                .isEqualByComparingTo(new BigDecimal("200.00"));
    }

    private UUID createWallet(String base, String owner, String balance) {
        var body = Map.of("ownerName", owner, "currency", "USD", "initialBalance", balance);
        ResponseEntity<Map> resp = restTemplate.exchange(
                base + "/wallets", HttpMethod.POST,
                new HttpEntity<>(body, headers()), Map.class);
        Map data = (Map) resp.getBody().get("data");
        return UUID.fromString((String) data.get("id"));
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Request-Id",   UUID.randomUUID().toString());
        h.set("X-Requestor-Id", "test");
        h.set("X-Request-Time", java.time.Instant.now().toString());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
