package com.wallet.transfer.adapter.out.fraud;

import com.wallet.transfer.port.out.FraudCheckPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class FraudCheckClient implements FraudCheckPort {

    private static final Logger log = LoggerFactory.getLogger(FraudCheckClient.class);

    @Override
    @CircuitBreaker(name = "fraudCheck", fallbackMethod = "fraudCheckFallback")
    @Retry(name = "fraudCheck")
    public boolean isFraudulent(UUID fromWalletId, UUID toWalletId, BigDecimal amount) {
        // Simulated external fraud check — always approves in this simulation
        log.info("fraud_check_called from={} to={} amount={}", fromWalletId, toWalletId, amount);
        return false;
    }

    // Fallback when circuit is open or timeout — fail open (approve transfer)
    public boolean fraudCheckFallback(UUID fromWalletId, UUID toWalletId, BigDecimal amount, Exception ex) {
        log.warn("fraud_check_fallback from={} to={} amount={} reason={}", fromWalletId, toWalletId, amount, ex.getMessage());
        return false; // fail-open: approve when fraud service is unavailable
    }
}
