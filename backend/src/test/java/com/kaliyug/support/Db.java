package com.kaliyug.support;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/** Empties every application table between Spring tests that share one in-memory database. */
public final class Db {

    private Db() {
    }

    public static void wipe(JdbcTemplate jdbc) {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'PUBLIC' and table_type = 'BASE TABLE'",
                String.class);
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : tables) {
            jdbc.execute("TRUNCATE TABLE \"" + table + "\" RESTART IDENTITY");
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }
}
