package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.event.WorkflowTaskProgressRequestedEvent;
import com.cretas.aims.event.listener.WorkflowTaskProgressEventListener;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowTaskProgressWriterTest {

    @Test
    void stampsExactWorkflowNodeInIndependentWriter() {
        WorkProcessTaskRepository repository = mock(WorkProcessTaskRepository.class);
        WorkflowTaskProgressWriter writer = new WorkflowTaskProgressWriter(repository);
        WorkProcessTask first = task("node-1");
        WorkProcessTask target = task("node-2");
        when(repository.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc("F006", 31L))
                .thenReturn(List.of(first, target));

        writer.stamp(new WorkflowTaskProgressRequestedEvent(
                "F006", "PLAN-WF", 31L, "node-2", 2, new BigDecimal("48")));

        assertEquals(WorkProcessTask.Status.COMPLETED, target.getStatus());
        assertEquals(new BigDecimal("48"), target.getActualQuantity());
        verify(repository).saveAndFlush(target);
    }

    @Test
    void afterCommitListenerContainsProjectionFailure() {
        WorkflowTaskProgressWriter writer = mock(WorkflowTaskProgressWriter.class);
        WorkflowTaskProgressEventListener listener = new WorkflowTaskProgressEventListener(writer);
        WorkflowTaskProgressRequestedEvent event = new WorkflowTaskProgressRequestedEvent(
                "F006", "PLAN-WF", 31L, "node-2", 2, BigDecimal.ONE);
        doThrow(new IllegalStateException("projection failed")).when(writer).stamp(event);

        assertDoesNotThrow(() -> listener.onWorkflowTaskProgressRequested(event));
    }

    private WorkProcessTask task(String nodeId) {
        WorkProcessTask task = new WorkProcessTask();
        task.setWorkflowNodeId(nodeId);
        task.setStatus(WorkProcessTask.Status.PENDING);
        return task;
    }
}
