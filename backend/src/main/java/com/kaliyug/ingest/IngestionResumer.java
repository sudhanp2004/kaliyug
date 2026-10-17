package com.kaliyug.ingest;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.kaliyug.domain.Resource;
import com.kaliyug.domain.ResourceRepository;
import com.kaliyug.domain.ResourceStatus;

/** Picks up books that were still being read when the app last stopped. */
@Component
public class IngestionResumer {

    private final ResourceRepository resources;
    private final IngestionService ingestion;

    public IngestionResumer(ResourceRepository resources, IngestionService ingestion) {
        this.resources = resources;
        this.ingestion = ingestion;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resume() {
        for (Resource r : resources.findByStatus(ResourceStatus.INGESTING)) {
            ingestion.start(r.getId());
        }
    }
}
