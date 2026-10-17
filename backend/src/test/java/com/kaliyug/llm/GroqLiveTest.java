package com.kaliyug.llm;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import com.kaliyug.config.DotEnv;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Calls the real Groq API with the first key in ../.env. Run with: -Dgroups=live -Dtest.excluded= */
@Tag("live")
class GroqLiveTest {

    @Test
    void realGroqReturnsJson() {
        String key = DotEnv.parse(Path.of("../.env")).get("GROQ_API_KEY");
        var client = new GroqClient(RestClient.builder(), new KeyPool(List.of(key), Clock.systemUTC()),
                JsonMapper.builder().build(), "https://api.groq.com/openai/v1", "openai/gpt-oss-120b");

        String reply = client.completeJson("Reply with JSON.", "Return {\"ok\":true}");

        assertTrue(reply.contains("\"ok\""), reply);
    }
}
