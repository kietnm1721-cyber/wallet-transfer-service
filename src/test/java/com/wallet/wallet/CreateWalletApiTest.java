package com.wallet.wallet;

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
 * HTTP-layer tests for POST /wallets and GET /wallets/{id}.
 * Verifies response envelope, HTTP status codes, field types, and error codes
 * as defined in api-contract.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CreateWalletApiTest extends BaseIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;

    // ─── POST /wallets ────────────────────────────────────────────────────────

    @Test
    void createWallet_validRequest_returns201WithEnvelope() {
        var resp = post("/wallets", Map.of(
                "ownerName", "Alice",
                "currency", "USD",
                "initialBalance", "1000.00"
        ));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map body = resp.getBody();
        assertThat(body.get("code")).isEqualTo("0000");
        assertThat(body.get("requestId")).isEqualTo("test-create-wallet");
        assertThat(body.get("error")).isNull();

        Map data = (Map) body.get("data");
        assertThat(data.get("id")).isNotNull();
        assertThat(data.get("ownerName")).isEqualTo("Alice");
        assertThat(data.get("currency")).isEqualTo("USD");
        // balance serialized as String via @JsonFormat(STRING) — Jackson deserializes back to String in Map
        assertThat(data.get("balance")).isEqualTo("1000.00");
        assertThat(data.get("createdAt")).isNotNull();
    }

    @Test
    void createWallet_zeroInitialBalance_returns201BalanceZero() {
        var resp = post("/wallets", Map.of(
                "ownerName", "Bob",
                "currency", "USD",
                "initialBalance", "0.00"
        ));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map data = (Map) resp.getBody().get("data");
        assertThat(data.get("balance")).isEqualTo("0.00");
    }

    @Test
    void createWallet_blankOwnerName_returns400ValidationError() {
        var resp = post("/wallets", Map.of(
                "ownerName", "",
                "currency", "USD",
                "initialBalance", "0.00"
        ));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map body = resp.getBody();
        assertThat(body.get("code")).isEqualTo("4001");
        assertThat(body.get("error")).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("data")).isNull();
    }

    @Test
    void createWallet_missingRequiredHeader_returns400() {
        // Missing X-Request-Id header
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Requestor-Id", "test");
        headers.set("X-Request-Time", "2026-07-02T10:00:00Z");
        headers.setContentType(MediaType.APPLICATION_JSON);

        var resp = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/wallets",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("ownerName", "Test", "currency", "USD", "initialBalance", "0.00"), headers),
                Map.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("code")).isEqualTo("4001");
    }

    @Test
    void createWallet_serverGeneratesId_clientCannotSupplyId() {
        // Create same owner twice — must get different IDs (server-generated)
        var r1 = post("/wallets", Map.of("ownerName", "Alice", "currency", "USD", "initialBalance", "500.00"));
        var r2 = post("/wallets", Map.of("ownerName", "Alice", "currency", "USD", "initialBalance", "500.00"));

        String id1 = (String) ((Map) r1.getBody().get("data")).get("id");
        String id2 = (String) ((Map) r2.getBody().get("data")).get("id");
        assertThat(id1).isNotEqualTo(id2);
    }

    // ─── GET /wallets/{id} ───────────────────────────────────────────────────

    @Test
    void getWallet_existingWallet_returns200WithBalance() {
        // Create first
        Map created = (Map) post("/wallets", Map.of(
                "ownerName", "Charlie", "currency", "USD", "initialBalance", "750.00"
        )).getBody().get("data");
        String walletId = (String) created.get("id");

        // Then fetch
        var resp = get("/wallets/" + walletId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map data = (Map) resp.getBody().get("data");
        assertThat(data.get("id")).isEqualTo(walletId);
        assertThat(data.get("balance")).isEqualTo("750.00");
    }

    @Test
    void getWallet_nonExistentId_returns404() {
        var resp = get("/wallets/00000000-0000-0000-0000-000000000000");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().get("code")).isEqualTo("4004");
        assertThat(resp.getBody().get("error")).isEqualTo("WALLET_NOT_FOUND");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private ResponseEntity<Map> post(String path, Map<String, Object> body) {
        return restTemplate.exchange(
                "http://localhost:" + port + "/api/v1" + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers("test-create-wallet")),
                Map.class
        );
    }

    private ResponseEntity<Map> get(String path) {
        return restTemplate.exchange(
                "http://localhost:" + port + "/api/v1" + path,
                HttpMethod.GET,
                new HttpEntity<>(headers("test-get-wallet")),
                Map.class
        );
    }

    private HttpHeaders headers(String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Request-Id", requestId);
        h.set("X-Requestor-Id", "test");
        h.set("X-Request-Time", "2026-07-02T10:00:00Z");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
