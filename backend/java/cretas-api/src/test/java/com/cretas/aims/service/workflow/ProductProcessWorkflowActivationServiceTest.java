package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.ProductProcessWorkflowActivationDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.service.validation.ProductProcessWorkflowCatalogValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductProcessWorkflowActivationServiceTest {

    @Mock private ProductProcessWorkflowActivationRepository activationRepository;
    @Mock private ProductProcessWorkflowRepository workflowRepository;
    @Mock private ProductProcessWorkflowValidator validator;
    @Mock private ProductProcessWorkflowCatalogValidator catalogValidator;

    private ProductProcessWorkflowActivationService service;

    @BeforeEach
    void setUp() {
        service = new com.cretas.aims.service.workflow.impl.ProductProcessWorkflowActivationServiceImpl(
                activationRepository, workflowRepository, validator, catalogValidator, new ObjectMapper());
    }

    @Test
    void activateRequiresExactPublishedOwnedWorkflowAndIsIdempotent() {
        ProductProcessWorkflow workflow = publishedWorkflow(44L, "F006", "PT-PIG", 3);
        ProductProcessWorkflowActivation saved = activation(91L, workflow, true, 0L);
        when(workflowRepository.findByIdAndFactoryId(44L, "F006"))
                .thenReturn(Optional.of(workflow));
        when(activationRepository.findByFactoryIdAndProductTypeId("F006", "PT-PIG"))
                .thenReturn(Optional.empty(), Optional.of(saved));
        when(activationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ProductProcessWorkflowActivation entity = invocation.getArgument(0);
            entity.setId(91L);
            entity.setLockVersion(0L);
            return entity;
        });

        ProductProcessWorkflowActivationDTO first = service.activate("F006", 44L, 7001L);
        ProductProcessWorkflowActivationDTO second = service.activate("F006", 44L, 7001L);

        assertEquals(first.getId(), second.getId());
        assertEquals(3, second.getActiveDefinitionVersion());
        verify(activationRepository, times(1)).saveAndFlush(any());
        verify(validator, times(2)).validateForPublish(any());
        verify(catalogValidator, times(2)).validateForPublish(eq("F006"), eq("PT-PIG"), any());
    }

    @Test
    void activateRejectsDraftWithStableCodeAndHint() {
        ProductProcessWorkflow draft = publishedWorkflow(44L, "F006", "PT-PIG", 3);
        draft.setStatus(ProductProcessWorkflow.Status.DRAFT);
        when(workflowRepository.findByIdAndFactoryId(44L, "F006")).thenReturn(Optional.of(draft));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.activate("F006", 44L, 7001L));

        assertEquals("WORKFLOW_NOT_PUBLISHED", error.getErrorCode());
        assertNotNull(error.getActionHint());
        verifyNoInteractions(validator, catalogValidator, activationRepository);
    }

    @Test
    void activateRejectsCrossFactoryWorkflowWithStableProductMismatchCode() {
        when(workflowRepository.findByIdAndFactoryId(44L, "F006")).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.activate("F006", 44L, 7001L));

        assertEquals("WORKFLOW_ACTIVATION_PRODUCT_MISMATCH", error.getErrorCode());
        assertNotNull(error.getActionHint());
        verifyNoInteractions(validator, catalogValidator, activationRepository);
    }

    @Test
    void newerPublishedVersionDoesNotAutoSwitchWithoutExplicitActivation() {
        ProductProcessWorkflow oldWorkflow = publishedWorkflow(44L, "F006", "PT-PIG", 3);
        ProductProcessWorkflowActivation current = activation(91L, oldWorkflow, true, 5L);
        when(activationRepository.findByFactoryIdAndProductTypeId("F006", "PT-PIG"))
                .thenReturn(Optional.of(current));

        ProductProcessWorkflowActivationDTO result = service.get("F006", "PT-PIG");

        assertEquals(44L, result.getActiveWorkflowId());
        assertEquals(3, result.getActiveDefinitionVersion());
        verifyNoInteractions(workflowRepository, validator, catalogValidator);
    }

    @Test
    void explicitActivationOfNewPublishedVersionUpdatesTheSingleExistingRow() {
        ProductProcessWorkflow next = publishedWorkflow(45L, "F006", "PT-PIG", 4);
        ProductProcessWorkflowActivation current = activation(
                91L, publishedWorkflow(44L, "F006", "PT-PIG", 3), true, 5L);
        when(workflowRepository.findByIdAndFactoryId(45L, "F006")).thenReturn(Optional.of(next));
        when(activationRepository.findByFactoryIdAndProductTypeId("F006", "PT-PIG"))
                .thenReturn(Optional.of(current));
        when(activationRepository.saveAndFlush(current)).thenReturn(current);

        ProductProcessWorkflowActivationDTO result = service.activate("F006", 45L, 7002L);

        assertEquals(91L, result.getId());
        assertEquals(45L, result.getActiveWorkflowId());
        assertEquals(4, result.getActiveDefinitionVersion());
        assertEquals(7002L, result.getActivatedBy());
        verify(activationRepository).saveAndFlush(current);
    }

    @Test
    void deactivateRejectsStaleLockVersionWithStableConflictCode() {
        ProductProcessWorkflowActivation current = activation(
                91L, publishedWorkflow(44L, "F006", "PT-PIG", 3), true, 5L);
        when(activationRepository.findByFactoryIdAndProductTypeId("F006", "PT-PIG"))
                .thenReturn(Optional.of(current));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.deactivate("F006", "PT-PIG", 4L));

        assertEquals(409, error.getCode());
        assertEquals("WORKFLOW_ACTIVATION_CONFLICT", error.getErrorCode());
        assertNotNull(error.getActionHint());
        verify(activationRepository, never()).saveAndFlush(any());
    }

    @Test
    void deactivateOnlyDisablesActivationAndDoesNotDeleteRuntimeState() {
        ProductProcessWorkflowActivation current = activation(
                91L, publishedWorkflow(44L, "F006", "PT-PIG", 3), true, 5L);
        when(activationRepository.findByFactoryIdAndProductTypeId("F006", "PT-PIG"))
                .thenReturn(Optional.of(current));
        when(activationRepository.saveAndFlush(current)).thenReturn(current);

        ProductProcessWorkflowActivationDTO result = service.deactivate("F006", "PT-PIG", 5L);

        assertFalse(result.getEnabled());
        assertEquals(44L, result.getActiveWorkflowId());
        verify(activationRepository).saveAndFlush(current);
        verify(activationRepository, never()).delete(any());
        verify(activationRepository, never()).deleteById(any());
    }

    @Test
    void persistenceOptimisticLockFailureUsesStableActivationConflictCode() {
        ProductProcessWorkflowActivation current = activation(
                91L, publishedWorkflow(44L, "F006", "PT-PIG", 3), true, 5L);
        when(activationRepository.findByFactoryIdAndProductTypeId("F006", "PT-PIG"))
                .thenReturn(Optional.of(current));
        when(activationRepository.saveAndFlush(current))
                .thenThrow(new ObjectOptimisticLockingFailureException(ProductProcessWorkflowActivation.class, 91L));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.deactivate("F006", "PT-PIG", 5L));

        assertEquals("WORKFLOW_ACTIVATION_CONFLICT", error.getErrorCode());
    }

    private ProductProcessWorkflow publishedWorkflow(
            Long id, String factoryId, String productTypeId, int version) {
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(id);
        workflow.setFactoryId(factoryId);
        workflow.setProductTypeId(productTypeId);
        workflow.setSchemaVersion(1);
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setDefinitionVersion(version);
        workflow.setNodesJson("[]");
        workflow.setEdgesJson("[]");
        workflow.setViewportJson("{\"x\":0,\"y\":0,\"zoom\":1}");
        return workflow;
    }

    private ProductProcessWorkflowActivation activation(
            Long id, ProductProcessWorkflow workflow, boolean enabled, Long lockVersion) {
        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setId(id);
        activation.setFactoryId(workflow.getFactoryId());
        activation.setProductTypeId(workflow.getProductTypeId());
        activation.setActiveWorkflowId(workflow.getId());
        activation.setActiveDefinitionVersion(workflow.getDefinitionVersion());
        activation.setEnabled(enabled);
        activation.setActivatedBy(7001L);
        activation.setActivatedAt(LocalDateTime.of(2026, 7, 11, 10, 0));
        activation.setLockVersion(lockVersion);
        return activation;
    }
}
