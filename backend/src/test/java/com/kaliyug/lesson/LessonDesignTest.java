package com.kaliyug.lesson;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import com.kaliyug.domain.AttemptRepository;
import com.kaliyug.domain.Chapter;
import com.kaliyug.domain.ChapterKind;
import com.kaliyug.domain.Lesson;
import com.kaliyug.domain.LessonRepository;
import com.kaliyug.domain.LessonStatus;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.lesson.LessonContracts.Block;
import com.kaliyug.lesson.LessonContracts.LessonContent;
import com.kaliyug.lesson.LessonContracts.MiniDesignSpec;
import com.kaliyug.lesson.LessonContracts.Part;
import com.kaliyug.llm.FakeLlm;
import com.kaliyug.llm.TestLlmConfig;
import com.kaliyug.support.Db;
import com.kaliyug.support.Fixtures;
import com.kaliyug.support.TestClockConfig;

import static com.kaliyug.support.TestClockConfig.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestLlmConfig.class, Fixtures.class, TestClockConfig.class})
class LessonDesignTest {

    private static final String DESIGN = """
            {"nodes":[{"id":"n1","label":"Web server"},{"id":"n2","label":"Cache"}],
             "edges":[{"from":"n1","to":"n2","label":"read"}],
             "notes":"read-through"}""";

    @Autowired MockMvc mvc;
    @Autowired FakeLlm llm;
    @Autowired Fixtures fx;
    @Autowired LessonRepository lessons;
    @Autowired PointRepository points;
    @Autowired AttemptRepository attempts;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    MapItem item;
    MapItem plainItem;
    Point p1;
    Point p2;

    @BeforeEach
    void setUp() {
        Db.wipe(jdbc);
        llm.reset();
        Chapter ch = fx.chapter(fx.book("System Design"), 1, "Scale", ChapterKind.CONCEPT);
        item = fx.item(ch, 0, "Cache");
        plainItem = fx.item(ch, 1, "History");
        p1 = fx.point(item, PointKind.CONCEPT, "Cache sits between web server and database");
        p2 = fx.point(item, PointKind.TRADEOFF, "Several cache servers avoid a single point of failure");
        saveReady(item, new MiniDesignSpec("Add a cache to a web app.", List.of("Reads are fast"), List.of(p1.getId(), p2.getId())));
        saveReady(plainItem, null);
    }

    private void saveReady(MapItem m, MiniDesignSpec mini) {
        Lesson l = new Lesson(m);
        l.setStatus(LessonStatus.READY);
        l.setContentJson(mapper.writeValueAsString(new LessonContent(
                List.of(new Block(new Part("A cache keeps hot data in memory.", 11, 11),
                        new Part("Check the cache first.", 11, 11), List.of())),
                List.of(), "", "cache", mini)));
        lessons.save(l);
    }

    @Test
    void lessonMiniDesignGradesAgainstItsPoints() throws Exception {
        llm.reply("{\"coveredPointIds\":[" + p1.getId() + "],\"missedPointIds\":[" + p2.getId()
                + "],\"feedback\":\"Good start\",\"differences\":\"\"}");
        mvc.perform(post("/api/map-items/{id}/lesson/design", item.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(DESIGN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(2))
                .andExpect(jsonPath("$.feedback").value("Good start"));
        assertEquals(1, points.findById(p1.getId()).orElseThrow().getBox());
        assertEquals(NOW.plus(Duration.ofMinutes(10)), points.findById(p2.getId()).orElseThrow().getDueAt());
        assertTrue(attempts.findAll().stream().allMatch(a -> a.getProblem() == null));
        assertTrue(llm.calls.get(0)[1].contains("Add a cache to a web app."));
    }

    @Test
    void lessonWithoutMiniDesignRejectsDesign() throws Exception {
        mvc.perform(post("/api/map-items/{id}/lesson/design", plainItem.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(DESIGN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("This lesson has no mini design."));
    }
}
