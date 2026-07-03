package com.wallet.transfer;

import com.wallet.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-layer tests for POST /transfers.
 * Verifies happy path, validation errors, and business error codes
 * per api-contract.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransferApiTest extends BaseIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;

    @Test
    void transfer_validRequest_returns200Completed() {
        String fromId = createWallet("Alice", "1000.00");
        String toId   = createWallet("Bob", "0.00");

        var resp = postTransfer(UUID.randomUUID(), fromId, toId, "500.00");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);  // 200 per api-contract
        Map body = resp.getBody();
        assertThat(body.get("code")).isEqualTo("0000");

        Map data = (Map) body.get("data");
        assertThat(data.get("status")).isEqualTo("COMPLETED");
        assertThat(data.get("amount")).isEqualTo("500.00");  // string per api-contract
        assertThat(data.get("fromWalletId")).isEqualTo(fromId);
        assertThat(data.get("toWalletId")).isEqualTo(toId);
    }

    @Test
    void transfer_insufficientFunds_returns422() {
        String fromId = createWallet("Poor", "100.00");
        String toId   = createWallet("Rich", "0.00");

        var resp = postTransfer(UUID.randomUUID(), fromId, toId, "999.00");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody().get("code")).isEqualTo("4221");
        assertThat(resp.getBody().get("error")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(resp.getBody().get("data")).isNull();
    }

    @Test
    void transfer_nonExistentFromWallet_returns404() {
        String toId = createWallet("Bob", "0.00");

        var resp = postTransfer(UUID.randomUUID(),
                "00000000-0000-0000-0000-000000000000", toId, "100.00");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().get("code")).isEqualTo("4004");
        assertThat(resp.getBody().get("error")).isEqualTo("WALLET_NOT_FOUND");
    }

    @Test
    void transfer_zeroAmount_returns400ValidationError() {
        String fromId = createWallet("Alice", "1000.00");
        String toId   = createWallet("Bob", "0.00");

        var resp = postTransfer(UUID.randomUUID(), fromId, toId, "0.00");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("code")).isEqualTo("4001");
        assertThat(resp.getBody().get("error")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void transfer_selfTransfer_returns400ValidationError() {
        String walletId = createWallet("Alice", "1000.00");

        var resp = postTransfer(UUID.randomUUID(), walletId, walletId, "100.00");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("code")).isEqualTo("4001");
        assertThat(resp.getBody().get("error")).isEqualTo("VALIDATION_ERROR");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String createWallet(String owner, String balance) {
        Map data = (Map) restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/wallets",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("ownerName", owner, "currency", "USD", "initialBalance", balance), headers()),
                Map.class
        ).getBody().get("data");
        return (String) data.get("id");
    }

    private ResponseEntity<Map> postTransfer(UUID transferId, String fromId, String toId, String amount) {
        return restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "transferId", transferId.toString(),
                        "fromWalletId", fromId,
                        "toWalletId", toId,
                        "amount", amount
                ), headers()),
                Map.class
        );
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Request-Id", UUID.randomUUID().toString());
        h.set("X-Requestor-Id", "test");
        h.set("X-Request-Time", "2026-07-02T10:00:00Z");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
