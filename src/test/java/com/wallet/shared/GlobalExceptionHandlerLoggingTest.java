package com.wallet.shared;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wallet.shared.exception.*;
import com.wallet.transfer.adapter.in.web.TransferController;
import com.wallet.transfer.port.in.GetTransferUseCase;
import com.wallet.transfer.port.in.TransferUseCase;
import com.wallet.wallet.adapter.in.web.WalletController;
import com.wallet.wallet.port.in.CreateWalletUseCase;
import com.wallet.wallet.port.in.GetWalletUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({WalletController.class, TransferController.class})
class GlobalExceptionHandlerLoggingTest {

    @Autowired MockMvc mockMvc;

    @MockBean CreateWalletUseCase createWalletUseCase;
    @MockBean GetWalletUseCase getWalletUseCase;
    @MockBean TransferUseCase transferUseCase;
    @MockBean GetTransferUseCase getTransferUseCase;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger handlerLogger;

    @BeforeEach
    void attachAppender() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        handlerLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachAppender() {
        handlerLogger.detachAppender(listAppender);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<ILoggingEvent> logs() {
        return listAppender.list;
    }

    private boolean hasLog(Level level, String messageFragment) {
        return logs().stream().anyMatch(e ->
                e.getLevel() == level &&
                e.getFormattedMessage().contains(messageFragment));
    }

    private static final String REQUEST_ID   = "test-req-id";
    private static final String REQUESTOR_ID = "test";
    private static final String REQUEST_TIME = "2026-07-02T10:00:00Z";

    // ── Test cases ────────────────────────────────────────────────────────────

    @Test
    void walletNotFound_shouldLogWarn() throws Exception {
        UUID id = UUID.randomUUID();
        when(getWalletUseCase.getWallet(any())).thenThrow(new WalletNotFoundException(id));

        mockMvc.perform(get("/api/v1/wallets/" + id)
                .header("X-Request-Id", REQUEST_ID)
                .header("X-Requestor-Id", REQUESTOR_ID)
                .header("X-Request-Time", REQUEST_TIME))
                .andExpect(status().isNotFound());

        assertThat(hasLog(Level.WARN, "wallet_not_found")).isTrue();
    }

    @Test
    void insufficientFunds_shouldLogInfo() throws Exception {
        UUID fromId = UUID.randomUUID();
        UUID toId   = UUID.randomUUID();
        when(transferUseCase.transfer(any(), any(), any(), any()))
                .thenThrow(new InsufficientFundsException(fromId, BigDecimal.valueOf(50), BigDecimal.valueOf(200)));

        mockMvc.perform(post("/api/v1/transfers")
                .contentType("application/json")
                .header("X-Request-Id", REQUEST_ID)
                .header("X-Requestor-Id", REQUESTOR_ID)
                .header("X-Request-Time", REQUEST_TIME)
                .content("""
                        {"transferId":"%s","fromWalletId":"%s","toWalletId":"%s","amount":"200.00"}
                        """.formatted(UUID.randomUUID(), fromId, toId)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(hasLog(Level.INFO, "insufficient_funds")).isTrue();
    }

    @Test
    void idempotencyConflict_shouldLogWarn() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferUseCase.transfer(any(), any(), any(), any()))
                .thenThrow(new IdempotencyConflictException(transferId));

        mockMvc.perform(post("/api/v1/transfers")
                .contentType("application/json")
                .header("X-Request-Id", REQUEST_ID)
                .header("X-Requestor-Id", REQUESTOR_ID)
                .header("X-Request-Time", REQUEST_TIME)
                .content("""
                        {"transferId":"%s","fromWalletId":"%s","toWalletId":"%s","amount":"100.00"}
                        """.formatted(transferId, UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isConflict());

        assertThat(hasLog(Level.WARN, "idempotency_conflict")).isTrue();
    }

    @Test
    void fraudRejected_shouldLogWarn() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferUseCase.transfer(any(), any(), any(), any()))
                .thenThrow(new FraudRejectedException(transferId));

        mockMvc.perform(post("/api/v1/transfers")
                .contentType("application/json")
                .header("X-Request-Id", REQUEST_ID)
                .header("X-Requestor-Id", REQUESTOR_ID)
                .header("X-Request-Time", REQUEST_TIME)
                .content("""
                        {"transferId":"%s","fromWalletId":"%s","toWalletId":"%s","amount":"100.00"}
                        """.formatted(transferId, UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isUnprocessableEntity());

        assertThat(hasLog(Level.WARN, "fraud_rejected")).isTrue();
    }

    @Test
    void unexpectedException_shouldLogError() throws Exception {
        when(getWalletUseCase.getWallet(any()))
                .thenThrow(new RuntimeException("unexpected db failure"));

        mockMvc.perform(get("/api/v1/wallets/" + UUID.randomUUID())
                .header("X-Request-Id", REQUEST_ID)
                .header("X-Requestor-Id", REQUESTOR_ID)
                .header("X-Request-Time", REQUEST_TIME))
                .andExpect(status().isInternalServerError());

        assertThat(hasLog(Level.ERROR, "unexpected_error")).isTrue();
    }

    @Test
    void missingHeader_shouldLogWarn() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/" + UUID.randomUUID())
                // Missing X-Request-Id
                .header("X-Requestor-Id", REQUESTOR_ID)
                .header("X-Request-Time", REQUEST_TIME))
                .andExpect(status().isBadRequest());

        assertThat(hasLog(Level.WARN, "missing_header")).isTrue();
    }

    @Test
    void validationError_shouldLogWarn() throws Exception {
        mockMvc.perform(post("/api/v1/wallets")
                .contentType("application/json")
                .header("X-Request-Id", REQUEST_ID)
                .header("X-Requestor-Id", REQUESTOR_ID)
                .header("X-Request-Time", REQUEST_TIME)
                .content("""
                        {"ownerName":"","currency":"USD","initialBalance":"0.00"}
                        """))
                .andExpect(status().isBadRequest());

        assertThat(hasLog(Level.WARN, "validation_error")).isTrue();
    }
}
