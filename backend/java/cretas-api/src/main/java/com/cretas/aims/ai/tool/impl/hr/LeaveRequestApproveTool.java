package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.hr.LeaveRequest;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.hr.LeaveRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 批准/驳回请假申请工具 (AIChat).
 *
 * <p>WRITE Tool with TCC preview. Wraps {@link LeaveRequestService#approve} (APPROVED
 * + 自动扣减 LeaveBalance for tracked types + 发布 LeaveApprovedEvent) and
 * {@link LeaveRequestService#reject} (REJECTED + 记录 rejectReason).
 *
 * <p>Closes <a href="https://github.com/cretas/aims/issues/848">#848</a> PHRASE_MATCH
 * gap — "批准请假" previously routed to ATTENDANCE_STATS because no HR_LEAVE_APPROVE
 * Tool existed. Companion to {@link LeaveRequestSubmitTool} (Sprint 5 Tool 4).
 *
 * <p>防呆设计:
 * <ul>
 *   <li>R1 (max 显示): doPreview 查询 LeaveRequest by ID, 显示 {申请人 / 类型 / 起止 /
 *       时长 / 当前状态}, 仅当 status=SUBMITTED 时 canDo=true. 让审批人在执行前看清
 *       自己批的是什么 (年龄大的主管最怕被 AI 一键批了不知道是谁的什么假).</li>
 *   <li>R2 (context): 返回 message 含 审批人 + 申请人 + 决定 + leaveRequestId + 终态.</li>
 *   <li>R3 (decision 约束选择): decision 参数 enum APPROVE/REJECT, 不允许自由文本.
 *       REJECT 时强制要求 notes (作为 rejectReason).</li>
 *   <li>R4 (幂等): Service 内 status 校验 → 重复 approve/reject 抛 BusinessException
 *       ("仅 SUBMITTED 可审批, 当前=APPROVED"). Tool catch 后转 DUPLICATE 状态 + actionHint.</li>
 *   <li>4位一体 error: BusinessException.message 透传, ResourceNotFoundException →
 *       NOT_FOUND 状态; throw 让上层 GlobalExceptionHandler 处理 sticky toast.</li>
 * </ul>
 *
 * Intent Code: HR_LEAVE_APPROVE
 *
 * @author Cretas Team — Sprint 5 follow-up #848
 * @since 2026-05-18
 */
@Slf4j
@Component
public class LeaveRequestApproveTool extends AbstractBusinessTool {

    @Autowired
    private LeaveRequestService leaveRequestService;

    @Autowired
    private UserRepository userRepository;

    @Override
    public String getToolName() {
        return "hr_leave_approve";
    }

    @Override
    public String getDescription() {
        return "批准或驳回请假申请 — decision=APPROVE 通过 (审批后自动扣减余额并发布事件), "
                + "decision=REJECT 驳回 (须填 notes 作为驳回原因). "
                + "用户说 '批准请假 LR-001' / '同意张三的请假' / '驳回这个请假申请 原因 X' 时调用. "
                + "Preview 阶段显示申请人/类型/期间/时长/当前状态, Execute 阶段调用 Service.approve 或 reject.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("leaveRequestId", Map.of(
                "type", "string",
                "description", "请假申请 ID (LeaveRequest.id, UUID)"));
        properties.put("decision", Map.of(
                "type", "string",
                "description", "审批决定: APPROVE (通过) / REJECT (驳回)",
                "enum", List.of("APPROVE", "REJECT")));
        properties.put("notes", Map.of(
                "type", "string",
                "description", "审批备注 — APPROVE 时可选, REJECT 时必填 (作为 rejectReason)"));

        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("leaveRequestId", "decision"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("leaveRequestId", "decision");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    /**
     * Override naming convention default (returns READ for non-_create/_update/_delete suffix).
     * This Tool genuinely transitions LeaveRequest.status → WRITE semantics.
     */
    @Override
    public ActionType getActionType() {
        return ActionType.WRITE;
    }

    /** WRITE Tool with preview gate → MEDIUM risk (mirror AbstractBusinessTool default for WRITE). */
    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.MEDIUM;
    }

    /**
     * R1 防呆: 查 LeaveRequest by ID, 显示 申请人 + 类型 + 期间 + 时长 + 当前状态,
     * 让审批人在执行前看清"我要批的是谁的什么假". 不修改任何数据.
     */
    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        Long approverId = getUserId(context);
        if (approverId == null) {
            return Map.of(
                    "status", "PREVIEW_ERROR",
                    "message", "无法识别当前审批人, 请重新登录后再试");
        }

        String leaveRequestId;
        String decisionRaw;
        Decision decision;
        String notes;
        // R4位一体: surface friendly validation errors at param-parse stage
        try {
            leaveRequestId = requireString(params, "leaveRequestId");
            decisionRaw = requireString(params, "decision");
            decision = parseDecision(decisionRaw);
            notes = getString(params, "notes");
            validateNotesForDecision(decision, notes);
        } catch (BusinessException e) {
            return Map.of(
                    "status", "PREVIEW_INVALID",
                    "message", "⚠️ " + e.getMessage(),
                    "code", e.getCode() != null ? e.getCode() : 400);
        }

        Optional<LeaveRequest> reqOpt = leaveRequestService.getById(factoryId, leaveRequestId);
        if (reqOpt.isEmpty()) {
            return Map.of(
                    "status", "PREVIEW_NOT_FOUND",
                    "message", String.format("⚠️ 请假申请 %s 不存在 (工厂 %s)", leaveRequestId, factoryId),
                    "code", 404);
        }
        LeaveRequest req = reqOpt.get();

        // R1: 仅 SUBMITTED 状态可审批
        boolean canDo = req.getStatus() == HrRequestStatus.SUBMITTED;
        long days = ChronoUnit.DAYS.between(req.getStartDate(), req.getEndDate()) + 1;
        String requestorName = lookupUserName(req.getUserId());

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("status", "PREVIEW");
        preview.put("leaveRequestId", leaveRequestId);
        preview.put("decision", decision.name());
        preview.put("approverId", approverId);
        preview.put("requestorUserId", req.getUserId());
        preview.put("requestorName", requestorName);
        preview.put("leaveType", req.getLeaveType().name());
        preview.put("startDate", req.getStartDate().toString());
        preview.put("endDate", req.getEndDate().toString());
        preview.put("requestedDays", days);
        preview.put("requestedHours", req.getDurationHours());
        preview.put("reason", req.getReason() != null ? req.getReason() : "");
        preview.put("currentStatus", req.getStatus().name());
        preview.put("canDo", canDo);
        if (notes != null && !notes.isBlank()) {
            preview.put("notes", notes);
        }

        // R2 context: 含 审批人 / 申请人 / 类型 / 期间 / 时长 / 决定 / 当前状态
        String actionLabel = decision == Decision.APPROVE ? "批准" : "驳回";
        String msg;
        if (canDo) {
            msg = String.format(
                    "✓ 审批人 %d 将 %s 申请 %s: 申请人 %s (id=%d) 的 %s 申请 %s ~ %s (%d 天, %sh). 当前状态=SUBMITTED, 可执行.",
                    approverId, actionLabel, leaveRequestId,
                    requestorName, req.getUserId(),
                    req.getLeaveType().name(),
                    req.getStartDate(), req.getEndDate(),
                    days, req.getDurationHours());
        } else {
            msg = String.format(
                    "⚠️ 申请 %s 当前状态=%s, 仅 SUBMITTED 状态可审批 (申请人 %s, %s, %s ~ %s). 无法 %s.",
                    leaveRequestId, req.getStatus().name(),
                    requestorName, req.getLeaveType().name(),
                    req.getStartDate(), req.getEndDate(),
                    actionLabel);
        }
        preview.put("message", msg);

        log.info("LeaveRequestApprove PREVIEW: factory={} approver={} leaveRequest={} decision={} requestor={} status={} canDo={}",
                factoryId, approverId, leaveRequestId, decision, req.getUserId(), req.getStatus(), canDo);
        return preview;
    }

    /**
     * Execute: APPROVE → {@link LeaveRequestService#approve}, REJECT →
     * {@link LeaveRequestService#reject}. Service 内部:
     * <ul>
     *   <li>APPROVE: status SUBMITTED → APPROVED, approverIds 写入, balance.usedHours +=
     *       durationHours (tracked types), publish LeaveApprovedEvent</li>
     *   <li>REJECT: status SUBMITTED → REJECTED, rejectReason 写入</li>
     * </ul>
     *
     * R4 防呆: 捕获 BusinessException (state 校验失败, e.g. "仅 SUBMITTED 可审批") → DUPLICATE
     * 状态; ResourceNotFoundException → NOT_FOUND 状态; 其他 BusinessException → ERROR.
     */
    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        Long approverId = getUserId(context);
        if (approverId == null) {
            return Map.of(
                    "status", "ERROR",
                    "message", "无法识别当前审批人, 请重新登录后再试");
        }

        String leaveRequestId;
        Decision decision;
        String notes;
        // R4位一体: friendly validation errors at param-parse stage (mirrors doPreview)
        try {
            leaveRequestId = requireString(params, "leaveRequestId");
            decision = parseDecision(requireString(params, "decision"));
            notes = getString(params, "notes");
            validateNotesForDecision(decision, notes);
        } catch (BusinessException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "ERROR");
            err.put("message", "⚠️ " + e.getMessage());
            err.put("actionHint", "请检查输入参数后重试");
            err.put("code", e.getCode() != null ? e.getCode() : 400);
            log.warn("LeaveRequestApprove param-parse ERROR: factory={} approver={} msg={}",
                    factoryId, approverId, e.getMessage());
            return err;
        }

        try {
            LeaveRequest result;
            if (decision == Decision.APPROVE) {
                result = leaveRequestService.approve(factoryId, approverId, leaveRequestId);
                if (notes != null && !notes.isBlank()) {
                    // Service.approve 不接收 notes — 仅日志, 不破坏现有签名
                    log.info("LeaveRequestApprove APPROVE notes (not persisted by Service): leaveRequest={} approver={} notes={}",
                            leaveRequestId, approverId, notes);
                }
            } else {
                result = leaveRequestService.reject(factoryId, approverId, leaveRequestId, notes);
            }

            // R2 context: 含 leaveRequestId + 审批人 + 申请人 + 决定 + 终态
            String requestorName = lookupUserName(result.getUserId());
            String actionLabel = decision == Decision.APPROVE ? "批准" : "驳回";
            String msg = String.format(
                    "✓ 审批人 %d 已%s申请 %s: 申请人 %s (id=%d) 的 %s 申请 %s ~ %s (%sh), 终态=%s",
                    approverId, actionLabel, leaveRequestId,
                    requestorName, result.getUserId(),
                    result.getLeaveType().name(),
                    result.getStartDate(), result.getEndDate(),
                    result.getDurationHours(), result.getStatus().name());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", result.getId());
            data.put("decision", decision.name());
            data.put("approverId", approverId);
            data.put("requestorUserId", result.getUserId());
            data.put("requestorName", requestorName);
            data.put("leaveType", result.getLeaveType().name());
            data.put("startDate", result.getStartDate().toString());
            data.put("endDate", result.getEndDate().toString());
            data.put("durationHours", result.getDurationHours());
            data.put("status", result.getStatus().name());
            if (result.getApprovedAt() != null) {
                data.put("approvedAt", result.getApprovedAt().toString());
            }
            if (result.getRejectedAt() != null) {
                data.put("rejectedAt", result.getRejectedAt().toString());
            }
            if (result.getRejectReason() != null) {
                data.put("rejectReason", result.getRejectReason());
            }

            log.info("LeaveRequestApprove {} DONE: leaveRequest={} approver={} requestor={} status={}",
                    decision, leaveRequestId, approverId, result.getUserId(), result.getStatus());
            return buildSimpleResult(msg, data);

        } catch (ResourceNotFoundException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "NOT_FOUND");
            err.put("message", String.format("⚠️ 请假申请 %s 不存在 (工厂 %s)", leaveRequestId, factoryId));
            err.put("actionHint", "请确认 leaveRequestId 是否正确");
            err.put("code", 404);
            log.warn("LeaveRequestApprove NOT_FOUND: factory={} approver={} leaveRequest={}",
                    factoryId, approverId, leaveRequestId);
            return err;
        } catch (BusinessException e) {
            // R4 防呆: state 校验失败 (e.g. "仅 SUBMITTED 可审批, 当前=APPROVED")
            // 通常是 重复提交 / 已撤回 / 已驳回 → 转 DUPLICATE 状态
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("仅 SUBMITTED") || msg.contains("当前=APPROVED")
                    || msg.contains("当前=REJECTED") || msg.contains("当前=CANCELLED")
                    || msg.contains("当前=DRAFT")) {
                Map<String, Object> dup = new LinkedHashMap<>();
                dup.put("status", "DUPLICATE");
                dup.put("message", "⚠️ " + msg);
                dup.put("actionHint", "请刷新查看申请最新状态, 如需修改请联系申请人撤回后重新提交");
                log.warn("LeaveRequestApprove DUPLICATE: factory={} approver={} leaveRequest={}: {}",
                        factoryId, approverId, leaveRequestId, msg);
                return dup;
            }
            // 其他 BusinessException — 4位一体: surface friendly message
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "ERROR");
            err.put("message", "⚠️ " + msg);
            err.put("actionHint", "请检查申请信息或联系管理员");
            err.put("code", e.getCode() != null ? e.getCode() : 400);
            log.warn("LeaveRequestApprove ERROR: factory={} approver={} leaveRequest={}: code={} msg={}",
                    factoryId, approverId, leaveRequestId, e.getCode(), msg);
            return err;
        }
    }

    /** Helper: required-string param with friendly error. */
    private String requireString(Map<String, Object> params, String key) {
        String v = getString(params, key);
        if (v == null || v.isBlank()) {
            throw new BusinessException(key + " 必填");
        }
        return v.trim();
    }

    /** Helper: parse decision enum, friendly error. */
    private Decision parseDecision(String raw) {
        try {
            return Decision.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("不支持的审批决定: " + raw + ". 可选: APPROVE / REJECT");
        }
    }

    /** Helper: REJECT 必填 notes (作为 rejectReason), APPROVE 可选. */
    private void validateNotesForDecision(Decision decision, String notes) {
        if (decision == Decision.REJECT && (notes == null || notes.isBlank())) {
            throw new BusinessException("驳回 (REJECT) 时 notes 必填, 作为驳回原因 (rejectReason)");
        }
    }

    /** Helper: 查 user.fullName / username 作为申请人显示名, 查不到返回 "用户 <id>". */
    private String lookupUserName(Long userId) {
        if (userId == null) return "未知用户";
        try {
            return userRepository.findById(userId)
                    .map(u -> {
                        String name = u.getFullName();
                        if (name != null && !name.isBlank()) return name;
                        return u.getUsername() != null ? u.getUsername() : ("用户 " + userId);
                    })
                    .orElse("用户 " + userId);
        } catch (Exception e) {
            // Defensive — user lookup failure shouldn't break approval flow
            log.warn("lookupUserName failed for userId={}: {}", userId, e.getMessage());
            return "用户 " + userId;
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "leaveRequestId", "请提供要审批的请假申请 ID (leaveRequestId)。",
                "decision", "请选择审批决定: APPROVE (通过) 或 REJECT (驳回)。",
                "notes", "请填写驳回原因 (REJECT 时必填)。"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> names = Map.of(
                "leaveRequestId", "请假申请ID",
                "decision", "审批决定",
                "notes", "审批备注"
        );
        return names.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** Decision enum — restrict to APPROVE/REJECT (Rule 3 约束选择, 非自由文本). */
    private enum Decision {
        APPROVE,
        REJECT
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
