package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.event.WorkflowTaskProgressRequestedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkflowTaskProgressWriter {

    private final WorkProcessTaskRepository taskRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stamp(WorkflowTaskProgressRequestedEvent event) {
        WorkProcessTask task = taskRepository
                .findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(
                        event.factoryId(), event.workflowInstanceId())
                .stream()
                .filter(candidate -> event.workflowNodeId().equals(candidate.getWorkflowNodeId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(409, "Workflow 工序任务不存在，无法回写报工进度")
                        .withCode("WORKFLOW_RUNTIME_TASK_NOT_FOUND"));
        if (event.actualQuantity() != null) {
            task.setActualQuantity(event.actualQuantity());
        }
        task.setStatus(WorkProcessTask.Status.COMPLETED);
        taskRepository.saveAndFlush(task);
    }
}
