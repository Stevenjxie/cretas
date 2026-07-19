package com.cretas.aims.scheduler;

import com.cretas.aims.ai.synthetic.SyntheticDataService;
import com.cretas.aims.config.SyntheticDataConfig;
import com.cretas.aims.repository.FactoryAILearningConfigRepository;
import com.cretas.aims.repository.ModelVersionRepository;
import com.cretas.aims.repository.TrainingDataRepository;
import com.cretas.aims.service.MixedTrainingDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelTrainingSchedulerTest {

    private TrainingDataRepository trainingDataRepository;
    private ModelVersionRepository modelVersionRepository;
    private ModelTrainingScheduler scheduler;

    @BeforeEach
    void setUp() {
        trainingDataRepository = mock(TrainingDataRepository.class);
        modelVersionRepository = mock(ModelVersionRepository.class);
        scheduler = new ModelTrainingScheduler(
                trainingDataRepository,
                modelVersionRepository,
                mock(SyntheticDataConfig.class),
                mock(SyntheticDataService.class),
                mock(FactoryAILearningConfigRepository.class),
                mock(MixedTrainingDataService.class));
        ReflectionTestUtils.setField(scheduler, "minTrainingDataCount", 50);
        ReflectionTestUtils.setField(scheduler, "retrainThreshold", 1.2d);
    }

    @Test
    void trainingIsDisabledByDefaultAndCapabilityIsExplicit() {
        when(trainingDataRepository.findDistinctFactoryIds()).thenReturn(List.of());
        when(modelVersionRepository.findDistinctFactoryIdsWithActiveModels()).thenReturn(List.of());

        Map<String, Object> status = scheduler.getTrainingStatus();

        assertEquals(false, status.get("trainingEnabled"));
        assertEquals("NOT_IMPLEMENTED", status.get("trainingCapability"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void manualCheckDoesNotClaimTrainingWasTriggered() {
        when(trainingDataRepository.findDistinctFactoryIds()).thenReturn(List.of("FACTORY-1"));
        when(trainingDataRepository.countByFactoryId("FACTORY-1")).thenReturn(80L);
        when(modelVersionRepository.existsByFactoryIdAndModelTypeAndIsActiveTrue(
                "FACTORY-1", "efficiency")).thenReturn(false);

        Map<String, Object> result = scheduler.manualTrainingCheck();
        Map<String, Map<String, Object>> factories =
                (Map<String, Map<String, Object>>) result.get("factories");

        assertEquals("NOT_IMPLEMENTED", result.get("trainingCapability"));
        assertEquals("NOT_IMPLEMENTED",
                factories.get("FACTORY-1").get("trainingCapability"));
        assertFalse(factories.get("FACTORY-1").containsKey("trainingTriggered"));
        verify(modelVersionRepository, never()).save(any());
        verify(modelVersionRepository, never()).deactivateModelsByType(anyString(), anyString());
    }

    @Test
    void triggerTrainingIsAnExplicitNoOp() {
        scheduler.triggerTraining("FACTORY-1", List.of("efficiency"));

        verifyNoInteractions(trainingDataRepository, modelVersionRepository);
    }
}
