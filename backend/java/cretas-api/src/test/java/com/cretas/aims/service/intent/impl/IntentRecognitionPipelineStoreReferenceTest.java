package com.cretas.aims.service.intent.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IntentRecognitionPipeline STORE reference handling")
class IntentRecognitionPipelineStoreReferenceTest {

    @Test
    @DisplayName("store reference follow-up bypasses pre-preprocess phrase shortcut")
    void storeReferenceFollowUpBypassesPrePreprocessPhraseShortcut() {
        assertThat(IntentRecognitionPipelineServiceImpl
                .shouldBypassEarlyPhraseShortcutForStoreReference("那家店的客单价呢")).isTrue();
        assertThat(IntentRecognitionPipelineServiceImpl
                .shouldBypassEarlyPhraseShortcutForStoreReference("这家的客单价呢")).isTrue();
        assertThat(IntentRecognitionPipelineServiceImpl
                .shouldBypassEarlyPhraseShortcutForStoreReference("客单价")).isFalse();
        assertThat(IntentRecognitionPipelineServiceImpl
                .shouldBypassEarlyPhraseShortcutForStoreReference("哪家店业绩最好")).isFalse();
    }
}
