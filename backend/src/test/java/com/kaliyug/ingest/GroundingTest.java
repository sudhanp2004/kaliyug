package com.kaliyug.ingest;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.kaliyug.ingest.AiContracts.Section;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundingTest {

    @Test
    void normalizeLowercasesAndCollapsesNonAlphanumerics() {
        assertEquals("a b 1 2", Grounding.normalize("  A—b! 1,2 "));
    }

    @Test
    void evidenceMustBeLongEnoughAndPresent() {
        assertTrue(Grounding.evidenceFound("the load  balancer evenly distributes", "...The load balancer evenly distributes incoming traffic..."));
        assertFalse(Grounding.evidenceFound("too short", "too short text"));
        assertFalse(Grounding.evidenceFound("caches always use write-through policy", "Cache uses read-through"));
    }

    @Test
    void sectionsMustBeContiguousAndCoverTheChapter() {
        assertTrue(Grounding.validSections(List.of(new Section("a", 51, 55), new Section("b", 55, 60), new Section("c", 61, 70)), 51, 70));
        assertFalse(Grounding.validSections(List.of(new Section("a", 51, 55), new Section("b", 57, 70)), 51, 70));
        assertFalse(Grounding.validSections(List.of(), 51, 70));
        assertFalse(Grounding.validSections(List.of(new Section("a", 52, 70)), 51, 70));
    }
}
