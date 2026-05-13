package com.swissql.service;

import com.swissql.util.DsnParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DsnParser URL builders and parseComponents.
 * Focuses on null/empty path handling and default port behaviour.
 */
class DsnParserTest {

    // -------------------------------------------------------------------------
    // buildPostgresJdbcUrl
    // -------------------------------------------------------------------------

    @Test
    void postgresUrlWithDatabase() {
        assertThat(DsnParser.buildPostgresJdbcUrl("localhost", 5432, "mydb"))
                .isEqualTo("jdbc:postgresql://localhost:5432/mydb");
    }

    @Test
    void postgresUrlWithDefaultPort() {
        assertThat(DsnParser.buildPostgresJdbcUrl("localhost", -1, "mydb"))
                .isEqualTo("jdbc:postgresql://localhost:5432/mydb");
    }

    @Test
    void postgresUrlWithNullPath() {
        assertThat(DsnParser.buildPostgresJdbcUrl("localhost", 5432, null))
                .isEqualTo("jdbc:postgresql://localhost:5432");
    }

    @Test
    void postgresUrlWithEmptyPath() {
        assertThat(DsnParser.buildPostgresJdbcUrl("localhost", 5432, ""))
                .isEqualTo("jdbc:postgresql://localhost:5432");
    }

    // -------------------------------------------------------------------------
    // buildMysqlJdbcUrl
    // -------------------------------------------------------------------------

    @Test
    void mysqlUrlWithDatabase() {
        assertThat(DsnParser.buildMysqlJdbcUrl("localhost", 3306, "mydb"))
                .isEqualTo("jdbc:mysql://localhost:3306/mydb");
    }

    @Test
    void mysqlUrlWithDefaultPort() {
        assertThat(DsnParser.buildMysqlJdbcUrl("localhost", -1, "mydb"))
                .isEqualTo("jdbc:mysql://localhost:3306/mydb");
    }

    @Test
    void mysqlUrlWithNullPath() {
        // MySQL allows connecting without specifying a database
        assertThat(DsnParser.buildMysqlJdbcUrl("localhost", 3306, null))
                .isEqualTo("jdbc:mysql://localhost:3306");
    }

    @Test
    void mysqlUrlWithEmptyPath() {
        assertThat(DsnParser.buildMysqlJdbcUrl("localhost", 3306, ""))
                .isEqualTo("jdbc:mysql://localhost:3306");
    }

    // -------------------------------------------------------------------------
    // buildOracleJdbcUrl — service mode (null path)
    // -------------------------------------------------------------------------

    @Test
    void oracleServiceModeWithDatabase() {
        assertThat(DsnParser.buildOracleJdbcUrl("dbhost", 1521, "ORCL", null))
                .isEqualTo("jdbc:oracle:thin:@//dbhost:1521/ORCL");
    }

    @Test
    void oracleServiceModeWithNullPath() {
        // null path + explicit port → service mode without database segment
        assertThat(DsnParser.buildOracleJdbcUrl("dbhost", 1521, null, null))
                .isEqualTo("jdbc:oracle:thin:@//dbhost:1521");
    }

    @Test
    void oracleTnsAliasMode() {
        // no port, no path → TNS alias mode
        assertThat(DsnParser.buildOracleJdbcUrl("myalias", -1, null, null))
                .isEqualTo("jdbc:oracle:thin:@myalias");
    }

    @Test
    void oracleSidMode() {
        assertThat(DsnParser.buildOracleJdbcUrl("dbhost", 1521, "ORCL", "sid=ORCL"))
                .isEqualTo("jdbc:oracle:thin:@dbhost:1521:ORCL");
    }

    // -------------------------------------------------------------------------
    // parseComponents — DSN without database segment
    // -------------------------------------------------------------------------

    @Test
    void parseComponentsNoDatabaseSegment() {
        DsnParser.ParsedDsn parsed = DsnParser.parseComponents("postgres://localhost:5432", "postgres");
        assertThat(parsed.getHost()).isEqualTo("localhost");
        assertThat(parsed.getPort()).isEqualTo(5432);
        assertThat(parsed.getPath()).isNull();
    }

    @Test
    void parseComponentsWithDatabase() {
        DsnParser.ParsedDsn parsed = DsnParser.parseComponents("mysql://user:pass@localhost:3306/mydb", "mysql");
        assertThat(parsed.getHost()).isEqualTo("localhost");
        assertThat(parsed.getPort()).isEqualTo(3306);
        assertThat(parsed.getPath()).isEqualTo("mydb");
        assertThat(parsed.getUsername()).isEqualTo("user");
    }
}
