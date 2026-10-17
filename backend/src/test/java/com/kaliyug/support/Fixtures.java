package com.kaliyug.support;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.kaliyug.domain.Chapter;
import com.kaliyug.domain.ChapterKind;
import com.kaliyug.domain.ChapterRepository;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.MapItemRepository;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.domain.Question;
import com.kaliyug.domain.QuestionRepository;
import com.kaliyug.domain.QuestionType;
import com.kaliyug.domain.Resource;
import com.kaliyug.domain.ResourceRepository;
import com.kaliyug.domain.ResourceStatus;

/** Builds library data for Spring tests. Import with @Import(Fixtures.class). */
@Component
public class Fixtures {

    private final ResourceRepository resources;
    private final ChapterRepository chapters;
    private final MapItemRepository mapItems;
    private final PointRepository points;
    private final QuestionRepository questions;

    public Fixtures(ResourceRepository resources, ChapterRepository chapters, MapItemRepository mapItems,
            PointRepository points, QuestionRepository questions) {
        this.resources = resources;
        this.chapters = chapters;
        this.mapItems = mapItems;
        this.points = points;
        this.questions = questions;
    }

    public Resource book(String title) {
        Resource r = new Resource(title, "unused.pdf", UUID.randomUUID().toString(), Instant.now());
        r.setStatus(ResourceStatus.READY);
        return resources.save(r);
    }

    public Chapter chapter(Resource book, int index, String title, ChapterKind kind) {
        Chapter c = new Chapter(book, index, title, index * 10 + 1, index * 10 + 10, kind);
        c.setSectionsSplit(true);
        return chapters.save(c);
    }

    /** An ingested, unlearned, visible item. */
    public MapItem item(Chapter chapter, int index, String title) {
        MapItem m = new MapItem(chapter, index, title, chapter.getStartPage() + index, chapter.getStartPage() + index);
        m.setIngested(true);
        return mapItems.save(m);
    }

    public MapItem save(MapItem item) {
        return mapItems.save(item);
    }

    public Point point(MapItem item, PointKind kind, String text) {
        return points.save(new Point(item, kind, text, item.getStartPage(), item.getStartPage(), "evidence for " + text));
    }

    public Point save(Point point) {
        return points.save(point);
    }

    public Question question(Point point, QuestionType type, String prompt, String modelAnswer) {
        return questions.save(new Question(point, type, prompt, modelAnswer));
    }
}
