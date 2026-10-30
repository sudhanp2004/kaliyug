package com.kaliyug.ingest;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.domain.Question;
import com.kaliyug.domain.QuestionRepository;
import com.kaliyug.domain.QuestionType;
import com.kaliyug.ingest.AiContracts.ExtractedPoint;
import com.kaliyug.ingest.AiContracts.ExtractedQuestion;

/**
 * Saves AI-extracted points that are grounded in the section's text, with their questions.
 * Call inside a transaction.
 */
@Component
public class PointWriter {

    private final PointRepository points;
    private final QuestionRepository questions;

    public PointWriter(PointRepository points, QuestionRepository questions) {
        this.points = points;
        this.questions = questions;
    }

    public List<Point> saveGrounded(MapItem item, List<ExtractedPoint> extracted, String itemPlainText) {
        List<Point> saved = new ArrayList<>();
        for (ExtractedPoint e : extracted == null ? List.<ExtractedPoint>of() : extracted) {
            if (!grounded(e, item, itemPlainText)) {
                continue;
            }
            List<ExtractedQuestion> usable = (e.questions() == null ? List.<ExtractedQuestion>of() : e.questions()).stream()
                    .filter(q -> notBlank(q.prompt()) && notBlank(q.modelAnswer()))
                    .toList();
            if (usable.isEmpty()) {
                continue;
            }
            PointKind kind = e.kind() == null ? PointKind.CONCEPT : e.kind();
            Point point = points.save(new Point(item, kind, e.text().trim(), e.firstPage(), e.lastPage(), e.evidence().trim()));
            for (ExtractedQuestion q : usable) {
                QuestionType type = q.type() != null ? q.type() : kind == PointKind.NUMBER ? QuestionType.QUICKFIRE : QuestionType.SHORT;
                questions.save(new Question(point, type, q.prompt().trim(), q.modelAnswer().trim()));
            }
            saved.add(point);
        }
        return saved;
    }

    private static boolean grounded(ExtractedPoint e, MapItem item, String itemText) {
        return notBlank(e.text())
                && e.firstPage() >= item.getStartPage() && e.lastPage() <= item.getEndPage()
                && e.firstPage() <= e.lastPage()
                && Grounding.evidenceFound(e.evidence(), itemText);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
