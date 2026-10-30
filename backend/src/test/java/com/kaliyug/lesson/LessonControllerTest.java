package com.kaliyug.lesson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.PageText;
import com.kaliyug.domain.PageTextRepository;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.domain.QuestionRepository;
import com.kaliyug.domain.QuestionType;
import com.kaliyug.domain.Resource;
import com.kaliyug.llm.FakeLlm;
import com.kaliyug.llm.LlmUnavailableException;
import com.kaliyug.llm.TestLlmConfig;
import com.kaliyug.support.Db;
import com.kaliyug.support.Fixtures;
import com.kaliyug.support.TestClockConfig;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestLlmConfig.class, Fixtures.class, TestClockConfig.class})
class LessonControllerTest {

    static final String COVERAGE_NONE = "{\"points\":[]}";
    static final Pattern PAGES = Pattern.compile("\\(pages (\\d+)-");

    @Autowired MockMvc mvc;
    @Autowired LessonService service;
    @Autowired LessonRepository lessons;
    @Autowired FakeLlm llm;
    @Autowired Fixtures fx;
    @Autowired PageTextRepository pages;
    @Autowired PointRepository points;
    @Autowired QuestionRepository questions;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    MapItem i1;
    MapItem i2;
    MapItem i3;
    MapItem i4;

    @BeforeEach
    void setUp() {
        Db.wipe(jdbc);
        llm.reset();
        service.setExecutor(Runnable::run);
        Resource book = fx.book("System Design");
        Chapter ch = fx.chapter(book, 1, "Scale", ChapterKind.CONCEPT);
        List<MapItem> items = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            pages.save(new PageText(book, 11 + k, ("Section " + k + " explains caching and how servers share load. ").repeat(6)));
            MapItem m = fx.item(ch, k, "Section " + k);
            for (int j = 0; j < 2; j++) {
                Point p = fx.point(m, PointKind.CONCEPT, "Point " + k + "." + j);
                fx.question(p, QuestionType.SHORT, "Question " + k + "." + j + "?", "Answer.");
            }
            items.add(m);
        }
        i1 = items.get(0);
        i2 = items.get(1);
        i3 = items.get(2);
        i4 = items.get(3);
    }

    static int pageIn(String user) {
        Matcher m = PAGES.matcher(user);
        if (!m.find()) {
            throw new IllegalStateException("no page range in: " + user);
        }
        return Integer.parseInt(m.group(1));
    }

    private Map<String, Object> part(String text, int page) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        m.put("firstPage", page);
        m.put("lastPage", page);
        return m;
    }

    String lessonJsonWithIdea(String idea, int page) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idea", part(idea, page));
        m.put("howItWorks", part("Check the cache first.", page));
        m.put("remember", List.of());
        m.put("diagrams", List.of());
        m.put("leftOut", "");
        m.put("youtubeQuery", "cache eviction");
        m.put("miniDesign", null);
        return mapper.writeValueAsString(m);
    }

    String lessonJson(int page) {
        return lessonJsonWithIdea("A cache keeps hot data in memory.", page);
    }

    void queueLesson() {
        llm.replyWith(u -> lessonJson(pageIn(u)));
        llm.reply(COVERAGE_NONE);
    }

    void hideAllBut(MapItem keep) {
        for (MapItem m : List.of(i2, i3, i4)) {
            if (!m.getId().equals(keep.getId())) {
                m.setHidden(true);
                fx.save(m);
            }
        }
    }

    @Test
    void openStartsGenerationAndReturnsReadyLesson() throws Exception {
        for (int i = 0; i < 3; i++) {
            queueLesson();
        }
        mvc.perform(post("/api/map-items/{id}/lesson/open", i1.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.blocks.length()").value(1))
                .andExpect(jsonPath("$.youtubeUrl").value("https://www.youtube.com/results?search_query=cache+eviction"))
                .andExpect(jsonPath("$.quickCheck.length()").value(2));
    }

    @Test
    void openPreparesTheNextTwoUpNextSections() throws Exception {
        for (int i = 0; i < 3; i++) {
            queueLesson();
        }
        mvc.perform(post("/api/map-items/{id}/lesson/open", i1.getId()));
        assertTrue(lessons.findByMapItemId(i2.getId()).isPresent());
        assertTrue(lessons.findByMapItemId(i3.getId()).isPresent());
        assertTrue(lessons.findByMapItemId(i4.getId()).isEmpty());
    }

    @Test
    void openTwiceGeneratesOnce() throws Exception {
        List<Runnable> submitted = new ArrayList<>();
        service.setExecutor(submitted::add);
        hideAllBut(i1);
        mvc.perform(post("/api/map-items/{id}/lesson/open", i1.getId())).andExpect(jsonPath("$.status").value("PREPARING"));
        mvc.perform(post("/api/map-items/{id}/lesson/open", i1.getId()));
        assertEquals(1, submitted.size());
    }

    @Test
    void preparingLessonWithNothingRunningIsRestartedOnOpen() throws Exception {
        lessons.save(new Lesson(i1));
        hideAllBut(i1);
        queueLesson();
        mvc.perform(post("/api/map-items/{id}/lesson/open", i1.getId())).andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void failedLessonIsRetriedOnOpenButNotOnGet() throws Exception {
        hideAllBut(i1);
        llm.fail(new LlmUnavailableException("down"));
        mvc.perform(post("/api/map-items/{id}/lesson/open", i1.getId()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value(LessonGenerator.AI_UNREACHABLE));
        int calls = llm.calls.size();
        mvc.perform(get("/api/map-items/{id}/lesson", i1.getId())).andExpect(jsonPath("$.status").value("FAILED"));
        assertEquals(calls, llm.calls.size());
        queueLesson();
        mvc.perform(post("/api/map-items/{id}/lesson/open", i1.getId())).andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void missingLessonAndRemovedSectionAre404() throws Exception {
        mvc.perform(get("/api/map-items/{id}/lesson", i1.getId())).andExpect(status().isNotFound());
        i2.setHidden(true);
        fx.save(i2);
        mvc.perform(post("/api/map-items/{id}/lesson/open", i2.getId())).andExpect(status().isNotFound());
    }

    @Test
    void rewriteReplacesContent() throws Exception {
        hideAllBut(i1);
        queueLesson();
        mvc.perform(post("/api/map-items/{id}/lesson/open", i1.getId()));
        llm.reply(lessonJsonWithIdea("Rewritten idea", 11)).reply(COVERAGE_NONE);
        mvc.perform(post("/api/map-items/{id}/lesson/rewrite", i1.getId()))
                .andExpect(jsonPath("$.blocks[0].idea.text").value("Rewritten idea"));
    }

    @Test
    void quickCheckSkipsPointsWithoutActiveQuestions() throws Exception {
        hideAllBut(i1);
        queueLesson();
        for (int k = 0; k < 3; k++) {
            fx.question(fx.point(i1, PointKind.CONCEPT, "extra " + k), QuestionType.SHORT, "Q" + k, "A" + k);
        }
        Point retiredPoint = points.findByMapItemId(i1.getId()).get(1);
        questions.findByPointIdAndRetiredFalseOrderByLastAskedAtAscIdAsc(retiredPoint.getId()).forEach(q -> {
            q.setRetired(true);
            questions.save(q);
        });
        mvc.perform(post("/api/map-items/{id}/lesson/open", i1.getId()))
                .andExpect(jsonPath("$.quickCheck.length()").value(3))
                .andExpect(jsonPath("$.quickCheck[*].pointId", not(hasItem(retiredPoint.getId().intValue()))));
    }

    @Test
    void prepareUpcomingPreparesTopTwo() throws Exception {
        queueLesson();
        queueLesson();
        mvc.perform(post("/api/lessons/prepare-upcoming")).andExpect(status().isNoContent());
        assertTrue(lessons.findByMapItemId(i1.getId()).isPresent());
        assertTrue(lessons.findByMapItemId(i2.getId()).isPresent());
        assertTrue(lessons.findByMapItemId(i3.getId()).isEmpty());
    }
}
