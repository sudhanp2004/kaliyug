package com.kaliyug.library;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.kaliyug.domain.MapItemRepository;
import com.kaliyug.domain.Resource;
import com.kaliyug.domain.ResourceRepository;
import com.kaliyug.domain.ResourceStatus;
import com.kaliyug.ingest.IngestionService;
import com.kaliyug.ingest.PdfBook;

/** Adding books: from the project folder or by upload. Each book is copied into the data folder and read in the background. */
@Service
public class LibraryService {

    public record ResourceDto(Long id, String title, ResourceStatus status, String failureReason, int totalPages,
            long itemsReady, long itemsTotal, boolean learningOn, boolean revisionOn) {
    }

    private static final int MAX_METADATA_TITLE = 60;

    private final ResourceRepository resources;
    private final MapItemRepository mapItems;
    private final IngestionService ingestion;
    private final Path booksDir;
    private final Path storeDir;
    private final Clock clock;
    /** SHA-256 of files in the books folder, keyed by path + size + modified time, so listing stays fast. */
    private final Map<String, String> hashCache = new ConcurrentHashMap<>();

    public LibraryService(ResourceRepository resources, MapItemRepository mapItems, IngestionService ingestion,
            @Value("${app.books-dir}") String booksDir, @Value("${app.data-dir}") String dataDir, Clock clock) {
        this.resources = resources;
        this.mapItems = mapItems;
        this.ingestion = ingestion;
        // Plain strings: Spring's Path conversion rejects "../x" inside a servlet container.
        this.booksDir = Path.of(booksDir);
        this.storeDir = Path.of(dataDir).resolve("books");
        this.clock = clock;
    }

    public List<ResourceDto> list() {
        return resources.findAll().stream()
                .map(r -> new ResourceDto(r.getId(), r.getTitle(), r.getStatus(), r.getFailureReason(), r.getTotalPages(),
                        mapItems.countByChapterResourceIdAndIngestedTrue(r.getId()), mapItems.countByChapterResourceId(r.getId()),
                        r.isLearningOn(), r.isRevisionOn()))
                .toList();
    }

    public List<String> available() {
        Set<String> imported = resources.findAll().stream().map(Resource::getSha256).collect(Collectors.toSet());
        try (Stream<Path> files = Files.list(booksDir)) {
            return files.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .filter(f -> !imported.contains(cachedHash(f)))
                    .map(f -> f.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ResourceDto importFromFolder(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException("Invalid file name.");
        }
        Path source = booksDir.resolve(fileName);
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf") || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("No such PDF in the project folder.");
        }
        return add(source, fileName);
    }

    public ResourceDto upload(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : Path.of(file.getOriginalFilename()).getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF files can be added.");
        }
        try {
            Files.createDirectories(storeDir);
            Path temp = Files.createTempFile(storeDir, "upload-", ".pdf");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                return add(temp, name);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void setLearning(Long id, boolean on) {
        Resource r = find(id);
        r.setLearningOn(on);
        resources.save(r);
    }

    public void setRevision(Long id, boolean on) {
        Resource r = find(id);
        r.setRevisionOn(on);
        resources.save(r);
    }

    private Resource find(Long id) {
        return resources.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such book"));
    }

    public void retry(Long id) {
        Resource r = find(id);
        if (r.getStatus() != ResourceStatus.FAILED) {
            throw new IllegalArgumentException("Only a book that failed can be retried.");
        }
        r.setStatus(ResourceStatus.INGESTING);
        r.setFailureReason(null);
        resources.save(r);
        ingestion.start(id);
    }

    private ResourceDto add(Path source, String fileName) {
        String sha = sha256(source);
        if (resources.findBySha256(sha).isPresent()) {
            throw new DuplicateResourceException("This book is already in your library.");
        }
        try {
            Files.createDirectories(storeDir);
            Path stored = storeDir.resolve(sha + ".pdf");
            Files.copy(source, stored, StandardCopyOption.REPLACE_EXISTING);
            Resource r = resources.save(new Resource(title(stored, fileName), stored.toString(), sha, clock.instant()));
            ingestion.start(r.getId());
            return new ResourceDto(r.getId(), r.getTitle(), r.getStatus(), r.getFailureReason(), r.getTotalPages(), 0, 0,
                    r.isLearningOn(), r.isRevisionOn());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The PDF's own title when it is short enough to read at a glance, otherwise the file name. */
    private static String title(Path pdf, String fileName) {
        String fallback = fileName.replaceFirst("(?i)\\.pdf$", "");
        try (PdfBook book = PdfBook.open(pdf)) {
            String meta = book.title();
            return meta != null && meta.length() <= MAX_METADATA_TITLE ? meta : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private String cachedHash(Path file) {
        try {
            String key = file.toAbsolutePath() + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis();
            return hashCache.computeIfAbsent(key, k -> sha256(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(Path file) {
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
