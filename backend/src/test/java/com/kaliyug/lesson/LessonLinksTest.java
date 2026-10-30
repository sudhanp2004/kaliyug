package com.kaliyug.lesson;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import com.kaliyug.domain.Chapter;
import com.kaliyug.domain.ChapterKind;
import com.kaliyug.domain.Lesson;
import com.kaliyug.domain.LessonRepository;
import com.kaliyug.domain.LessonStatus;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.PageText;
import com.kaliyug.domain.PageTextRepository;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.Resource;
import com.kaliyug.lesson.LessonContracts.Block;
import com.kaliyug.lesson.LessonContracts.LessonContent;
import com.kaliyug.lesson.LessonContracts.Part;
import com.kaliyug.llm.FakeLlm;
import com.kaliyug.llm.LlmUnavailableException;
import com.kaliyug.llm.TestLlmConfig;
import com.kaliyug.support.Db;
import com.kaliyug.support.Fixtures;
import com.kaliyug.support.TestClockConfig;

import static com.kaliyug.support.TestClockConfig.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestLlmConfig.class, Fixtures.class, TestClockConfig.class})
class LessonLinksTest {

    @Autowired MockMvc mvc;
    @Autowired LessonService service;
    @Autowired LessonRepository lessons;
    @Autowired FakeLlm llm;
    @Autowired Fixtures fx;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PageTextRepository pages;

    MapItem cur;
    MapItem a1;
    MapItem a2;
    MapItem b1;
    String linksA1;

    @BeforeEach
    void setUp() {
        Db.wipe(jdbc);
        llm.reset();
        service.setExecutor(Runnable::run);
        Resource bookA = fx.book("A");
        Chapter chA = fx.chapter(bookA, 1, "A ch", ChapterKind.CONCEPT);
        cur = fx.item(chA, 0, "Cache");
        a1 = learn(fx.item(chA, 1, "Replication"));
        a2 = learn(fx.item(chA, 2, "Load balancer"));
        fx.point(a1, PointKind.CONCEPT, "Replicas copy the primary database");
        Resource bookB = fx.book("B");
        b1 = fx.item(fx.chapter(bookB, 1, "B ch", ChapterKind.CONCEPT), 0, "Unlearned B section");

        Lesson lesson = new Lesson(cur);
        lesson.setStatus(LessonStatus.READY);
        lesson.setReadingMinutes(1);
        lesson.setContentJson(mapper.writeValueAsString(new LessonContent(
                List.of(new Block(new Part("A cache keeps hot data in memory.", 11, 11),
                        new Part("Check the cache first.", 11, 11), List.of())),
                List.of(), "", "cache", null)));
        lessons.save(lesson);
        linksA1 = "{\"links\":[{\"mapItemId\":" + a1.getId() + ",\"why\":\"Replication first\"}]}";
    }

    private MapItem learn(MapItem m) {
        m.setLearnedAt(NOW);
        return fx.save(m);
    }

    private void viewLinks() throws Exception {
        mvc.perform(get("/api/map-items/{id}/lesson", cur.getId()));
    }

    @Test
    void linksPickLearnedSectionsOnly() throws Exception {
        llm.reply("{\"links\":[{\"mapItemId\":" + a1.getId() + ",\"why\":\"Replication first\"},"
                + "{\"mapItemId\":" + b1.getId() + ",\"why\":\"x\"},{\"mapItemId\":" + cur.getId() + ",\"why\":\"x\"}]}");
        mvc.perform(get("/api/map-items/{id}/lesson", cur.getId()))
                .andExpect(jsonPath("$.buildsOn.length()").value(1))
                .andExpect(jsonPath("$.buildsOn[0].mapItemId").value(a1.getId()))
                .andExpect(jsonPath("$.buildsOn[0].why").value("Replication first"))
                .andExpect(jsonPath("$.buildsOn[0].bookTitle").value("A"));
        assertFalse(llm.calls.get(0)[1].contains("| " + b1.getTitle() + " |"));
        assertTrue(llm.calls.get(0)[1].contains("Replicas copy the primary database"));
    }

    @Test
    void noLearnedSectionsMeansNoLinksAndNoAiCall() throws Exception {
        for (MapItem m : List.of(a1, a2)) {
            m.setLearnedAt(null);
            fx.save(m);
        }
        mvc.perform(get("/api/map-items/{id}/lesson", cur.getId())).andExpect(jsonPath("$.buildsOn.length()").value(0));
        assertTrue(llm.calls.isEmpty());
    }

    @Test
    void linksRefreshOnlyWhenMoreSectionsAreLearned() throws Exception {
        llm.reply(linksA1);
        viewLinks();
        viewLinks();
        assertEquals(1, llm.calls.size());
        learn(b1);
        llm.reply(linksA1);
        viewLinks();
        assertEquals(2, llm.calls.size());
    }

    @Test
    void linksToRemovedSectionsAreHidden() throws Exception {
        llm.reply(linksA1);
        viewLinks();
        a1.setHidden(true);
        fx.save(a1);
        mvc.perform(get("/api/map-items/{id}/lesson", cur.getId())).andExpect(jsonPath("$.buildsOn.length()").value(0));
    }

    @Test
    void linksAiDownKeepsLessonReadableAndRetriesNextTime() throws Exception {
        llm.fail(new LlmUnavailableException("down"));
        mvc.perform(get("/api/map-items/{id}/lesson", cur.getId()))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.buildsOn.length()").value(0));
        llm.reply(linksA1);
        mvc.perform(get("/api/map-items/{id}/lesson", cur.getId())).andExpect(jsonPath("$.buildsOn.length()").value(1));
    }

    private static void runAll(List<Runnable> submitted) {
        while (!submitted.isEmpty()) {
            submitted.remove(0).run();
        }
    }

    @Test
    void viewDoesNotWaitForTheLinksAi() throws Exception {
        List<Runnable> submitted = new java.util.ArrayList<>();
        service.setExecutor(submitted::add);
        mvc.perform(get("/api/map-items/{id}/lesson", cur.getId()))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.buildsOn.length()").value(0));
        assertTrue(llm.calls.isEmpty());
        assertEquals(1, submitted.size());
        llm.reply(linksA1);
        runAll(submitted);
        mvc.perform(get("/api/map-items/{id}/lesson", cur.getId())).andExpect(jsonPath("$.buildsOn.length()").value(1));
        assertTrue(submitted.isEmpty());
    }

    @Test
    void linksArePickedWhenTheLessonIsGenerated() throws Exception {
        b1.setHidden(true);
        fx.save(b1);
        MapItem fresh = fx.item(cur.getChapter(), 3, "Cache eviction");
        pages.save(new PageText(cur.getChapter().getResource(), fresh.getStartPage(),
                "Least recently used is the most common eviction policy for caches. ".repeat(5)));
        List<Runnable> submitted = new java.util.ArrayList<>();
        service.setExecutor(submitted::add);
        mvc.perform(post("/api/map-items/{id}/lesson/open", fresh.getId()));
        llm.reply(LessonControllerTest.lessonJsonFor(mapper, fresh.getStartPage())).reply("{\"points\":[]}").reply(linksA1);
        runAll(submitted);
        mvc.perform(get("/api/map-items/{id}/lesson", fresh.getId()))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.buildsOn.length()").value(1));
        assertTrue(submitted.isEmpty());
    }
}
