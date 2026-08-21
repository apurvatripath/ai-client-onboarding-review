package com.apurva.onboarding.service;

public class InvalidSubmissionException extends RuntimeException {
    private final String field;

    public InvalidSubmissionException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}

