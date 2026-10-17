package com.kaliyug.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Test double: replays canned replies (or throws canned exceptions) in call order and records every call. */
public class FakeLlm implements LlmClient {

    public final List<String[]> calls = new ArrayList<>();
    private final Deque<Object> replies = new ArrayDeque<>();

    public FakeLlm reply(String json) {
        replies.add(json);
        return this;
    }

    public FakeLlm fail(RuntimeException e) {
        replies.add(e);
        return this;
    }

    public FakeLlm failTimes(int times, RuntimeException e) {
        for (int i = 0; i < times; i++) {
            replies.add(e);
        }
        return this;
    }

    public void reset() {
        calls.clear();
        replies.clear();
    }

    @Override
    public String completeJson(String system, String user) {
        calls.add(new String[] {system, user});
        Object next = replies.poll();
        if (next == null) {
            throw new IllegalStateException("FakeLlm has no reply queued for call #" + calls.size() + ": " + user.substring(0, Math.min(120, user.length())));
        }
        if (next instanceof RuntimeException e) {
            throw e;
        }
        return (String) next;
    }
}
