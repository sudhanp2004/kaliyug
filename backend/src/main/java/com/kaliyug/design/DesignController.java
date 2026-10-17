package com.kaliyug.design;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.kaliyug.design.DesignService.DesignResultDto;
import com.kaliyug.design.DesignService.DesignSubmission;
import com.kaliyug.design.DesignService.ProblemDto;

@RestController
public class DesignController {

    private final DesignService design;

    public DesignController(DesignService design) {
        this.design = design;
    }

    @GetMapping("/api/problems/{id}")
    public ProblemDto problem(@PathVariable Long id) {
        return design.problem(id);
    }

    @PostMapping("/api/problems/{id}/submit")
    public DesignResultDto submit(@PathVariable Long id, @RequestBody DesignSubmission submission) {
        return design.submit(id, submission);
    }
}
