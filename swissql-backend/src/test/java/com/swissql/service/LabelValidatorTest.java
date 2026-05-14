package com.swissql.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabelValidatorTest {

    @Test
    void acceptsNullLabels() {
        assertThatCode(() -> LabelValidator.validate(null)).doesNotThrowAnyException();
    }

    @Test
    void acceptsEmptyLabels() {
        assertThatCode(() -> LabelValidator.validate(Map.of())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "env",
            "cluster",
            "a",
            "0",
            "my-key",
            "my.key",
            "my_key",
            "my/key",
            "abc123",
            "a-b.c_d/e"
    })
    void acceptsValidKeys(String key) {
        assertThatCode(() -> LabelValidator.validate(Map.of(key, "value"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-starts-with-dash",
            ".starts-with-dot",
            "_starts-with-underscore",
            "/starts-with-slash",
            "",
            "has space",
            "HAS_UPPER",
            "has@symbol"
    })
    void rejectsInvalidKeys(String key) {
        assertThatThrownBy(() -> LabelValidator.validate(Map.of(key, "value")))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("label key");
    }

    @Test
    void rejectsKeyTooLong() {
        String longKey = "a" + "b".repeat(63); // 64 chars total — exceeds max of 63
        assertThatThrownBy(() -> LabelValidator.validate(Map.of(longKey, "value")))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("label key");
    }

    @Test
    void acceptsKeyAtMaxLength() {
        String maxKey = "a" + "b".repeat(62); // 63 chars total — exactly at max
        assertThatCode(() -> LabelValidator.validate(Map.of(maxKey, "value"))).doesNotThrowAnyException();
    }

    @Test
    void acceptsValueAtMaxLength() {
        String maxValue = "v".repeat(256);
        assertThatCode(() -> LabelValidator.validate(Map.of("key", maxValue))).doesNotThrowAnyException();
    }

    @Test
    void rejectsValueTooLong() {
        String longValue = "v".repeat(257);
        assertThatThrownBy(() -> LabelValidator.validate(Map.of("key", longValue)))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("label value");
    }

    @Test
    void rejectsNullValue() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("key", null);
        assertThatThrownBy(() -> LabelValidator.validate(labels))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("label value");
    }

    @Test
    void acceptsMaxLabelCount() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < 64; i++) {
            labels.put("key" + i, "value");
        }
        assertThatCode(() -> LabelValidator.validate(labels)).doesNotThrowAnyException();
    }

    @Test
    void rejectsTooManyLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < 65; i++) {
            labels.put("key" + i, "value");
        }
        assertThatThrownBy(() -> LabelValidator.validate(labels))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("64");
    }
}
