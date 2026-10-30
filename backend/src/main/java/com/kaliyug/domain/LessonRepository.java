package com.kaliyug.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LessonRepository extends JpaRepository<Lesson, Long> {

    Optional<Lesson> findByMapItemId(Long mapItemId);
}
