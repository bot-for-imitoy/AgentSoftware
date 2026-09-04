package com.agent.software.llm.provider;

/**
 * Checked exception raised by {@link ProviderManager}: unknown/disabled provider,
 * missing API key, or a failed model list request (HTTP error, transport error,
 * or unparseable response).
 */
public class ProviderException extends Exception {

    private final String providerId;
    private final int statusCode; // HTTP status, or -1 when no HTTP response was received

    public ProviderException(String providerId, int statusCode, String message) {
        super(message);
        this.providerId = providerId;
        this.statusCode = statusCode;
    }

    public ProviderException(String providerId, String message, Throwable cause) {
        super(message, cause);
        this.providerId = providerId;
        this.statusCode = -1;
    }

    /** Id of the provider the failed call targeted. */
    public String providerId() {
        return providerId;
    }

    /** HTTP status code of the failed response, or -1 when the request never completed. */
    public int statusCode() {
        return statusCode;
    }

    /** Convenience constructor for configuration errors that do not concern one provider. */
    public static ProviderException config(String message) {
        return new ProviderException("", -1, message);
    }
}
