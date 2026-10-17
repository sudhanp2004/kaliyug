package com.kaliyug.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptRepository extends JpaRepository<Attempt, Long> {

    List<Attempt> findByAtAfterOrderByAt(Instant after);

    Optional<Attempt> findFirstByPointIdAndAtBeforeOrderByAtDesc(Long pointId, Instant before);
}
