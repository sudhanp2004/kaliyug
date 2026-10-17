package com.kaliyug.llm;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GroqClientTest {

    private static final String URL = "https://groq.test/v1/chat/completions";
    private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"{\\\"x\\\":1}\"}}]}";

    private MockRestServiceServer server;
    private KeyPool pool;
    private GroqClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        pool = new KeyPool(List.of("k1", "k2"), Clock.systemUTC());
        client = new GroqClient(builder, pool, JsonMapper.builder().build(), "https://groq.test/v1", "openai/gpt-oss-120b", Duration.ofMillis(1));
    }

    @Test
    void sendsBearerKeyModelAndJsonMode() {
        server.expect(requestTo(URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer k1"))
                .andExpect(jsonPath("$.model").value("openai/gpt-oss-120b"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].content").value("hello"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        assertEquals("{\"x\":1}", client.completeJson("sys", "hello"));
        server.verify();
    }

    @Test
    void on429CoolsKeyAndRetriesWithNext() throws Exception {
        HttpHeaders retryAfter = new HttpHeaders();
        retryAfter.add("retry-after", "7");
        server.expect(requestTo(URL)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer k1"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryAfter));
        server.expect(requestTo(URL)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer k2"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        assertEquals("{\"x\":1}", client.completeJson("sys", "hello"));
        server.verify();
        assertEquals("k2", pool.acquire());
    }

    @Test
    void serverErrorsRetryThreeTimesThenThrow() {
        server.expect(times(4), requestTo(URL)).andRespond(withServerError());

        var e = assertThrows(LlmUnavailableException.class, () -> client.completeJson("sys", "hello"));
        server.verify();
        assertFalse(e.getMessage().contains("k1") || e.getMessage().contains("k2"));
    }
}
