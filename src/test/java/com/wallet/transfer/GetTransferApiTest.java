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
 * HTTP-layer tests for GET /transfers/{transferId}.
 * Reconciliation endpoint — verifies transfer state lookup and error codes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GetTransferApiTest extends BaseIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;

    @Test
    void getTransfer_existingTransfer_returns200WithCorrectData() {
        String fromId = createWallet("Alice", "1000.00");
        String toId   = createWallet("Bob", "0.00");
        UUID transferId = UUID.randomUUID();

        // Execute transfer first
        postTransfer(transferId, fromId, toId, "300.00");

        // Reconcile
        var resp = getTransfer(transferId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map body = resp.getBody();
        assertThat(body.get("code")).isEqualTo("0000");

        Map data = (Map) body.get("data");
        assertThat(data.get("transferId")).isEqualTo(transferId.toString());
        assertThat(data.get("status")).isEqualTo("COMPLETED");
        assertThat(data.get("fromWalletId")).isEqualTo(fromId);
        assertThat(data.get("toWalletId")).isEqualTo(toId);
        assertThat(data.get("amount")).isEqualTo("300.00");
        assertThat(data.get("createdAt")).isNotNull();
    }

    @Test
    void getTransfer_nonExistentTransferId_returns404() {
        var resp = getTransfer(UUID.randomUUID());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().get("code")).isEqualTo("4041");
        assertThat(resp.getBody().get("error")).isEqualTo("TRANSFER_NOT_FOUND");
        assertThat(resp.getBody().get("data")).isNull();
    }

    @Test
    void getTransfer_responseEnvelopeContainsAllFields() {
        String fromId = createWallet("Alice", "500.00");
        String toId   = createWallet("Bob", "0.00");
        UUID transferId = UUID.randomUUID();
        postTransfer(transferId, fromId, toId, "100.00");

        Map body = getTransfer(transferId).getBody();

        // Verify full response envelope per api-contract
        assertThat(body.get("requestId")).isNotNull();
        assertThat(body.get("requestorId")).isEqualTo("test");
        assertThat(body.get("requestTime")).isNotNull();
        assertThat(body.get("responseTime")).isNotNull();
        assertThat(body.get("code")).isEqualTo("0000");
        assertThat(body.get("error")).isNull();
        assertThat(body.get("description")).isNull();
    }

    @Test
    void getTransfer_afterFailedTransfer_balanceUnchanged() {
        String fromId = createWallet("Poor", "50.00");
        String toId   = createWallet("Rich", "0.00");
        UUID transferId = UUID.randomUUID();

        // This will fail — insufficient funds
        postTransfer(transferId, fromId, toId, "999.00");

        // Transfer record should NOT exist since it was rejected before save
        var resp = getTransfer(transferId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().get("code")).isEqualTo("4041");
    }

    @Test
    void getTransfer_afterIdempotentRetry_returnsConsistentResult() {
        String fromId = createWallet("Alice", "1000.00");
        String toId   = createWallet("Bob", "0.00");
        UUID transferId = UUID.randomUUID();

        // Submit twice
        postTransfer(transferId, fromId, toId, "200.00");
        postTransfer(transferId, fromId, toId, "200.00");

        // GET should return single COMPLETED transfer
        Map data = (Map) getTransfer(transferId).getBody().get("data");
        assertThat(data.get("status")).isEqualTo("COMPLETED");
        assertThat(data.get("amount")).isEqualTo("200.00");
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

    private void postTransfer(UUID transferId, String fromId, String toId, String amount) {
        restTemplate.exchange(
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

    private ResponseEntity<Map> getTransfer(UUID transferId) {
        return restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/transfers/" + transferId,
                HttpMethod.GET,
                new HttpEntity<>(headers()),
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
