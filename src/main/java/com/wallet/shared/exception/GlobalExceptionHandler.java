package com.wallet.shared.exception;

import com.wallet.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleWalletNotFound(WalletNotFoundException ex, HttpServletRequest req) {
        log.warn("wallet_not_found description=\"{}\"", ex.getMessage());
        return ResponseEntity.status(404).body(error(req, "4004", "WALLET_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleTransferNotFound(TransferNotFoundException ex, HttpServletRequest req) {
        log.warn("transfer_not_found description=\"{}\"", ex.getMessage());
        return ResponseEntity.status(404).body(error(req, "4041", "TRANSFER_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiResponse<Void>> handleInsufficientFunds(InsufficientFundsException ex, HttpServletRequest req) {
        log.info("insufficient_funds description=\"{}\"", ex.getMessage());
        return ResponseEntity.status(422).body(error(req, "4221", "INSUFFICIENT_FUNDS", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest req) {
        log.warn("idempotency_conflict description=\"{}\"", ex.getMessage());
        return ResponseEntity.status(409).body(error(req, "4009", "IDEMPOTENCY_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(FraudRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> handleFraudRejected(FraudRejectedException ex, HttpServletRequest req) {
        log.warn("fraud_rejected description=\"{}\"", ex.getMessage());
        return ResponseEntity.status(422).body(error(req, "4222", "FRAUD_REJECTED", ex.getMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest req) {
        log.warn("missing_header header=\"{}\"", ex.getHeaderName());
        return ResponseEntity.status(400).body(error(req, "4001", "VALIDATION_ERROR", "Required header missing: " + ex.getHeaderName()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String description = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst().orElse("Validation failed");
        log.warn("validation_error description=\"{}\"", description);
        return ResponseEntity.status(400).body(error(req, "4001", "VALIDATION_ERROR", description));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex, HttpServletRequest req) {
        log.error("unexpected_error", ex);
        return ResponseEntity.status(500).body(error(req, "5000", "INTERNAL_ERROR", "An unexpected error occurred"));
    }

    private ApiResponse<Void> error(HttpServletRequest req, String code, String error, String description) {
        return ApiResponse.error(
                req.getHeader("X-Request-Id"),
                req.getHeader("X-Requestor-Id"),
                req.getHeader("X-Request-Time"),
                code, error, description
        );
    }
}
