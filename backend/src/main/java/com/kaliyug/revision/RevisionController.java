package com.kaliyug.revision;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kaliyug.revision.RevisionService.GradeResultDto;
import com.kaliyug.revision.RevisionService.RevisionItemDto;

@RestController
public class RevisionController {

    public record AnswerRequest(String answer) {
    }

    private final RevisionService revision;

    public RevisionController(RevisionService revision) {
        this.revision = revision;
    }

    @GetMapping("/api/revision/next")
    public RevisionItemDto next(@RequestParam(defaultValue = "0") int answered,
            @RequestParam(required = false) Set<Long> exclude) {
        return revision.next(answered, exclude == null ? Set.of() : exclude);
    }

    @PostMapping("/api/questions/{id}/answer")
    public GradeResultDto answer(@PathVariable Long id, @RequestBody AnswerRequest body) {
        return revision.answer(id, body.answer());
    }

    @PostMapping("/api/attempts/{id}/override")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void override(@PathVariable Long id) {
        revision.override(id);
    }

    @PostMapping("/api/questions/{id}/flag")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void flag(@PathVariable Long id) {
        revision.flag(id);
    }
}
