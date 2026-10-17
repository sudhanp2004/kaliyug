package com.kaliyug.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    /** Active questions for a point, least recently asked (never asked first). */
    @Query("""
            select q from Question q
            where q.point.id = :pointId and q.retired = false
            order by q.lastAskedAt asc nulls first, q.id asc""")
    List<Question> findByPointIdAndRetiredFalseOrderByLastAskedAtAscIdAsc(@Param("pointId") Long pointId);
}
