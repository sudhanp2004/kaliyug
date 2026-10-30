package com.kaliyug.lesson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import com.kaliyug.domain.Chapter;
import com.kaliyug.domain.ChapterKind;
import com.kaliyug.domain.Lesson;
import com.kaliyug.domain.LessonRepository;
import com.kaliyug.domain.LessonStatus;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.PageText;
import com.kaliyug.domain.PageTextRepository;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.domain.QuestionType;
import com.kaliyug.domain.Resource;
import com.kaliyug.ingest.IngestionService;
import com.kaliyug.lesson.LessonContracts.LessonContent;
import com.kaliyug.lesson.LessonContracts.Remember;
import com.kaliyug.llm.FakeLlm;
import com.kaliyug.llm.LlmUnavailableException;
import com.kaliyug.llm.TestLlmConfig;
import com.kaliyug.support.Db;
import com.kaliyug.support.Fixtures;
import com.kaliyug.support.TestClockConfig;

import static com.kaliyug.support.TestClockConfig.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import({TestLlmConfig.class, Fixtures.class, TestClockConfig.class})
class LessonGeneratorTest {

    static final String TEXT = "A cache keeps the results of frequent reads in memory so later requests skip the database. "
            + "Reading from memory is much faster than reading from disk. "
            + "Least recently used is the most common eviction policy. "
            + "A cache server is a single point of failure unless several are used. "
            + "Set an expiry so cached data does not go stale.";
    static final String COVERAGE_NONE = "{\"points\":[]}";

    @Autowired LessonGenerator generator;
    @Autowired FakeLlm llm;
    @Autowired Fixtures fx;
    @Autowired LessonRepository lessons;
    @Autowired PointRepository points;
    @Autowired PageTextRepository pages;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    MapItem item;
    MapItem otherItem;
    MapItem longItem;
    MapItem emptyItem;
    Point p1;

    @BeforeEach
    void setUp() {
        Db.wipe(jdbc);
        llm.reset();
        generator.setMaxInputChars(IngestionService.DEFAULT_MAX_INPUT_CHARS);

        Resource book = fx.book("System Design");
        Chapter ch = fx.chapter(book, 1, "Scale", ChapterKind.CONCEPT);
        pages.save(new PageText(book, 11, TEXT));
        pages.save(new PageText(book, 12, "Other section text."));
        item = fx.item(ch, 0, "Cache");
        otherItem = fx.item(ch, 1, "Other");
        p1 = fx.point(item, PointKind.CONCEPT, "Caches keep hot data in memory");
        fx.question(p1, QuestionType.SHORT, "Why cache?", "Speed.");
        lessons.save(new Lesson(item));

        Resource longBook = fx.book("Long");
        Chapter longCh = fx.chapter(longBook, 1, "Long ch", ChapterKind.CONCEPT);
        for (int n = 11; n <= 13; n++) {
            pages.save(new PageText(longBook, n, "Long page sentence about caching. ".repeat(59)));
        }
        longItem = fx.save(withIngested(new MapItem(longCh, 0, "Long section", 11, 13)));
        lessons.save(new Lesson(longItem));

        Resource emptyBook = fx.book("Figures");
        Chapter emptyCh = fx.chapter(emptyBook, 1, "Figures ch", ChapterKind.CONCEPT);
        pages.save(new PageText(emptyBook, 11, "Figure 1-3"));
        emptyItem = fx.item(emptyCh, 0, "Figures only");
        lessons.save(new Lesson(emptyItem));
    }

    private static MapItem withIngested(MapItem m) {
        m.setIngested(true);
        return m;
    }

    // ---- reply builders

    private Map<String, Object> part(String text, int page) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        m.put("firstPage", page);
        m.put("lastPage", page);
        return m;
    }

    private Map<String, Object> remember(String text, int page, String evidence) {
        Map<String, Object> m = part(text, page);
        m.put("evidence", evidence);
        return m;
    }

    private String lesson(int page, List<Map<String, Object>> remember, List<String> diagrams, Map<String, Object> miniDesign) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idea", part("A cache keeps hot data in memory.", page));
        m.put("howItWorks", part("Check the cache first; on a miss read the database and store a copy.", page));
        m.put("remember", remember);
        m.put("diagrams", diagrams);
        m.put("leftOut", "Left out: the code snippet.");
        m.put("youtubeQuery", "cache read through");
        m.put("miniDesign", miniDesign);
        return mapper.writeValueAsString(m);
    }

    private String lessonJson(int page, List<Map<String, Object>> remember) {
        return lesson(page, remember, List.of(), null);
    }

    private String lessonJsonWithTwoDiagrams(int page) {
        return lesson(page, List.of(), List.of("flowchart LR\n  A --> B", "flowchart LR\n  C --> D"), null);
    }

    private String lessonJsonWithMiniDesign(List<Long> pointIds) {
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("task", "Add a cache in front of the database.");
        md.put("requirements", List.of("Reads are fast"));
        md.put("pointIds", pointIds);
        return lesson(11, List.of(), List.of(), md);
    }

    private Map<String, Object> point(String text, int page, String evidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "GOTCHA");
        m.put("text", text);
        m.put("firstPage", page);
        m.put("lastPage", page);
        m.put("evidence", evidence);
        m.put("questions", List.of(Map.of("type", "SHORT", "prompt", "Why several cache servers?", "modelAnswer", "No SPOF.")));
        return m;
    }

    @SafeVarargs
    private String coverage(Map<String, Object>... pts) {
        return mapper.writeValueAsString(Map.of("points", new ArrayList<>(List.of(pts))));
    }

    private Lesson lessonOf(MapItem m) {
        return lessons.findByMapItemId(m.getId()).orElseThrow();
    }

    private Lesson lesson() {
        return lessonOf(item);
    }

    private LessonContent read(Lesson l) {
        return mapper.readValue(l.getContentJson(), LessonContent.class);
    }

    // ---- tests

    @Test
    void buildsGroundedLessonAndDropsUnquotedRememberItems() {
        llm.reply(lessonJson(11, List.of(
                remember("LRU is most common", 11, "Least recently used is the most common eviction policy."),
                remember("Invented", 11, "Caches never lose data under any circumstances at all."))));
        llm.reply(COVERAGE_NONE);
        generator.generate(item.getId());
        Lesson l = lesson();
        assertEquals(LessonStatus.READY, l.getStatus());
        LessonContent c = read(l);
        assertEquals(List.of("LRU is most common"), c.blocks().get(0).remember().stream().map(Remember::text).toList());
        assertTrue(l.getReadingMinutes() >= 1);
        assertTrue(llm.calls.get(0)[1].contains(p1.getId() + " | CONCEPT | "));
    }

    @Test
    void narrativeOutsideSectionIsRetriedThenFails() {
        llm.reply(lessonJson(99, List.of())).reply(lessonJson(99, List.of()));
        generator.generate(item.getId());
        Lesson l = lesson();
        assertEquals(LessonStatus.FAILED, l.getStatus());
        assertEquals(LessonGenerator.AI_UNUSABLE, l.getFailureReason());
        assertEquals(2, llm.calls.size());
        assertTrue(llm.calls.get(1)[1].contains("pages must be within 11-11"));
    }

    @Test
    void coverageAddsGroundedPointsWithQuestionsToRevisionAndLesson() {
        llm.reply(lessonJson(11, List.of()));
        llm.reply(coverage(
                point("Single cache server is a SPOF", 11, "A cache server is a single point of failure unless several are used."),
                point("Invented", 11, "Nothing like this sentence is in the section text.")));
        generator.generate(item.getId());
        List<Point> all = points.findByMapItemId(item.getId());
        assertEquals(2, all.size());
        Point added = all.stream().filter(p -> p.getText().startsWith("Single")).findFirst().orElseThrow();
        assertNull(added.getDueAt());
        assertEquals(0, lesson().getNewPointsFound());
        assertTrue(read(lesson()).blocks().get(0).remember().stream().anyMatch(r -> r.text().startsWith("Single")));
    }

    @Test
    void newPointsInLearnedSectionAreDueNowAndAnnounced() {
        item.setLearnedAt(NOW.minusSeconds(3600));
        fx.save(item);
        llm.reply(lessonJson(11, List.of()));
        llm.reply(coverage(point("Single cache server is a SPOF", 11, "A cache server is a single point of failure unless several are used.")));
        generator.generate(item.getId());
        Point added = points.findByMapItemId(item.getId()).stream()
                .filter(p -> p.getText().startsWith("Single")).findFirst().orElseThrow();
        assertEquals(NOW, added.getDueAt());
        assertEquals(1, lesson().getNewPointsFound());
    }

    @Test
    void longSectionBecomesOneBlockPerChunkWithinBudget() {
        // pages 11-13, about 2,000 chars each; maxInputChars 4,000 -> page budget 2,750 -> 3 chunks
        generator.setMaxInputChars(4_000);
        for (int i = 0; i < 3; i++) {
            llm.reply(lessonJsonWithTwoDiagrams(11 + i));
            llm.reply(COVERAGE_NONE);
        }
        generator.generate(longItem.getId());
        LessonContent c = read(lessonOf(longItem));
        assertEquals(3, c.blocks().size());
        assertEquals(2, c.diagrams().size());
        assertTrue(llm.calls.stream().allMatch(call -> call[1].length() <= 4_000));
    }

    @Test
    void miniDesignKeepsOnlyThisSectionsPoints() {
        Point foreign = fx.point(otherItem, PointKind.CONCEPT, "elsewhere");
        llm.reply(lessonJsonWithMiniDesign(List.of(p1.getId(), foreign.getId(), 999L, p1.getId())));
        llm.reply(COVERAGE_NONE);
        generator.generate(item.getId());
        assertEquals(List.of(p1.getId()), read(lesson()).miniDesign().pointIds());
    }

    @Test
    void sectionWithAlmostNoTextFailsHonestlyWithoutAi() {
        generator.generate(emptyItem.getId());
        assertEquals(LessonStatus.FAILED, lessonOf(emptyItem).getStatus());
        assertEquals(LessonGenerator.TOO_LITTLE_TEXT, lessonOf(emptyItem).getFailureReason());
        assertTrue(llm.calls.isEmpty());
    }

    @Test
    void aiDownMarksLessonFailedUnreachable() {
        llm.fail(new LlmUnavailableException("down"));
        generator.generate(item.getId());
        assertEquals(LessonStatus.FAILED, lesson().getStatus());
        assertEquals(LessonGenerator.AI_UNREACHABLE, lesson().getFailureReason());
    }

    @Test
    void coverageSkipsIdeasTheSectionAlreadyHas() {
        String spof = "A cache server is a single point of failure unless several are used.";
        for (int run = 0; run < 2; run++) {
            llm.reply(lessonJson(11, List.of()));
            llm.reply(coverage(point("Single cache server is a SPOF", 11, spof),
                    point("Same idea again", 11, spof)));
            generator.generate(item.getId());
        }
        assertEquals(2, points.findByMapItemId(item.getId()).size());   // p1 + the SPOF point, once
    }
}
