package com.cretas.aims.repository.workflow;

import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowTaskPortRepository extends JpaRepository<WorkflowTaskPort, Long> {

    List<WorkflowTaskPort> findByFactoryIdAndWorkflowInstanceId(
            String factoryId,
            Long workflowInstanceId);
}
