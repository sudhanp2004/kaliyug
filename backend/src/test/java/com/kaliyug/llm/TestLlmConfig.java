package com.kaliyug.llm;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the real Groq client with a FakeLlm in Spring tests. Import it so test contexts are shared. */
@TestConfiguration
public class TestLlmConfig {

    @Bean
    @Primary
    public FakeLlm fakeLlm() {
        return new FakeLlm();
    }
}
