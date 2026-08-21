package com.apurva.onboarding.service;

public class AutomationUnavailableException extends RuntimeException {
    public AutomationUnavailableException() {
        super("The automation service is unavailable.");
    }

    public AutomationUnavailableException(Throwable cause) {
        super("The automation service is unavailable.", cause);
    }
}

