package com.kaliyug.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Hands out API keys round-robin, skipping keys that are cooling down after a rate limit. */
public class KeyPool {

    private final List<String> keys;
    private final Clock clock;
    private final Map<String, Instant> coolingUntil = new HashMap<>();
    private int next;

    public KeyPool(List<String> keys, Clock clock) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("KeyPool needs at least one key");
        }
        this.keys = List.copyOf(keys);
        this.clock = clock;
    }

    public synchronized String acquire() throws InterruptedException {
        while (true) {
            Instant now = clock.instant();
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get((next + i) % keys.size());
                Instant until = coolingUntil.get(key);
                if (until == null || !until.isAfter(now)) {
                    next = (next + i + 1) % keys.size();
                    return key;
                }
            }
            Instant earliest = coolingUntil.values().stream().min(Instant::compareTo).orElse(now);
            wait(Math.max(1, Duration.between(now, earliest).toMillis()));
        }
    }

    public synchronized void coolDown(String key, Duration duration) {
        coolingUntil.put(key, clock.instant().plus(duration));
    }

    public int size() {
        return keys.size();
    }
}
