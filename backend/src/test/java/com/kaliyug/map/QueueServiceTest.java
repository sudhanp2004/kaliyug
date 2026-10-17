package com.kaliyug.map;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.kaliyug.domain.Chapter;
import com.kaliyug.domain.ChapterKind;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.Resource;
import com.kaliyug.domain.ResourceRepository;
import com.kaliyug.domain.ResourceStatus;
import com.kaliyug.llm.TestLlmConfig;
import com.kaliyug.map.QueueService.QueueItemDto;
import com.kaliyug.support.Db;
import com.kaliyug.support.Fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import({TestLlmConfig.class, Fixtures.class})
class QueueServiceTest {

    @Autowired QueueService queue;
    @Autowired Fixtures fx;
    @Autowired ResourceRepository resources;
    @Autowired JdbcTemplate jdbc;

    private Resource bookA;
    private MapItem a1;
    private MapItem a2;
    private MapItem a3;

    @BeforeEach
    void setUp() {
        Db.wipe(jdbc);
        bookA = fx.book("A");
        Chapter ach1 = fx.chapter(bookA, 1, "A ch1", ChapterKind.CONCEPT);
        Chapter ach2 = fx.chapter(bookA, 2, "A ch2", ChapterKind.DESIGN);
        a1 = fx.item(ach1, 0, "a1");
        a2 = fx.item(ach1, 1, "a2");
        a3 = fx.item(ach2, 0, "a3");
    }

    private List<String> titles() {
        return queue.queue(20).items().stream().map(QueueItemDto::title).toList();
    }

    private MapItem learned(MapItem item) {
        item.setLearnedAt(Instant.now());
        return fx.save(item);
    }

    private void addBookB() {
        Resource bookB = fx.book("B");
        Chapter bch1 = fx.chapter(bookB, 1, "B ch1", ChapterKind.CONCEPT);
        Chapter bch2 = fx.chapter(bookB, 2, "B ch2", ChapterKind.CONCEPT);
        fx.item(bch1, 0, "b1");
        fx.item(bch2, 0, "b2");
        fx.item(bch2, 1, "b3");
    }

    @Test
    void singleBookIsSequential() {
        assertEquals(List.of("a1", "a2", "a3"), titles());
        learned(a1);
        assertEquals(List.of("a2", "a3"), titles());
    }

    @Test
    void rotatesBetweenBooksAtChapterBoundaries() {
        addBookB();
        assertEquals(List.of("a1", "a2", "b1", "a3", "b2", "b3"), titles());
    }

    @Test
    void partiallyLearnedChapterGoesFirst() {
        addBookB();
        Resource bookB = resources.findAll().stream().filter(r -> r.getTitle().equals("B")).findFirst().orElseThrow();
        // learn b1 and b2 so B's chapter 2 is half done
        jdbc.update("update map_item set learned_at = ? where title in ('b1','b2')", java.sql.Timestamp.from(Instant.now()));
        assertEquals("b3", titles().get(0));
        assertTrue(bookB.getId() != null);
    }

    @Test
    void stopsAtFirstUningestedItem() {
        a2.setIngested(false);
        fx.save(a2);
        bookA.setStatus(ResourceStatus.INGESTING);
        resources.save(bookA);

        var dto = queue.queue(20);

        assertEquals(List.of("a1"), dto.items().stream().map(QueueItemDto::title).toList());
        assertEquals(1, dto.remaining());
        assertTrue(dto.preparing());
    }

    @Test
    void hiddenAndFillerExcluded() {
        a2.setHidden(true);
        fx.save(a2);
        Chapter filler = fx.chapter(bookA, 3, "Afterword", ChapterKind.FILLER);
        fx.item(filler, 0, "filler item");

        assertEquals(List.of("a1", "a3"), titles());
    }

    @Test
    void itemsCarryBookChapterAndPages() {
        QueueItemDto first = queue.queue(1).items().get(0);
        assertEquals("A", first.bookTitle());
        assertEquals("A ch1", first.chapterTitle());
        assertEquals(a1.getStartPage(), first.startPage());
        assertEquals(3, queue.queue(1).remaining());
    }
}
