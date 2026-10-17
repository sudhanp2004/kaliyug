package com.kaliyug.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyPoolTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void rotatesRoundRobin() throws Exception {
        var pool = new KeyPool(List.of("a", "b", "c"), clock);

        assertEquals(List.of("a", "b", "c", "a"), List.of(pool.acquire(), pool.acquire(), pool.acquire(), pool.acquire()));
    }

    @Test
    void skipsCoolingKey() throws Exception {
        var pool = new KeyPool(List.of("a", "b", "c"), clock);
        pool.coolDown("b", Duration.ofSeconds(30));

        assertEquals(List.of("a", "c", "a"), List.of(pool.acquire(), pool.acquire(), pool.acquire()));
    }

    @Test
    void allKeysCoolingWaitsForEarliest() throws Exception {
        var pool = new KeyPool(List.of("a", "b"), Clock.systemUTC());
        pool.coolDown("a", Duration.ofMillis(50));
        pool.coolDown("b", Duration.ofSeconds(5));

        long start = System.nanoTime();
        String key = pool.acquire();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals("a", key);
        assertTrue(elapsedMs >= 40 && elapsedMs < 2000, "waited " + elapsedMs + "ms");
    }

    @Test
    void acquireGivesUpAfterMaxWait() {
        var pool = new KeyPool(List.of("a", "b"), Clock.systemUTC());
        pool.coolDown("a", Duration.ofSeconds(5));
        pool.coolDown("b", Duration.ofSeconds(5));

        long start = System.nanoTime();
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
                () -> pool.acquire(Duration.ofMillis(100)));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 90 && elapsedMs < 2000, "gave up after " + elapsedMs + "ms");
    }
}
