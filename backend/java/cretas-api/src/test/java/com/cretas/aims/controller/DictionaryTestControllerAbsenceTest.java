package com.cretas.aims.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DictionaryTestControllerAbsenceTest {

    @Test
    void publicDictionaryTestControllerMustNotBePackaged() {
        assertThatThrownBy(() -> Class.forName(
                "com.cretas.aims.controller.DictionaryTestController"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
