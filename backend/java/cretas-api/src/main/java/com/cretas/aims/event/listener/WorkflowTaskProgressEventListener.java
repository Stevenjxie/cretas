package com.cretas.aims.event.listener;

import com.cretas.aims.event.WorkflowTaskProgressRequestedEvent;
import com.cretas.aims.service.processentry.WorkflowTaskProgressWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowTaskProgressEventListener {

    private final WorkflowTaskProgressWriter writer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkflowTaskProgressRequested(WorkflowTaskProgressRequestedEvent event) {
        try {
            writer.stamp(event);
        } catch (Exception e) {
            // The process-sheet row is already committed. Progress projection is best-effort and isolated.
            log.warn("Workflow 任务进度回写失败 (不影响逐道录入): planId={}, processOrder={}, err={}",
                    event.planId(), event.processOrder(), e.getMessage());
        }
    }
}
