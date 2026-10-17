package com.kaliyug.llm;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonLlmTest {

    record Rec(String a) {}

    @Test
    void retriesOnceWhenReplyIsNotValidJson() {
        var fake = new FakeLlm().reply("not json").reply("{\"a\":\"x\"}");
        var llm = new JsonLlm(fake, JsonMapper.builder().build());

        assertEquals(new Rec("x"), llm.ask("sys", "user", Rec.class));
        assertEquals(2, fake.calls.size());
        assertTrue(fake.calls.get(1)[1].contains("was not valid JSON"));
    }

    @Test
    void throwsUnavailableAfterTwoBadReplies() {
        var fake = new FakeLlm().reply("nope").reply("still nope");
        var llm = new JsonLlm(fake, JsonMapper.builder().build());

        assertThrows(LlmUnavailableException.class, () -> llm.ask("sys", "user", Rec.class));
    }
}
