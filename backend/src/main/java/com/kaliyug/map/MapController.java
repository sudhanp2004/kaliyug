package com.kaliyug.map;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kaliyug.map.QueueService.QueueDto;
import com.kaliyug.map.QueueService.QueueItemDto;

@RestController
public class MapController {

    private final QueueService queue;

    public MapController(QueueService queue) {
        this.queue = queue;
    }

    @GetMapping("/api/queue")
    public QueueDto queue(@RequestParam(defaultValue = "8") int limit) {
        return queue.queue(limit);
    }

    @PostMapping("/api/map-items/{id}/learned")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void learned(@PathVariable Long id) {
        queue.markLearned(id);
    }

    @PostMapping("/api/map-items/{id}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hide(@PathVariable Long id) {
        queue.setHidden(id, true);
    }

    @PostMapping("/api/map-items/{id}/unhide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unhide(@PathVariable Long id) {
        queue.setHidden(id, false);
    }

    @GetMapping("/api/map-items/hidden")
    public List<QueueItemDto> hidden() {
        return queue.hidden();
    }
}
