package com.kaliyug.llm;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Groq's OpenAI-compatible chat completions API, rotating across the configured keys. */
@Component
public class GroqClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GroqClient.class);
    private static final int MAX_RETRIES = 3;
    private static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** A lost connection (e.g. laptop lid closed mid-request) surfaces as a timeout instead of hanging forever. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);
    /** How long a call waits for a key when all of them are rate-limited, so grading can't spin forever. */
    private static final Duration MAX_KEY_WAIT = Duration.ofSeconds(30);

    private final RestClient http;
    private final KeyPool keys;
    private final ObjectMapper mapper;
    private final String model;
    private final Duration backoffBase;
    private final Duration maxKeyWait;

    @Autowired
    public GroqClient(KeyPool keys, ObjectMapper mapper,
            @Value("${app.groq.base-url}") String baseUrl, @Value("${app.groq.model}") String model) {
        this(RestClient.builder(), keys, mapper, baseUrl, model, Duration.ofSeconds(2), READ_TIMEOUT, MAX_KEY_WAIT);
    }

    GroqClient(RestClient.Builder builder, KeyPool keys, ObjectMapper mapper, String baseUrl, String model, Duration backoffBase) {
        this(builder, keys, mapper, baseUrl, model, backoffBase, null, MAX_KEY_WAIT);
    }

    /** A null readTimeout keeps the builder's own request factory (tests bind a mock server to it). */
    GroqClient(RestClient.Builder builder, KeyPool keys, ObjectMapper mapper, String baseUrl, String model,
            Duration backoffBase, Duration readTimeout, Duration maxKeyWait) {
        if (readTimeout != null) {
            JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
            requests.setReadTimeout(readTimeout);
            builder.requestFactory(requests);
        }
        this.http = builder.baseUrl(baseUrl).build();
        this.keys = keys;
        this.mapper = mapper;
        this.model = model;
        this.backoffBase = backoffBase;
        this.maxKeyWait = maxKeyWait;
    }

    @Override
    public String completeJson(String system, String user) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0.2);

        int failures = 0;
        while (true) {
            String key = acquireKey();
            try {
                String raw = http.post().uri("/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return extractContent(raw);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
                    keys.coolDown(key, retryAfter(e.getResponseHeaders()));
                    continue;
                }
                String code = errorCode(e.getResponseBodyAsString());
                if ("json_validate_failed".equals(code) && ++failures <= MAX_RETRIES) {
                    log.warn("Groq could not produce valid JSON, retry {}/{}", failures, MAX_RETRIES);
                    continue;
                }
                throw new LlmUnavailableException("Groq rejected the request (HTTP " + e.getStatusCode().value()
                        + (code == null ? "" : ", " + code) + ")");
            } catch (HttpServerErrorException | ResourceAccessException e) {
                failures++;
                if (failures > MAX_RETRIES) {
                    throw new LlmUnavailableException("Groq unreachable after " + MAX_RETRIES + " retries: " + e.getClass().getSimpleName());
                }
                log.warn("Groq call failed ({}), retry {}/{}", e.getClass().getSimpleName(), failures, MAX_RETRIES);
                sleep(backoffBase.multipliedBy(1L << (failures - 1)));
            }
        }
    }

    private String extractContent(String raw) {
        JsonNode content = mapper.readTree(raw).path("choices").path(0).path("message").path("content");
        if (!content.isString()) {
            throw new LlmUnavailableException("Groq reply had no message content");
        }
        return content.asString();
    }

    /** Groq's machine-readable error code, e.g. "json_validate_failed". Never contains the key. */
    private String errorCode(String body) {
        try {
            JsonNode code = mapper.readTree(body).path("error").path("code");
            return code.isString() ? code.asString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Duration retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst("retry-after");
        if (value != null) {
            try {
                return Duration.ofMillis((long) (Double.parseDouble(value.trim()) * 1000));
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return DEFAULT_COOLDOWN;
    }

    private String acquireKey() {
        try {
            return keys.acquire(maxKeyWait);
        } catch (TimeoutException e) {
            throw new LlmUnavailableException("All API keys are rate-limited right now");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("Interrupted while waiting for an API key", e);
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("Interrupted while retrying", e);
        }
    }
}
