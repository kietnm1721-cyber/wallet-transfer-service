package com.wallet.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        String requestId,
        String requestorId,
        String requestTime,
        String responseTime,
        String code,
        T data,
        String error,
        String description
) {
    public static <T> ApiResponse<T> success(String requestId, String requestorId, String requestTime, T data) {
        return new ApiResponse<>(requestId, requestorId, requestTime, Instant.now().toString(), "0000", data, null, null);
    }

    public static <T> ApiResponse<T> error(String requestId, String requestorId, String requestTime,
                                            String code, String error, String description) {
        return new ApiResponse<>(requestId, requestorId, requestTime, Instant.now().toString(), code, null, error, description);
    }
}
