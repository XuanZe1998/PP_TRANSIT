package com.transit.service;

public class DujiaoNextApiException extends RuntimeException {
    private final String errorCode;
    private final int httpStatus;
    private final boolean retryable;

    public DujiaoNextApiException(String errorCode, String message, int httpStatus, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public DujiaoNextApiException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = "network_error";
        this.httpStatus = 0;
        this.retryable = retryable;
    }

    public String getErrorCode() { return errorCode; }
    public int getHttpStatus() { return httpStatus; }
    public boolean isRetryable() { return retryable; }
}
