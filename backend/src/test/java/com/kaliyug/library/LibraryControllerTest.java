package com.kaliyug.library;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.kaliyug.domain.Resource;
import com.kaliyug.domain.ResourceRepository;
import com.kaliyug.domain.ResourceStatus;
import com.kaliyug.ingest.IngestionService;
import com.kaliyug.ingest.TestPdfs;
import com.kaliyug.llm.TestLlmConfig;
import com.kaliyug.support.Db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestLlmConfig.class)
class LibraryControllerTest {

    @TempDir
    static Path booksDir;
    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        registry.add("app.books-dir", booksDir::toString);
        registry.add("app.data-dir", dataDir::toString);
    }

    @BeforeAll
    static void books() {
        TestPdfs.outlined(booksDir.resolve("Fixture Book.pdf"));
        TestPdfs.withoutBookmarks(booksDir.resolve("Plain.pdf"));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ResourceRepository resources;
    @MockitoSpyBean IngestionService ingestion;

    @BeforeEach
    void setUp() {
        Db.wipe(jdbc);
        doNothing().when(ingestion).start(any());
    }

    private ResultActions importBook(String fileName) throws Exception {
        return mvc.perform(post("/api/resources/import")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fileName\":\"" + fileName + "\"}"));
    }

    @Test
    void availableListsUnimportedPdfs() throws Exception {
        mvc.perform(get("/api/resources/available"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("Fixture Book.pdf"))
                .andExpect(jsonPath("$[1]").value("Plain.pdf"));
    }

    @Test
    void importStartsIngestionAndHidesFromAvailable() throws Exception {
        importBook("Fixture Book.pdf")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Fixture Book"))
                .andExpect(jsonPath("$.status").value("INGESTING"));

        Resource saved = resources.findAll().get(0);
        verify(ingestion).start(saved.getId());
        assertTrue(Files.exists(Path.of(saved.getStoredPath())));
        assertTrue(saved.getStoredPath().startsWith(dataDir.resolve("books").toString()));
        mvc.perform(get("/api/resources/available"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value("Plain.pdf"));
        mvc.perform(get("/api/resources"))
                .andExpect(jsonPath("$[0].title").value("Fixture Book"))
                .andExpect(jsonPath("$[0].itemsTotal").value(0));
    }

    @Test
    void duplicateImportReturns409() throws Exception {
        importBook("Fixture Book.pdf").andExpect(status().isCreated());

        importBook("Fixture Book.pdf")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("This book is already in your library."));
    }

    @Test
    void pathTraversalRejected() throws Exception {
        importBook("../x.pdf").andExpect(status().isBadRequest());
        importBook("Missing.pdf").andExpect(status().isBadRequest());
        assertEquals(0, resources.count());
    }

    @Test
    void uploadAcceptsPdfOnly() throws Exception {
        byte[] pdf = Files.readAllBytes(booksDir.resolve("Plain.pdf"));
        mvc.perform(multipart("/api/resources").file(new MockMultipartFile("file", "Plain.pdf", "application/pdf", pdf)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INGESTING"));
        mvc.perform(multipart("/api/resources").file(new MockMultipartFile("file", "notes.txt", "text/plain", "hi".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importOfPdfWithoutBookmarksShowsFailedReason() throws Exception {
        doAnswer(inv -> {
            ingestion.run(inv.getArgument(0));
            return null;
        }).when(ingestion).start(any());

        importBook("Plain.pdf").andExpect(status().isCreated());

        mvc.perform(get("/api/resources"))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].failureReason").value(org.hamcrest.Matchers.startsWith("This PDF has no bookmarks")));
    }

    @Test
    void retryRestartsFailedResource() throws Exception {
        importBook("Fixture Book.pdf").andExpect(status().isCreated());
        Resource r = resources.findAll().get(0);
        mvc.perform(post("/api/resources/{id}/retry", r.getId())).andExpect(status().isBadRequest());

        r.setStatus(ResourceStatus.FAILED);
        r.setFailureReason("AI service unreachable — press Retry in Library.");
        resources.save(r);

        mvc.perform(post("/api/resources/{id}/retry", r.getId())).andExpect(status().isNoContent());

        Resource after = resources.findById(r.getId()).orElseThrow();
        assertEquals(ResourceStatus.INGESTING, after.getStatus());
        assertEquals(null, after.getFailureReason());
        verify(ingestion, org.mockito.Mockito.times(2)).start(r.getId());
    }

    @Test
    void newBookStartsWithLearningOffAndRevisionOn() throws Exception {
        importBook("Fixture Book.pdf")
                .andExpect(jsonPath("$.learningOn").value(false))
                .andExpect(jsonPath("$.revisionOn").value(true));
    }

    @Test
    void switchesTurnLearningAndRevisionOnAndOff() throws Exception {
        importBook("Fixture Book.pdf");
        Long id = resources.findAll().get(0).getId();

        mvc.perform(post("/api/resources/{id}/learning", id).contentType(MediaType.APPLICATION_JSON).content("{\"on\":true}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/resources/{id}/revision", id).contentType(MediaType.APPLICATION_JSON).content("{\"on\":false}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/resources"))
                .andExpect(jsonPath("$[0].learningOn").value(true))
                .andExpect(jsonPath("$[0].revisionOn").value(false));
    }
}
