package com.wallet.transfer.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.wallet.shared.web.ApiResponse;
import com.wallet.transfer.domain.Transfer;
import com.wallet.transfer.port.in.GetTransferUseCase;
import com.wallet.transfer.port.in.TransferUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers")
public class TransferController {

    private final TransferUseCase transferUseCase;
    private final GetTransferUseCase getTransferUseCase;

    public TransferController(TransferUseCase transferUseCase, GetTransferUseCase getTransferUseCase) {
        this.transferUseCase = transferUseCase;
        this.getTransferUseCase = getTransferUseCase;
    }

    @PostMapping
    @Operation(summary = "Transfer funds between wallets")
    public ResponseEntity<ApiResponse<TransferResponse>> transfer(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("X-Requestor-Id") String requestorId,
            @RequestHeader("X-Request-Time") String requestTime,
            @Valid @RequestBody TransferRequest request) {

        Transfer transfer = transferUseCase.transfer(
                request.transferId(), request.fromWalletId(), request.toWalletId(), request.amount());
        return ResponseEntity.ok(ApiResponse.success(requestId, requestorId, requestTime, TransferResponse.from(transfer)));
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "Get transfer by ID — reconciliation endpoint")
    public ResponseEntity<ApiResponse<TransferResponse>> getTransfer(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("X-Requestor-Id") String requestorId,
            @RequestHeader("X-Request-Time") String requestTime,
            @PathVariable UUID transferId) {

        Transfer transfer = getTransferUseCase.getTransfer(transferId);
        return ResponseEntity.ok(ApiResponse.success(requestId, requestorId, requestTime, TransferResponse.from(transfer)));
    }

    // DTOs
    public record TransferRequest(
            @NotNull UUID transferId,
            @NotNull UUID fromWalletId,
            @NotNull UUID toWalletId,
            @NotNull @DecimalMin("0.01") BigDecimal amount) {

        @AssertTrue(message = "fromWalletId and toWalletId must be different")
        public boolean isNotSelfTransfer() {
            return fromWalletId == null || toWalletId == null || !fromWalletId.equals(toWalletId);
        }
    }

    public record TransferResponse(UUID transferId, String status, UUID fromWalletId, UUID toWalletId,
                                   @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
                                   Instant createdAt) {
        static TransferResponse from(Transfer t) {
            return new TransferResponse(t.id(), t.status().name(), t.fromWalletId(), t.toWalletId(), t.amount(), t.createdAt());
        }
    }
}
