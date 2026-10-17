package com.kaliyug.llm;

/** The AI could not produce a usable answer (network down, server errors, or unusable output). */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
