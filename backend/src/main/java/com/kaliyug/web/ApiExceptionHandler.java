package com.kaliyug.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.kaliyug.library.DuplicateResourceException;
import com.kaliyug.llm.LlmUnavailableException;

/** Turns expected failures into {"error": "..."} responses the UI can show as-is. */
@RestControllerAdvice
public class ApiExceptionHandler {

    static final String AI_DOWN = "AI is unreachable right now — your answer wasn't graded, try again.";

    @ExceptionHandler(LlmUnavailableException.class)
    ResponseEntity<Map<String, String>> aiDown(LlmUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", AI_DOWN));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    ResponseEntity<Map<String, String>> duplicate(DuplicateResourceException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
