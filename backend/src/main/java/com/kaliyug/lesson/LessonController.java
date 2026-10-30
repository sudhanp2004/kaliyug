package com.kaliyug.lesson;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kaliyug.lesson.LessonService.LessonDto;

@RestController
public class LessonController {

    private final LessonService lessons;

    public LessonController(LessonService lessons) {
        this.lessons = lessons;
    }

    @PostMapping("/api/map-items/{id}/lesson/open")
    public LessonDto open(@PathVariable Long id) {
        return lessons.open(id);
    }

    @GetMapping("/api/map-items/{id}/lesson")
    public LessonDto view(@PathVariable Long id) {
        return lessons.view(id);
    }

    @PostMapping("/api/map-items/{id}/lesson/rewrite")
    public LessonDto rewrite(@PathVariable Long id) {
        return lessons.rewrite(id);
    }

    @PostMapping("/api/lessons/prepare-upcoming")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void prepareUpcoming() {
        lessons.prepareUpcoming();
    }
}
