package com.wallet.wallet.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.wallet.shared.web.ApiResponse;
import com.wallet.transfer.domain.LedgerEntry;
import com.wallet.transfer.port.in.GetTransferUseCase;
import com.wallet.wallet.domain.Wallet;
import com.wallet.wallet.port.in.CreateWalletUseCase;
import com.wallet.wallet.port.in.GetWalletUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
@Tag(name = "Wallets")
public class WalletController {

    private final CreateWalletUseCase createWalletUseCase;
    private final GetWalletUseCase getWalletUseCase;
    private final GetTransferUseCase getTransferUseCase;

    public WalletController(CreateWalletUseCase createWalletUseCase,
                             GetWalletUseCase getWalletUseCase,
                             GetTransferUseCase getTransferUseCase) {
        this.createWalletUseCase = createWalletUseCase;
        this.getWalletUseCase = getWalletUseCase;
        this.getTransferUseCase = getTransferUseCase;
    }

    @PostMapping
    @Operation(summary = "Create a new wallet")
    public ResponseEntity<ApiResponse<WalletResponse>> createWallet(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("X-Requestor-Id") String requestorId,
            @RequestHeader("X-Request-Time") String requestTime,
            @Valid @RequestBody CreateWalletRequest request) {

        Wallet wallet = createWalletUseCase.createWallet(request.ownerName(), request.currency(), request.initialBalance());
        return ResponseEntity.status(201)
                .body(ApiResponse.success(requestId, requestorId, requestTime, WalletResponse.from(wallet)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get wallet by ID")
    public ResponseEntity<ApiResponse<WalletResponse>> getWallet(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("X-Requestor-Id") String requestorId,
            @RequestHeader("X-Request-Time") String requestTime,
            @PathVariable UUID id) {

        Wallet wallet = getWalletUseCase.getWallet(id);
        return ResponseEntity.ok(ApiResponse.success(requestId, requestorId, requestTime, WalletResponse.from(wallet)));
    }

    @GetMapping("/{id}/transactions")
    @Operation(summary = "Get wallet transaction history")
    public ResponseEntity<ApiResponse<TransactionHistoryResponse>> getTransactions(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("X-Requestor-Id") String requestorId,
            @RequestHeader("X-Request-Time") String requestTime,
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String cursor) {

        List<LedgerEntry> entries = getTransferUseCase.getLedgerEntries(id, from, to, size, cursor);
        String nextCursor = entries.size() == size ? entries.get(entries.size() - 1).id().toString() : null;
        return ResponseEntity.ok(ApiResponse.success(requestId, requestorId, requestTime,
                new TransactionHistoryResponse(id, entries.stream().map(LedgerEntryResponse::from).toList(), nextCursor)));
    }

    // DTOs
    public record CreateWalletRequest(
            @NotBlank String ownerName,
            @NotBlank String currency,
            @NotNull @DecimalMin("0.00") BigDecimal initialBalance) {}

    public record WalletResponse(UUID id, String ownerName, String currency,
                                 @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance,
                                 Instant createdAt) {
        static WalletResponse from(Wallet w) {
            return new WalletResponse(w.id(), w.ownerName(), w.currency(), w.balance(), w.createdAt());
        }
    }

    public record LedgerEntryResponse(UUID id, String type,
                                      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
                                      UUID transferId, Instant createdAt) {
        static LedgerEntryResponse from(LedgerEntry e) {
            return new LedgerEntryResponse(e.id(), e.type().name(), e.amount(), e.transferId(), e.createdAt());
        }
    }

    public record TransactionHistoryResponse(UUID walletId, List<LedgerEntryResponse> entries, String nextCursor) {}
}
