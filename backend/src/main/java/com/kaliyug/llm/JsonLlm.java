package com.kaliyug.llm;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.ObjectMapper;

/** Asks the AI for JSON and maps it to a record, retrying once when the reply doesn't fit. */
public class JsonLlm {

    private final LlmClient llm;
    private final ObjectMapper mapper;

    public JsonLlm(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        // Forgiving about single fields, so one odd item (an unknown enum, a missing number) doesn't sink a whole reply;
        // grounding checks downstream drop items that end up unusable.
        this.mapper = mapper.rebuild()
                .enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
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
                throw new LlmBadReplyException("AI reply was not valid JSON twice: " + second.getOriginalMessage(), second);
            }
        }
    }
}
