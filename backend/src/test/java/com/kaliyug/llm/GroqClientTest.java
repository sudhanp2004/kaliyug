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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private RestClient.Builder lastBuilder;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        lastBuilder = builder;
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
    void invalidJsonGenerationIsRetriedImmediately() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"Failed to generate JSON\",\"code\":\"json_validate_failed\"}}"));
        server.expect(requestTo(URL)).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        assertEquals("{\"x\":1}", client.completeJson("sys", "hello"));
        server.verify();
    }

    @Test
    void otherBadRequestsFailWithGroqsReason() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"context too long\",\"code\":\"context_length_exceeded\"}}"));

        var e = assertThrows(LlmUnavailableException.class, () -> client.completeJson("sys", "hello"));
        assertTrue(e.getMessage().contains("context_length_exceeded"), e.getMessage());
    }

    @Test
    void serverErrorsRetryThreeTimesThenThrow() {
        server.expect(times(4), requestTo(URL)).andRespond(withServerError());

        var e = assertThrows(LlmUnavailableException.class, () -> client.completeJson("sys", "hello"));
        server.verify();
        assertFalse(e.getMessage().contains("k1") || e.getMessage().contains("k2"));
    }

    @Test
    void slowServerTimesOutAsUnavailable() throws Exception {
        var slow = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                // test server shutting down
            }
            exchange.close();
        });
        slow.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        slow.start();
        try {
            var timed = new GroqClient(RestClient.builder(), pool, JsonMapper.builder().build(),
                    "http://127.0.0.1:" + slow.getAddress().getPort(), "m", Duration.ofMillis(1),
                    Duration.ofMillis(200), Duration.ofSeconds(1));
            long start = System.nanoTime();
            assertThrows(LlmUnavailableException.class, () -> timed.completeJson("sys", "hello"));
            assertTrue((System.nanoTime() - start) / 1_000_000 < 2500, "should not wait for the slow server");
        } finally {
            slow.stop(0);
        }
    }

    @Test
    void allKeysRateLimitedGivesUpAfterMaxKeyWait() {
        HttpHeaders retryAfter = new HttpHeaders();
        retryAfter.add("retry-after", "30");
        server.expect(times(2), requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryAfter));
        var waiting = new GroqClient(builderFor(server), pool, JsonMapper.builder().build(), "https://groq.test/v1", "m",
                Duration.ofMillis(1), null, Duration.ofMillis(200));

        var e = assertThrows(LlmUnavailableException.class, () -> waiting.completeJson("sys", "hello"));
        assertTrue(e.getMessage().contains("rate-limited"), e.getMessage());
    }

    private RestClient.Builder builderFor(MockRestServiceServer unused) {
        return lastBuilder;
    }
}
