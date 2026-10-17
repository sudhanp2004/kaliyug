package com.kaliyug.ingest;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.kaliyug.domain.ChapterKind;
import com.kaliyug.domain.ChapterRepository;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.MapItemRepository;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.domain.ProblemRepository;
import com.kaliyug.domain.QuestionRepository;
import com.kaliyug.domain.Resource;
import com.kaliyug.domain.ResourceRepository;
import com.kaliyug.domain.ResourceStatus;
import com.kaliyug.llm.FakeLlm;
import com.kaliyug.llm.LlmUnavailableException;
import com.kaliyug.llm.Prompts;
import com.kaliyug.llm.TestLlmConfig;
import com.kaliyug.support.Db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestLlmConfig.class)
class IngestionServiceTest {

    @TempDir
    static Path dir;
    static Path fixture;

    @Autowired IngestionService ingestion;
    @Autowired FakeLlm llm;
    @Autowired Prompts prompts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ResourceRepository resources;
    @Autowired ChapterRepository chapters;
    @Autowired MapItemRepository mapItems;
    @Autowired PointRepository points;
    @Autowired QuestionRepository questions;
    @Autowired ProblemRepository problems;

    private Long bookId;

    @BeforeAll
    static void buildFixture() {
        fixture = TestPdfs.outlined(dir.resolve("fixture.pdf"));
    }

    @BeforeEach
    void setUp() {
        Db.wipe(jdbc);
        llm.reset();
        ingestion.setRetryDelay(java.time.Duration.ofMillis(1));
        bookId = resources.save(new Resource("Fixture Book", fixture.toString(), "sha", Instant.now())).getId();
    }

    private static final String CLASSIFY = """
            {"chapters":[{"index":0,"kind":"FILLER"},{"index":1,"kind":"CONCEPT"},{"index":2,"kind":"DESIGN"}]}""";
    private static final String SPLIT_CH1 = """
            {"sections":[{"title":"Basics","startPage":2,"endPage":3}]}""";
    private static final String SPLIT_CH2 = """
            {"sections":[{"title":"Part A","startPage":4,"endPage":5},{"title":"Part B","startPage":6,"endPage":6}]}""";

    private static String onePoint(int page) {
        return """
                {"points":[{"kind":"CONCEPT","text":"Load balancers spread traffic","firstPage":%d,"lastPage":%d,
                  "evidence":"The load balancer evenly distributes incoming traffic across servers.",
                  "questions":[{"type":"SHORT","prompt":"What does a load balancer do?","modelAnswer":"Evenly distributes incoming traffic across servers."}]}]}"""
                .formatted(page, page);
    }

    /** A generate-problem reply that cites every point id listed in the request. */
    private static String problemCitingListedPoints(String user) {
        return problemCiting(listedIds(user));
    }

    private static String problemCiting(List<Long> ids) {
        String idList = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return """
                {"title":"Design X","requirements":["Handle traffic"],
                 "components":[{"name":"Load balancer","role":"spreads traffic","pointIds":[%s]}],
                 "decisions":[{"decision":"Use a load balancer","pointIds":[%s]}]}""".formatted(idList, idList);
    }

    private static List<Long> listedIds(String user) {
        Matcher m = Pattern.compile("(?m)^(\\d+) \\|").matcher(user);
        List<Long> ids = new java.util.ArrayList<>();
        while (m.find()) {
            ids.add(Long.parseLong(m.group(1)));
        }
        return ids;
    }

    private void queueHappyPath() {
        llm.reply(CLASSIFY).reply(SPLIT_CH1).reply(SPLIT_CH2)
                .reply(onePoint(2)).reply(onePoint(4)).reply(onePoint(6))
                .replyWith(IngestionServiceTest::problemCitingListedPoints);
    }

    @Test
    void ingestsBookEndToEnd() {
        queueHappyPath();

        ingestion.run(bookId);

        assertEquals(ResourceStatus.READY, resources.findById(bookId).orElseThrow().getStatus());
        assertEquals(3, mapItems.count());
        assertEquals(3, points.count());
        assertEquals(3, questions.count());
        tx.executeWithoutResult(s -> {
            var problem = problems.findAll().get(0);
            Set<Long> problemPointPages = problem.getPoints().stream().map(p -> (long) p.getFirstPage()).collect(Collectors.toSet());
            assertEquals(Set.of(4L, 6L), problemPointPages);
        });
        assertEquals(1, problems.count());
        assertEquals(0, llm.pending());
        boolean anyCallReadForeword = llm.calls.stream()
                .filter(c -> !c[0].equals(prompts.get("classify-chapters")))
                .anyMatch(c -> c[1].contains("=== PAGE 1 ==="));
        assertFalse(anyCallReadForeword);
        var kinds = chapters.findByResourceIdOrderByOrderIndex(bookId).stream().map(c -> c.getKind()).toList();
        assertEquals(List.of(ChapterKind.FILLER, ChapterKind.CONCEPT, ChapterKind.DESIGN), kinds);
    }

    @Test
    void dropsPointsWithOutOfRangePagesOrFakeEvidence() {
        String mixed = """
                {"points":[
                  {"kind":"CONCEPT","text":"valid","firstPage":2,"lastPage":2,
                   "evidence":"The load balancer evenly distributes incoming traffic across servers.",
                   "questions":[{"type":"SHORT","prompt":"Q?","modelAnswer":"A."}]},
                  {"kind":"CONCEPT","text":"bad page","firstPage":9,"lastPage":9,
                   "evidence":"The load balancer evenly distributes incoming traffic across servers.",
                   "questions":[{"type":"SHORT","prompt":"Q?","modelAnswer":"A."}]},
                  {"kind":"CONCEPT","text":"fake evidence","firstPage":2,"lastPage":2,
                   "evidence":"servers are always stateless by design rule",
                   "questions":[{"type":"SHORT","prompt":"Q?","modelAnswer":"A."}]}]}""";
        llm.reply(CLASSIFY).reply(SPLIT_CH1).reply(SPLIT_CH2)
                .reply(mixed).reply(onePoint(4)).reply(onePoint(6))
                .replyWith(IngestionServiceTest::problemCitingListedPoints);

        ingestion.run(bookId);

        List<String> texts = points.findAll().stream().map(Point::getText).toList();
        assertTrue(texts.contains("valid"));
        assertFalse(texts.contains("bad page"));
        assertFalse(texts.contains("fake evidence"));
        assertEquals(3, texts.size());
    }

    @Test
    void invalidSectionSplitFallsBackToWholeChapter() {
        String bad = """
                {"sections":[{"title":"x","startPage":2,"endPage":2}]}""";
        llm.reply(CLASSIFY).reply(bad).reply(bad).reply(SPLIT_CH2)
                .reply(onePoint(2)).reply(onePoint(4)).reply(onePoint(6))
                .replyWith(IngestionServiceTest::problemCitingListedPoints);

        ingestion.run(bookId);

        var ch1 = chapters.findByResourceIdOrderByOrderIndex(bookId).get(1);
        List<MapItem> items = mapItems.findByChapterIdOrderByOrderIndex(ch1.getId());
        assertEquals(1, items.size());
        assertEquals(2, items.get(0).getStartPage());
        assertEquals(3, items.get(0).getEndPage());
        assertTrue(llm.calls.get(2)[1].contains("did not"), "retry message should explain the problem");
    }

    @Test
    void rerunAfterInterruptionCreatesNoDuplicates() {
        llm.reply(CLASSIFY).reply(SPLIT_CH1).reply(SPLIT_CH2)
                .reply(onePoint(2)).failTimes(6, new LlmUnavailableException("down"));

        ingestion.run(bookId);

        Resource failed = resources.findById(bookId).orElseThrow();
        assertEquals(ResourceStatus.FAILED, failed.getStatus());
        assertEquals("AI service unreachable — press Retry in Library.", failed.getFailureReason());
        assertEquals(1, points.count());

        failed.setStatus(ResourceStatus.INGESTING);
        resources.save(failed);
        llm.reset();
        llm.reply(onePoint(4)).reply(onePoint(6)).replyWith(IngestionServiceTest::problemCitingListedPoints);

        ingestion.run(bookId);

        assertEquals(ResourceStatus.READY, resources.findById(bookId).orElseThrow().getStatus());
        assertEquals(3, points.count());
        assertEquals(3, points.findAll().stream().map(Point::getFirstPage).distinct().count());
        assertEquals(3, llm.calls.size(), "only the two remaining extracts and the problem are requested");
    }

    @Test
    void designProblemDropsForeignPointIds() {
        llm.reply(CLASSIFY).reply(SPLIT_CH1).reply(SPLIT_CH2)
                .reply(onePoint(2)).reply(onePoint(4)).reply(onePoint(6))
                .replyWith(user -> {
                    Long ch1PointId = points.findAll().stream().filter(p -> p.getFirstPage() == 2).findFirst().orElseThrow().getId();
                    List<Long> ids = new java.util.ArrayList<>(listedIds(user));
                    ids.add(ch1PointId);
                    ids.add(9999L);
                    return problemCiting(ids);
                });

        ingestion.run(bookId);

        tx.executeWithoutResult(s -> {
            var pages = problems.findAll().get(0).getPoints().stream().map(Point::getFirstPage).collect(Collectors.toSet());
            assertEquals(Set.of(4, 6), pages);
        });
    }
}
