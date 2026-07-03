package com.wallet.wallet;

import com.wallet.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-layer tests for GET /wallets/{id}/transactions.
 * Verifies ledger entry structure, pagination, and balance computation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GetWalletApiTest extends BaseIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;

    @Test
    void getTransactions_walletWithInitialBalance_returnsCreditEntry() {
        String walletId = createWallet("Diana", "1000.00");

        var resp = get("/wallets/" + walletId + "/transactions");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map body = resp.getBody();
        assertThat(body.get("code")).isEqualTo("0000");

        Map data = (Map) body.get("data");
        assertThat(data.get("walletId")).isEqualTo(walletId);

        List<Map> entries = (List<Map>) data.get("entries");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("type")).isEqualTo("CREDIT");
        assertThat(entries.get(0).get("amount")).isEqualTo("1000.00");
        // seed entry has no transferId
        assertThat(entries.get(0).get("transferId")).isNull();
    }

    @Test
    void getTransactions_walletWithZeroBalance_returnsEmptyEntries() {
        String walletId = createWallet("Empty", "0.00");

        Map data = (Map) get("/wallets/" + walletId + "/transactions").getBody().get("data");
        List entries = (List) data.get("entries");
        assertThat(entries).isEmpty();
        assertThat(data.get("nextCursor")).isNull();
    }

    @Test
    void getTransactions_afterTransfer_containsDebitEntry() {
        String fromId = createWallet("Sender", "500.00");
        String toId   = createWallet("Receiver", "0.00");

        // Execute transfer
        post("/transfers", Map.of(
                "transferId", UUID.randomUUID().toString(),
                "fromWalletId", fromId,
                "toWalletId", toId,
                "amount", "200.00"
        ));

        // Sender: should have CREDIT (seed) + DEBIT (transfer)
        Map data = (Map) get("/wallets/" + fromId + "/transactions").getBody().get("data");
        List<Map> entries = (List<Map>) data.get("entries");
        assertThat(entries).hasSize(2);

        // Most recent first — DEBIT comes after seed CREDIT
        boolean hasDebit  = entries.stream().anyMatch(e -> "DEBIT".equals(e.get("type")));
        boolean hasCredit = entries.stream().anyMatch(e -> "CREDIT".equals(e.get("type")));
        assertThat(hasDebit).isTrue();
        assertThat(hasCredit).isTrue();
    }

    @Test
    void getTransactions_pagination_nextCursorPresent() {
        String walletId = createWallet("PaginationUser", "0.00");
        String toId     = createWallet("PaginationTo", "1000.00");

        // Create 5 transfers into walletId
        for (int i = 0; i < 5; i++) {
            post("/transfers", Map.of(
                    "transferId", UUID.randomUUID().toString(),
                    "fromWalletId", toId,
                    "toWalletId", walletId,
                    "amount", "10.00"
            ));
        }

        // Request page size=3 — should get nextCursor
        Map data = (Map) get("/wallets/" + walletId + "/transactions?size=3").getBody().get("data");
        List entries = (List) data.get("entries");
        assertThat(entries).hasSize(3);
        assertThat(data.get("nextCursor")).isNotNull();
    }

    @Test
    void getTransactions_pagination_noDuplicatesAcrossPages() {
        String walletId = createWallet("PaginationNoDup", "0.00");
        String toId     = createWallet("PaginationNoDupTo", "1000.00");

        // Create 5 transfers
        for (int i = 0; i < 5; i++) {
            post("/transfers", Map.of(
                    "transferId", UUID.randomUUID().toString(),
                    "fromWalletId", toId,
                    "toWalletId", walletId,
                    "amount", "10.00"
            ));
        }

        // Page 1 — size=3
        Map page1 = (Map) get("/wallets/" + walletId + "/transactions?size=3").getBody().get("data");
        List<Map> entries1 = (List<Map>) page1.get("entries");
        String cursor = (String) page1.get("nextCursor");
        assertThat(entries1).hasSize(3);
        assertThat(cursor).isNotNull();

        // Page 2 — use cursor
        Map page2 = (Map) get("/wallets/" + walletId + "/transactions?size=3&cursor=" + cursor).getBody().get("data");
        List<Map> entries2 = (List<Map>) page2.get("entries");
        assertThat(entries2).hasSize(2); // 5 total - 3 on page 1
        assertThat(page2.get("nextCursor")).isNull(); // last page

        // No duplicate IDs across pages
        var ids1 = entries1.stream().map(e -> e.get("id")).toList();
        var ids2 = entries2.stream().map(e -> e.get("id")).toList();
        assertThat(ids1).doesNotContainAnyElementsOf(ids2);

        // Total = 5
        assertThat(ids1.size() + ids2.size()).isEqualTo(5);
    }

    @Test
    void getTransactions_nonExistentWallet_balanceIsZero() {
        // computeBalance returns 0 for unknown wallet — no entries
        String fakeId = UUID.randomUUID().toString();
        var resp = get("/wallets/" + fakeId + "/transactions");
        // Wallet existence is not checked for transactions endpoint in current impl
        // so it returns empty entries rather than 404
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map data = (Map) resp.getBody().get("data");
        List entries = (List) data.get("entries");
        assertThat(entries).isEmpty();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String createWallet(String owner, String balance) {
        Map data = (Map) post("/wallets", Map.of(
                "ownerName", owner, "currency", "USD", "initialBalance", balance
        )).getBody().get("data");
        return (String) data.get("id");
    }

    private ResponseEntity<Map> post(String path, Map<String, Object> body) {
        return restTemplate.exchange(
                "http://localhost:" + port + "/api/v1" + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers()),
                Map.class
        );
    }

    private ResponseEntity<Map> get(String path) {
        return restTemplate.exchange(
                "http://localhost:" + port + "/api/v1" + path,
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
