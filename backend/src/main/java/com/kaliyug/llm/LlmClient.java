package com.kaliyug.llm;

public interface LlmClient {

    /**
     * Sends a system and user message and returns the assistant's reply, which is asked to be a JSON object.
     *
     * @throws LlmUnavailableException when no answer can be obtained
     */
    String completeJson(String system, String user);
}
