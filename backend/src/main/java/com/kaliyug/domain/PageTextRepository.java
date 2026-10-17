package com.kaliyug.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PageTextRepository extends JpaRepository<PageText, Long> {

    List<PageText> findByResourceIdAndPageNumberBetweenOrderByPageNumber(Long resourceId, int from, int to);

    boolean existsByResourceId(Long resourceId);
}
