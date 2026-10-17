package com.kaliyug.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChapterRepository extends JpaRepository<Chapter, Long> {

    List<Chapter> findByResourceIdOrderByOrderIndex(Long resourceId);
}
