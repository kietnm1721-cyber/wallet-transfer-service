package com.wallet.shared.exception;

import java.util.UUID;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(UUID transferId) {
        super("Idempotency conflict: transferId=" + transferId + " submitted with different payload");
    }
}
