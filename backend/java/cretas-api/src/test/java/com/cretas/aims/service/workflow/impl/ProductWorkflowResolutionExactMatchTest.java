package com.cretas.aims.service.workflow.impl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 与被测类同包 —— smallestMissingSiblings 是 package-private，跨包访问编译不过。
 * 该目录已有 WorkflowEngineServiceImplTest 等先例。
 */
class ProductWorkflowResolutionExactMatchTest {

    @Test
    void reportsFewestMissingSiblingsFromTheClosestSupersetWorkflow() {
        List<String> missing = ProductWorkflowResolutionServiceImpl.smallestMissingSiblings(
                Set.of("FG-1"),
                List.of(Set.of("FG-1", "FG-2", "FG-3"), Set.of("FG-1", "FG-2")));

        assertEquals(List.of("FG-2"), missing);
    }

    @Test
    void reportsNothingWhenNoWorkflowCoversTheRequestedSet() {
        List<String> missing = ProductWorkflowResolutionServiceImpl.smallestMissingSiblings(
                Set.of("FG-9"), List.of(Set.of("FG-1", "FG-2")));

        assertTrue(missing.isEmpty());
    }

    @Test
    void reportsNothingWhenAWorkflowAlreadyMatchesExactly() {
        List<String> missing = ProductWorkflowResolutionServiceImpl.smallestMissingSiblings(
                Set.of("FG-1", "FG-2"), List.of(Set.of("FG-1", "FG-2")));

        assertTrue(missing.isEmpty());
    }

    @Test
    void ordersMissingSiblingsDeterministically() {
        List<String> missing = ProductWorkflowResolutionServiceImpl.smallestMissingSiblings(
                Set.of("FG-1"), List.of(Set.of("FG-3", "FG-1", "FG-2")));

        assertEquals(List.of("FG-2", "FG-3"), missing);
    }
}
