package com.kaliyug.library;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.kaliyug.library.LibraryService.ResourceDto;

@RestController
public class LibraryController {

    public record ImportRequest(String fileName) {
    }

    private final LibraryService library;

    public LibraryController(LibraryService library) {
        this.library = library;
    }

    @GetMapping("/api/resources")
    public List<ResourceDto> list() {
        return library.list();
    }

    @GetMapping("/api/resources/available")
    public List<String> available() {
        return library.available();
    }

    @PostMapping("/api/resources/import")
    @ResponseStatus(HttpStatus.CREATED)
    public ResourceDto importFromFolder(@RequestBody ImportRequest body) {
        return library.importFromFolder(body.fileName());
    }

    @PostMapping("/api/resources")
    @ResponseStatus(HttpStatus.CREATED)
    public ResourceDto upload(@RequestParam("file") MultipartFile file) {
        return library.upload(file);
    }

    @PostMapping("/api/resources/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retry(@PathVariable Long id) {
        library.retry(id);
    }
}
