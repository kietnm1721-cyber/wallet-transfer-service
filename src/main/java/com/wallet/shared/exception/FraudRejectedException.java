package com.wallet.shared.exception;

import java.util.UUID;

public class FraudRejectedException extends RuntimeException {
    public FraudRejectedException(UUID transferId) {
        super("Fraud check rejected transfer: " + transferId);
    }
}
