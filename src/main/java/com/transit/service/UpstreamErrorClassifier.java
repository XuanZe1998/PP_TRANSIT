package com.transit.service;

import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Locale;

/** Central retry boundary: only failures known to be safe before acceptance may switch accounts. */
public final class UpstreamErrorClassifier {
    private UpstreamErrorClassifier() {}

    public enum ErrorClass {
        AUTHENTICATION, RATE_LIMIT, QUOTA_EXHAUSTED, OVERLOAD, PROXY_FAILURE,
        REQUEST_ERROR, UNKNOWN_RESULT, UPSTREAM_ERROR
    }

    public static ErrorClass classify(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof java.net.ConnectException || cursor instanceof java.net.UnknownHostException) {
                return ErrorClass.PROXY_FAILURE;
            }
            if (cursor instanceof java.net.SocketTimeoutException || cursor instanceof java.util.concurrent.TimeoutException) {
                return ErrorClass.UNKNOWN_RESULT;
            }
            cursor = cursor.getCause();
        }
        if (error instanceof WebClientResponseException response) {
            int status = response.getStatusCode().value();
            String body = response.getResponseBodyAsString().toLowerCase(Locale.ROOT);
            if (status == 401 || status == 403) return ErrorClass.AUTHENTICATION;
            if (status == 429 && (body.contains("quota") || body.contains("credit"))) return ErrorClass.QUOTA_EXHAUSTED;
            if (status == 429) return ErrorClass.RATE_LIMIT;
            if (status == 408 || status == 409 || status >= 500) return ErrorClass.OVERLOAD;
            if (status >= 400 && status < 500) return ErrorClass.REQUEST_ERROR;
        }
        String message = error == null ? "" : String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("proxy") || message.contains("connect refused") || message.contains("dns")) return ErrorClass.PROXY_FAILURE;
        if (message.contains("timeout") || message.contains("reset") || message.contains("premature")) return ErrorClass.UNKNOWN_RESULT;
        return ErrorClass.UPSTREAM_ERROR;
    }

    public static boolean safeToSwitch(Throwable error) {
        return switch (classify(error)) {
            case AUTHENTICATION, RATE_LIMIT, QUOTA_EXHAUSTED, OVERLOAD, PROXY_FAILURE -> true;
            case REQUEST_ERROR, UNKNOWN_RESULT, UPSTREAM_ERROR -> false;
        };
    }
}
