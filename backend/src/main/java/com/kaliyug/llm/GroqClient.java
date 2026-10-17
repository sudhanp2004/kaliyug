package com.kaliyug.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    private final RestClient http;
    private final KeyPool keys;
    private final ObjectMapper mapper;
    private final String model;
    private final Duration backoffBase;

    @Autowired
    public GroqClient(RestClient.Builder builder, KeyPool keys, ObjectMapper mapper,
            @Value("${app.groq.base-url}") String baseUrl, @Value("${app.groq.model}") String model) {
        this(builder, keys, mapper, baseUrl, model, Duration.ofSeconds(2));
    }

    GroqClient(RestClient.Builder builder, KeyPool keys, ObjectMapper mapper, String baseUrl, String model, Duration backoffBase) {
        this.http = builder.baseUrl(baseUrl).build();
        this.keys = keys;
        this.mapper = mapper;
        this.model = model;
        this.backoffBase = backoffBase;
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
                throw new LlmUnavailableException("Groq rejected the request (HTTP " + e.getStatusCode().value() + ")");
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
            return keys.acquire();
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
