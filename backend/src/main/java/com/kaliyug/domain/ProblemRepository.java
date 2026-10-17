package com.kaliyug.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProblemRepository extends JpaRepository<Problem, Long> {

    Optional<Problem> findByChapterId(Long chapterId);

    @Query("select p from Problem p where p.dueAt <= :now order by p.dueAt, p.id")
    List<Problem> findDue(@Param("now") Instant now);
}
