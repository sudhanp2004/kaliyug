package com.kaliyug.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MapItemRepository extends JpaRepository<MapItem, Long> {

    List<MapItem> findByChapterIdOrderByOrderIndex(Long chapterId);

    List<MapItem> findByHiddenTrue();

    long countByChapterResourceId(Long resourceId);

    long countByLearnedAtIsNotNull();

    long countByChapterResourceIdAndIngestedTrue(Long resourceId);
}
