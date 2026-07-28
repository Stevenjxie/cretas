package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowRevisionSnapshotServiceTest {

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_ID = "PT-001";

    @Mock
    private ProductProcessWorkflowRevisionRepository revisionRepository;

    @Mock
    private BomRecipeRepository recipeRepository;

    private WorkflowRevisionSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowRevisionSnapshotService(
                revisionRepository,
                recipeRepository,
                new ProductProcessWorkflowValidator(),
                new ObjectMapper());
    }

    @Test
    void canonicalHashSurvivesJsonbObjectKeyReorderingAndWhitespace() {
        ProductProcessWorkflow first = workflow(
                """
                [{"id":"raw","kind":"RAW_MATERIAL","position":{"x":1,"y":2},"data":{"label":"A","ports":[]}}]
                """,
                """
                [{"id":"edge-1","source":"raw","sourceHandle":"out","target":"process","targetHandle":"in"}]
                """,
                "{\"x\":0,\"y\":0,\"zoom\":1}");
        ProductProcessWorkflow jsonbReadback = workflow(
                """
                [ { "data": { "ports": [], "label": "A" }, "kind": "RAW_MATERIAL",
                    "position": { "y": 2, "x": 1 }, "id": "raw" } ]
                """,
                """
                [{"targetHandle":"in","target":"process","sourceHandle":"out","source":"raw","id":"edge-1"}]
                """,
                "{\"zoom\":1,\"y\":0,\"x\":0}");

        assertEquals(service.hash(first), service.hash(jsonbReadback));
    }

    @Test
    void unpinnedDraftSaveOverwritesCurrentRevision() {
        ProductProcessWorkflow workflow = workflow(
                "[{\"id\":\"raw-new\",\"kind\":\"RAW_MATERIAL\",\"position\":{\"x\":2,\"y\":3},\"data\":{}}]",
                "[]",
                "{\"x\":5,\"y\":6,\"zoom\":1}");
        ProductProcessWorkflowRevision current = revision(
                9L,
                "[{\"id\":\"raw-old\",\"kind\":\"RAW_MATERIAL\",\"position\":{\"x\":1,\"y\":1},\"data\":{}}]",
                "[]",
                "{\"x\":0,\"y\":0,\"zoom\":1}");
        current.setRevisionHash(service.hash(current));
        workflow.setCurrentRevisionId(current.getId());
        workflow.setCurrentRevisionHash(current.getRevisionHash());
        when(revisionRepository.findByIdAndFactoryId(9L, FACTORY_ID))
                .thenReturn(Optional.of(current));
        when(revisionRepository.findByWorkflowIdAndRevisionHash(workflow.getId(), service.hash(workflow)))
                .thenReturn(Optional.empty());
        when(revisionRepository.saveAndFlush(current)).thenReturn(current);

        ProductProcessWorkflowRevision saved = service.captureDraft(workflow);

        assertSame(current, saved);
        assertEquals(9L, saved.getId());
        assertEquals(service.hash(workflow), saved.getRevisionHash());
        assertEquals(service.hash(saved), saved.getRevisionHash());
        verify(revisionRepository).saveAndFlush(current);
        verify(revisionRepository, never()).findMaxRevisionNumber(any());
    }

    @Test
    void invalidCurrentDraftHashIsRepairedInPlaceWhenNothingPinsIt() {
        ProductProcessWorkflow workflow = workflow(
                "[{\"id\":\"raw\",\"kind\":\"RAW_MATERIAL\",\"position\":{\"x\":2,\"y\":3},\"data\":{}}]",
                "[]",
                "{\"x\":5,\"y\":6,\"zoom\":1}");
        ProductProcessWorkflowRevision current = revision(
                9L,
                "[{\"data\":{},\"position\":{\"y\":3,\"x\":2},\"kind\":\"RAW_MATERIAL\",\"id\":\"raw\"}]",
                "[]",
                "{\"zoom\":1,\"y\":6,\"x\":5}");
        current.setRevisionHash("legacy-pre-jsonb-hash");
        workflow.setCurrentRevisionId(current.getId());
        workflow.setCurrentRevisionHash(current.getRevisionHash());
        when(revisionRepository.findByIdAndFactoryId(9L, FACTORY_ID))
                .thenReturn(Optional.of(current));
        when(revisionRepository.findByWorkflowIdAndRevisionHash(workflow.getId(), service.hash(workflow)))
                .thenReturn(Optional.empty());
        when(revisionRepository.saveAndFlush(current)).thenReturn(current);

        ProductProcessWorkflowRevision repaired = service.captureDraft(workflow);

        assertSame(current, repaired);
        assertEquals(service.hash(workflow), repaired.getRevisionHash());
        assertEquals(service.hash(repaired), repaired.getRevisionHash());
    }

    @ParameterizedTest
    @EnumSource(BomRecipe.Status.class)
    void anyBomPinFreezesRevisionAndNextSaveCreatesANewOne(BomRecipe.Status status) {
        ProductProcessWorkflow workflow = workflow(
                "[{\"id\":\"raw-new\",\"kind\":\"RAW_MATERIAL\",\"position\":{\"x\":2,\"y\":3},\"data\":{}}]",
                "[]",
                "{\"x\":5,\"y\":6,\"zoom\":1}");
        ProductProcessWorkflowRevision current = revision(
                9L,
                "[{\"id\":\"raw-old\",\"kind\":\"RAW_MATERIAL\",\"position\":{\"x\":1,\"y\":1},\"data\":{}}]",
                "[]",
                "{\"x\":0,\"y\":0,\"zoom\":1}");
        current.setRevisionHash(service.hash(current));
        String frozenHash = current.getRevisionHash();
        String frozenNodes = current.getNodesJson();
        workflow.setCurrentRevisionId(current.getId());
        workflow.setCurrentRevisionHash(current.getRevisionHash());
        when(revisionRepository.findByIdAndFactoryId(9L, FACTORY_ID))
                .thenReturn(Optional.of(current));
        when(revisionRepository.findByWorkflowIdAndRevisionHash(workflow.getId(), service.hash(workflow)))
                .thenReturn(Optional.empty());
        when(recipeRepository.findByFactoryIdAndWorkflowRevisionIdAndStatusOrderByProductTypeIdAsc(
                eq(FACTORY_ID), eq(9L), any(BomRecipe.Status.class)))
                .thenAnswer(invocation -> invocation.getArgument(2) == status
                        ? List.of(BomRecipe.builder().status(status).build())
                        : List.of());
        when(revisionRepository.findMaxRevisionNumber(workflow.getId())).thenReturn(1);
        when(revisionRepository.saveAndFlush(any(ProductProcessWorkflowRevision.class)))
                .thenAnswer(invocation -> {
                    ProductProcessWorkflowRevision created = invocation.getArgument(0);
                    created.setId(10L);
                    return created;
                });

        ProductProcessWorkflowRevision created = service.captureDraft(workflow);

        assertNotSame(current, created);
        assertEquals(10L, created.getId());
        assertEquals(2, created.getRevisionNumber());
        assertEquals(frozenHash, current.getRevisionHash());
        assertEquals(frozenNodes, current.getNodesJson());
        assertNotEquals(current.getRevisionHash(), created.getRevisionHash());
    }

    @Test
    void validLegacyStoredOrderHashRemainsReadable() throws Exception {
        ProductProcessWorkflowRevision revision = revision(
                9L,
                "[{\"id\":\"raw\",\"kind\":\"RAW_MATERIAL\",\"position\":{\"y\":1,\"x\":1},\"data\":{}}]",
                "[]",
                "{\"zoom\":1,\"y\":0,\"x\":0}");
        String legacyHash = rawHash(revision);
        revision.setRevisionHash(legacyHash);

        assertEquals(legacyHash, service.hash(revision));
    }

    @Test
    void semanticallyUnchangedLegacyRevisionIsReusedWithoutRewritingPinnedHistory() throws Exception {
        ProductProcessWorkflowRevision current = revision(
                9L,
                "[{\"id\":\"raw\",\"kind\":\"RAW_MATERIAL\",\"position\":{\"y\":1,\"x\":1},\"data\":{}}]",
                "[]",
                "{\"zoom\":1,\"y\":0,\"x\":0}");
        current.setRevisionHash(rawHash(current));
        ProductProcessWorkflow workflow = workflow(
                "[{\"data\":{},\"position\":{\"x\":1,\"y\":1},\"kind\":\"RAW_MATERIAL\",\"id\":\"raw\"}]",
                "[]",
                "{\"x\":0,\"y\":0,\"zoom\":1}");
        workflow.setCurrentRevisionId(current.getId());
        workflow.setCurrentRevisionHash(current.getRevisionHash());
        when(revisionRepository.findByIdAndFactoryId(9L, FACTORY_ID))
                .thenReturn(Optional.of(current));

        ProductProcessWorkflowRevision reused = service.captureDraft(workflow);

        assertSame(current, reused);
        verify(revisionRepository, never()).saveAndFlush(any());
        verify(recipeRepository, never())
                .findByFactoryIdAndWorkflowRevisionIdAndStatusOrderByProductTypeIdAsc(
                        any(), any(), any());
    }

    private ProductProcessWorkflow workflow(String nodes, String edges, String viewport) {
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(7L);
        workflow.setFactoryId(FACTORY_ID);
        workflow.setProductTypeId(PRODUCT_ID);
        workflow.setStatus(ProductProcessWorkflow.Status.DRAFT);
        workflow.setDefinitionVersion(1);
        workflow.setSchemaVersion(1);
        workflow.setNodesJson(nodes);
        workflow.setEdgesJson(edges);
        workflow.setViewportJson(viewport);
        return workflow;
    }

    private ProductProcessWorkflowRevision revision(
            Long id, String nodes, String edges, String viewport) {
        ProductProcessWorkflowRevision revision = new ProductProcessWorkflowRevision();
        revision.setId(id);
        revision.setFactoryId(FACTORY_ID);
        revision.setProductTypeId(PRODUCT_ID);
        revision.setWorkflowId(7L);
        revision.setStatus(ProductProcessWorkflowRevision.Status.DRAFT);
        revision.setDefinitionVersion(1);
        revision.setRevisionNumber(1);
        revision.setSchemaVersion(1);
        revision.setNodesJson(nodes);
        revision.setEdgesJson(edges);
        revision.setViewportJson(viewport);
        return revision;
    }

    private String rawHash(ProductProcessWorkflowRevision revision) throws Exception {
        String source = revision.getFactoryId() + "\n"
                + revision.getProductTypeId() + "\n"
                + revision.getDefinitionVersion() + "\n"
                + revision.getSchemaVersion() + "\n"
                + revision.getNodesJson() + "\n"
                + revision.getEdgesJson() + "\n"
                + revision.getViewportJson();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
    }
}
