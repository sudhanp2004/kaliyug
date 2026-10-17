package com.kaliyug.llm;

/** The AI answered, but twice in a row not in a usable shape. */
public class LlmBadReplyException extends LlmUnavailableException {

    public LlmBadReplyException(String message, Throwable cause) {
        super(message, cause);
    }
}
