package com.weepwood.cruddemo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlSupportTest {
    @Test
    void quotesIdentifiers() {
        assertThat(SqlSupport.quoteIdentifier("user\"table")).isEqualTo("\"user\"\"table\"");
    }

    @Test
    void buildsQualifiedName() {
        assertThat(SqlSupport.qualifiedName("app", "orders")).isEqualTo("\"app\".\"orders\"");
    }

    @Test
    void rejectsBlankIdentifier() {
        assertThatThrownBy(() -> SqlSupport.quoteIdentifier(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
