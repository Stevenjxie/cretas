package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.foodsafety.RecallAction;
import com.cretas.aims.entity.foodsafety.RecallEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.RecallActionRepository;
import com.cretas.aims.repository.foodsafety.RecallEventRepository;
import com.cretas.aims.service.OssService;
import com.cretas.aims.service.foodsafety.RegulatoryReportPdfService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 监管上报文件生成 Tool — Sprint 8 P3 Phase B (食品召回闭环 step 5c).
 *
 * <p>基于召回事件 + 关联 actions 生成监管上报文件 (markdown + PDF link).
 * Phase B 简化: 生成 markdown 报告内容 + 调度 PDF 生成 (复用 PurchaseOrderPdfService pattern).
 *
 * <p>实际 PDF 生成 TODO: Phase C 接 PrintService / 调 FoodSafetyRecallPdfService (待 build).
 * 当前先返回 markdown 报告全文 + 上报文件 metadata.
 *
 * <p>同时记 RecallAction (REGULATORY_REPORT type) 用于 audit + 状态流转.
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"生成召回上报文件"</li>
 *   <li>"食药监上报"</li>
 *   <li>"召回报告"</li>
 * </ul>
 *
 * <p>Intent Code: {@code REGULATORY_REPORT}
 */
@Slf4j
@Component
public class RegulatoryReportGenerateTool extends AbstractBusinessTool {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Autowired
    private RecallEventRepository recallEventRepository;

    @Autowired
    private RecallActionRepository recallActionRepository;

    @Autowired
    private RegulatoryReportPdfService regulatoryReportPdfService;

    @Autowired
    private OssService ossService;

    @Override
    public String getToolName() {
        return "regulatory_report_generate";
    }

    @Override
    public String getDescription() {
        return "生成监管上报文件 (食药监召回报告) — 基于召回事件 + 关联 actions 生成 markdown 报告 + "
                + "PDF 下载链接. LLM 触发: '生成召回上报文件' / '食药监上报' / '召回报告' / "
                + "'监管文件'. 召回 SOP 监管上报 step.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> recallEventId = new HashMap<>();
        recallEventId.put("type", "integer");
        recallEventId.put("description", "RecallEvent ID (必填)");
        properties.put("recallEventId", recallEventId);

        schema.put("properties", properties);
        schema.put("required", List.of("recallEventId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("recallEventId");
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.GENERATE;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        Long recallEventId = getLong(params, "recallEventId");
        Long userId = getUserId(context);

        log.info("regulatory_report_generate — factory={} event={} userId={}",
                factoryId, recallEventId, userId);

        Optional<RecallEvent> opt = recallEventRepository.findById(recallEventId);
        if (opt.isEmpty()) {
            throw new BusinessException(404,
                    String.format("RecallEvent #%d 不存在", recallEventId));
        }
        RecallEvent event = opt.get();
        if (!factoryId.equals(event.getFactoryId())) {
            throw new BusinessException(403,
                    String.format("RecallEvent #%d 属于其他工厂", recallEventId));
        }

        List<RecallAction> actions = recallActionRepository
                .findByRecallEventIdOrderByCreatedAtAsc(recallEventId);

        // 生成 markdown 报告 (Phase B — markdown only, PDF Phase C)
        StringBuilder md = new StringBuilder();
        md.append("# 食品召回事件监管上报报告\n\n");
        md.append("**报告生成时间**: ").append(LocalDateTime.now().format(DATE_FMT)).append("\n\n");
        md.append("**召回事件编号**: ").append(event.getEventCode()).append("\n\n");
        md.append("## 一、基本信息\n\n");
        md.append("| 项 | 内容 |\n|---|---|\n");
        md.append("| 工厂ID | ").append(event.getFactoryId()).append(" |\n");
        md.append("| 召回编号 | ").append(event.getEventCode()).append(" |\n");
        md.append("| 涉事产品类别 | ").append(event.getAffectedProductCategory()).append(" |\n");
        md.append("| 触发时间 | ").append(event.getTriggerTime() != null
                ? event.getTriggerTime().format(DATE_FMT) : "-").append(" |\n");
        md.append("| 触发人 (userId) | ").append(event.getTriggeredByUserId()).append(" |\n");
        md.append("| 当前状态 | ").append(event.getStatus()).append(" |\n");
        md.append("| 预估损失 | ¥").append(event.getEstimatedLoss() != null
                ? event.getEstimatedLoss() : "-").append(" |\n\n");

        md.append("## 二、触发原因\n\n");
        md.append(event.getTriggerReason()).append("\n\n");

        md.append("## 三、召回行动记录\n\n");
        md.append("| # | 类型 | 目标 | 状态 | 执行时间 | 备注 |\n");
        md.append("|---|---|---|---|---|---|\n");
        int i = 1;
        for (RecallAction a : actions) {
            md.append("| ").append(i++).append(" | ")
                    .append(a.getActionType()).append(" | ")
                    .append(a.getTargetEntityType()).append("#").append(a.getTargetEntityId()).append(" | ")
                    .append(a.getStatus()).append(" | ")
                    .append(a.getExecutedAt() != null
                            ? a.getExecutedAt().format(DATE_FMT) : "-").append(" | ")
                    .append(a.getExecutionNotes() != null ? a.getExecutionNotes() : "-")
                    .append(" |\n");
        }
        if (actions.isEmpty()) {
            md.append("| - | - | - | - | - | (无召回行动记录) |\n");
        }

        md.append("\n## 四、监管承诺\n\n");
        md.append("我司依据《食品安全法》第六十三条及实施条例, 已采取上述召回措施. ");
        md.append("如需进一步信息, 请联系本企业质量负责人.\n\n");
        md.append("---\n");
        md.append("*本报告由 Cretas 食品溯源系统自动生成.*\n");

        // Sprint 9 P1.2 Fix 2 — 真 PDF 生成 + OSS 上传 (graceful: OSS 不可达保持 markdown)
        String pdfUrl = null;
        String pdfStatus = "GENERATED";
        Integer pdfBytes = null;
        try {
            byte[] pdfBytesArr = regulatoryReportPdfService.generatePdf(event, actions);
            pdfBytes = pdfBytesArr.length;
            String filename = event.getEventCode() + ".pdf";
            try {
                pdfUrl = ossService.uploadBytes(pdfBytesArr,
                        "regulatory-reports", factoryId, filename, "application/pdf");
                pdfStatus = "UPLOADED";
            } catch (UnsupportedOperationException ossDisabled) {
                // OSS 未启用 (NoOpOssServiceImpl 抛此异常) — PDF 已生成但不可托管
                log.warn("OSS 未启用, PDF 已生成 ({} bytes) 但未上传; eventCode={}",
                        pdfBytesArr.length, event.getEventCode());
                pdfStatus = "GENERATED_OSS_UNAVAILABLE";
            } catch (Exception ossErr) {
                log.error("PDF 已生成但上传 OSS 失败; eventCode={}: {}",
                        event.getEventCode(), ossErr.getMessage(), ossErr);
                pdfStatus = "GENERATED_UPLOAD_FAILED";
            }
        } catch (Exception pdfErr) {
            // PDF 生成失败也不阻断 — markdown 已可用 (caller 可降级显示)
            log.error("PDF 生成失败 eventCode={}, 继续保留 markdown 报告: {}",
                    event.getEventCode(), pdfErr.getMessage(), pdfErr);
            pdfStatus = "FAILED";
        }

        // 记 RecallAction
        RecallAction reportAction = RecallAction.builder()
                .recallEventId(recallEventId)
                .actionType("REGULATORY_REPORT")
                .targetEntityType("EVENT")
                .targetEntityId(recallEventId)
                .status("COMPLETED")
                .executedAt(LocalDateTime.now())
                .executionNotes(String.format(
                        "监管上报文件生成: report=%d chars, pdf=%s (status=%s), by user=%d",
                        md.length(),
                        pdfBytes != null ? pdfBytes + " bytes" : "-",
                        pdfStatus,
                        userId))
                .build();
        recallActionRepository.save(reportAction);

        // 更新 RecallEvent status → REPORTED (如果还在 INVESTIGATING/NOTIFYING/FROZEN)
        if (!"REPORTED".equals(event.getStatus()) && !"COMPLETED".equals(event.getStatus())) {
            event.setStatus("REPORTED");
            recallEventRepository.save(event);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recallEventId", recallEventId);
        data.put("eventCode", event.getEventCode());
        data.put("reportMarkdown", md.toString());
        data.put("reportLength", md.length());
        data.put("pdfUrl", pdfUrl);
        data.put("pdfStatus", pdfStatus);
        data.put("pdfBytes", pdfBytes);
        data.put("recallActionId", reportAction.getId());
        data.put("actionHint", "/quality/recall/" + recallEventId + "?tab=report");

        String pdfMsg = pdfUrl != null
                ? String.format("PDF 已生成 (%d bytes) → %s", pdfBytes, pdfUrl)
                : ("UPLOADED".equals(pdfStatus)
                        ? "PDF 已上传"
                        : "PDF 状态: " + pdfStatus);
        String message = String.format(
                "📄 已生成召回上报文件 → %s (%d 字符). RecallEvent #%d 状态 → REPORTED. %s",
                event.getEventCode(), md.length(), recallEventId, pdfMsg);
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
