package com.swissql.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlSafetyValidatorTest {
    @Test
    void allowsSingleReadStatementWithTrailingSemicolon() {
        SqlSafetyValidator.validate("select ';' as value;", false);

        assertThat(SqlSafetyValidator.isWriteStatement("/* comment */ select 1")).isFalse();
    }

    @Test
    void rejectsMultipleStatements() {
        assertThatThrownBy(() -> SqlSafetyValidator.validate("select 1; select 2", false))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("Exactly one");
    }

    @Test
    void rejectsWriteStatementWithoutAllowWrite() {
        assertThatThrownBy(() -> SqlSafetyValidator.validate("-- x\nupdate users set name = 'x'", false))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("allow_write=true");
    }

    @Test
    void allowsWriteStatementWithAllowWrite() {
        SqlSafetyValidator.validate("delete from users where id = 1", true);

        assertThat(SqlSafetyValidator.isWriteStatement("create table x(id int)")).isTrue();
    }
}
