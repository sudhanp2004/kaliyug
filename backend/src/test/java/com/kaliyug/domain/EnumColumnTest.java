package com.kaliyug.domain;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Enum columns are plain text so adding a constant later doesn't break the existing database. */
@DataJpaTest
class EnumColumnTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void enumColumnsAreVarchar() {
        List<String> types = jdbc.queryForList("""
                select data_type from information_schema.columns
                where (table_name, column_name) in (('POINT','KIND'),('QUESTION','TYPE'),('CHAPTER','KIND'),
                                                     ('RESOURCE','STATUS'),('ATTEMPT','VERDICT'))""", String.class);
        assertEquals(5, types.size());
        types.forEach(t -> assertEquals("CHARACTER VARYING", t));
    }

    @Test
    void longFailureReasonIsClipped() {
        Resource r = new Resource("t", "p", "s", java.time.Instant.now());
        r.setFailureReason("x".repeat(5000));
        assertEquals(1000, r.getFailureReason().length());
    }
}
