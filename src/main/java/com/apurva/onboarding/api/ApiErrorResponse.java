package com.apurva.onboarding.api;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(
        boolean success,
        String code,
        String message,
        List<FieldErrorDetail> fieldErrors,
        Instant timestamp
) {
    public static ApiErrorResponse of(String code, String message, List<FieldErrorDetail> fieldErrors) {
        return new ApiErrorResponse(false, code, message, fieldErrors, Instant.now());
    }
}

