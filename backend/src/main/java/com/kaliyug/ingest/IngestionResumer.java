package com.kaliyug.ingest;

import org.springframework.beans.factory.annotation.Value;
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
    private final boolean enabled;

    public IngestionResumer(ResourceRepository resources, IngestionService ingestion,
            @Value("${app.resume-ingestion:true}") boolean enabled) {
        this.resources = resources;
        this.ingestion = ingestion;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resume() {
        if (!enabled) {
            return;
        }
        for (Resource r : resources.findByStatus(ResourceStatus.INGESTING)) {
            ingestion.start(r.getId());
        }
    }
}
