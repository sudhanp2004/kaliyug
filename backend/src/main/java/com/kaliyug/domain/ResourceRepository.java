package com.kaliyug.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceRepository extends JpaRepository<Resource, Long> {

    Optional<Resource> findBySha256(String sha256);

    List<Resource> findByStatus(ResourceStatus status);
}
