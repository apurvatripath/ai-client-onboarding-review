package com.apurva.onboarding.extraction;

public interface FieldExtractor {
    String extract(String pageText) throws Exception;
    String name();
    class CallFailure extends Exception {
        public final boolean retryable;
        public final long retryAfterMillis;
        public CallFailure(String code, boolean retryable, long retryAfterMillis) {
            super(code); this.retryable=retryable; this.retryAfterMillis=retryAfterMillis;
        }
    }
}
