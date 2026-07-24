package com.cretas.aims.event;

import com.cretas.aims.client.LabelQcAnalysisClient;
import com.cretas.aims.service.LabelQcAnalysisWorker;
import com.cretas.aims.service.LabelQcAnalysisWorker.AnalysisBatch;
import com.cretas.aims.service.attachment.AttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class LabelQcAnalysisListener {

    private final LabelQcAnalysisWorker worker;
    private final LabelQcAnalysisClient client;
    private final AttachmentService attachmentService;

    @Async("aiAnalysisExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(LabelQcAnalysisRequestedEvent event) {
        AnalysisBatch batch;
        try {
            batch = worker.start(event.taskId());
        } catch (Exception ex) {
            log.warn("Label QC task {} could not enter ANALYZING: {}",
                    event.taskId(), ex.getMessage());
            return;
        }
        if (batch == null) {
            return;
        }
        for (var photo : batch.photos()) {
            try {
                String downloadUrl = attachmentService.generateDownloadUrl(
                        batch.factoryId(), photo.attachmentId());
                var result = client.analyze(downloadUrl, batch.factoryId(), photo.photoId());
                worker.saveSuccess(batch.factoryId(), batch.taskId(), photo.photoId(), result);
            } catch (Exception ex) {
                log.warn("Label QC photo {} analysis failed: {}", photo.photoId(), ex.getMessage());
                worker.saveFailure(
                        batch.factoryId(),
                        batch.taskId(),
                        photo.photoId(),
                        concise(ex.getMessage()));
            }
        }
        worker.finish(batch.factoryId(), batch.taskId());
    }

    private String concise(String message) {
        if (message == null || message.isBlank()) return "视觉服务异常";
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
