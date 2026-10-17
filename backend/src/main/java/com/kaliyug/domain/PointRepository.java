package com.kaliyug.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointRepository extends JpaRepository<Point, Long> {

    List<Point> findByMapItemId(Long mapItemId);

    @Modifying
    void deleteByMapItemId(Long mapItemId);

    /** Points ready for revision: due, in a learned and visible map item, outside filler chapters. */
    @Query("""
            select p from Point p
            where p.dueAt <= :now
              and p.mapItem.learnedAt is not null
              and p.mapItem.hidden = false
              and p.mapItem.chapter.kind <> com.kaliyug.domain.ChapterKind.FILLER
            order by p.dueAt, p.id""")
    List<Point> findDue(@Param("now") Instant now);
}
