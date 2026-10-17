package com.kaliyug.llm;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Asks the AI for JSON and maps it to a record, retrying once when the reply doesn't fit. */
public class JsonLlm {

    private final LlmClient llm;
    private final ObjectMapper mapper;

    public JsonLlm(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public <T> T ask(String system, String user, Class<T> type) {
        String reply = llm.completeJson(system, user);
        try {
            return mapper.readValue(reply, type);
        } catch (JacksonException first) {
            String retry = user + "\n\nYour previous reply was not valid JSON for the required shape: "
                    + first.getOriginalMessage() + ". Reply with JSON only.";
            try {
                return mapper.readValue(llm.completeJson(system, retry), type);
            } catch (JacksonException second) {
                throw new LlmUnavailableException("AI reply was not valid JSON twice: " + second.getOriginalMessage(), second);
            }
        }
    }
}
