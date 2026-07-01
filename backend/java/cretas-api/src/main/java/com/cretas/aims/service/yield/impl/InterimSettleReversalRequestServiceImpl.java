package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.production.InterimSettleReversalRequestDTO;
import com.cretas.aims.entity.InterimSettleReversalRequest;
import com.cretas.aims.entity.ProductionInterimSettlement;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.InterimSettleReversalRequestRepository;
import com.cretas.aims.repository.ProductionInterimSettlementRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.yield.InterimSettleReversalRequestService;
import com.cretas.aims.service.yield.InterimSettleReversalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 撤销小结治理编排实现 — 见 {@link InterimSettleReversalRequestService} Javadoc。
 *
 * <p>审批基础设施镜像 SP7 / 半成品盘点: {@code STOCKTAKE_APPROVAL_ROLES}
 * ({@code finance_manager/factory_super_admin/platform_admin}), 角色经 requestRole 参数校验 (非 SecurityContext)。
 * 审批通过<b>内联</b>调用既有 {@link InterimSettleReversalService#reverseInterimSettle} (与 approve 同事务, REQUIRED):
 * 执行侧下游守卫抛 → 整个 approve 回滚, 申请留在 PENDING_APPROVAL (审批不绕过 guard)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterimSettleReversalRequestServiceImpl implements InterimSettleReversalRequestService {

    private final ProductionPlanRepository planRepository;
    private final ProductionInterimSettlementRepository settlementRepository;
    private final InterimSettleReversalRequestRepository requestRepository;
    private final InterimSettleReversalService reversalService;

    /** 1天时间窗 (小时)。小结 postedAt 起算; 超过即不允许撤销 (走盘点调整)。 */
    private static final long REVERSE_WINDOW_HOURS = 24;

    /** 审批角色 (镜像 SP7 / 半成品盘点 STOCKTAKE_APPROVAL_ROLES)。 */
    private static final Set<String> STOCKTAKE_APPROVAL_ROLES = Set.of(
            "finance_manager", "factory_super_admin", "platform_admin");

    // ─────────────────────────────────────────────────────────────
    // ① 撤销申请 (request) — 零库存副作用
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public InterimSettleReversalRequestDTO requestReverse(String factoryId, String planId, Integer sessionSeq,
                                                          String reason, Long userId) {
        ProductionPlan plan = planRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "生产计划不存在: " + planId));
        if (plan.getSourceType() != PlanSourceType.SAFETY_STOCK) {
            throw new BusinessException(400, "仅存货生产计划可撤销小结, 当前来源类型: " + plan.getSourceType())
                    .withHintTarget("撤销小结");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(400, "撤销原因必填")
                    .withCode("INTERIM_REVERSE_REASON_REQUIRED")
                    .withHint("请填写撤销原因")
                    .withHintTarget("reason");
        }

        // 定位目标小结 (取 postedAt 作时间窗锚 + sessionSeq); 已撤销 (硬删) → empty → 拒绝。
        ProductionInterimSettlement settlement = (sessionSeq != null
                ? settlementRepository.findByFactoryIdAndProductionPlanIdAndSessionSeqAndDeletedAtIsNull(
                        factoryId, planId, sessionSeq)
                : settlementRepository.findTopByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqDesc(
                        factoryId, planId))
                .orElseThrow(() -> new BusinessException(409,
                        sessionSeq != null ? "第 " + sessionSeq + " 次小结不存在或已撤销" : "无可撤销的小结")
                        .withCode("INTERIM_SETTLE_NOT_FOUND")
                        .withHint("该小结可能已被撤销, 请刷新后重试")
                        .withHintTarget("撤销小结"));

        int seq = settlement.getSessionSeq() == null ? 0 : settlement.getSessionSeq();
        LocalDateTime postedAt = settlement.getPostedAt();

        // ⏰ 1天时间窗 (申请端): postedAt 起 24h 内才可撤销。
        assertWithinWindow(postedAt, seq);

        // 幂等: 同 (factory, plan, seq) 已有待审批申请 → 拒绝重复。
        requestRepository.findByFactoryIdAndProductionPlanIdAndSessionSeqAndStatus(
                        factoryId, planId, seq, InterimSettleReversalRequest.Status.PENDING_APPROVAL)
                .ifPresent(existing -> {
                    throw new BusinessException(409, "第 " + seq + " 次小结已有待审批的撤销申请")
                            .withCode("INTERIM_REVERSE_REQUEST_PENDING")
                            .withHint("请勿重复提交, 前往审批中心查看")
                            .withHintTarget("撤销小结");
                });

        InterimSettleReversalRequest req = InterimSettleReversalRequest.builder()
                .factoryId(factoryId)
                .productionPlanId(planId)
                .sessionSeq(seq)
                .settlementPostedAt(postedAt)
                .reason(reason.trim())
                .status(InterimSettleReversalRequest.Status.PENDING_APPROVAL)
                .requestedBy(userId)
                .requestedAt(LocalDateTime.now())
                .build();
        InterimSettleReversalRequest saved = requestRepository.save(req);
        log.info("[interim-reverse] 撤销申请已创建 (零副作用): factory={}, plan={}, seq={}, requestId={}, by={}",
                factoryId, planId, seq, saved.getId(), userId);
        return InterimSettleReversalRequestDTO.from(saved);
    }

    // ─────────────────────────────────────────────────────────────
    // ② 审批通过 (approve) → 内联执行逆转
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public InterimSettleReversalRequestDTO approve(String requestId, String factoryId,
                                                   Long approverId, String requestRole) {
        assertApprovalRole(requestRole);
        InterimSettleReversalRequest req = loadPending(requestId, factoryId);

        // ⏰ 1天时间窗 (审批端再校验): 锚点仍是小结 postedAt (非申请时间) —— 审批拖过 24h 则不再允许执行。
        assertWithinWindow(req.getSettlementPostedAt(), req.getSessionSeq());

        // 内联执行既有逆转 (同事务): 悲观锁 + 下游守卫 + 原子。守卫抛 → 整个 approve 回滚, 申请留 PENDING。
        Map<String, Object> result = reversalService.reverseInterimSettle(
                factoryId, req.getProductionPlanId(), req.getSessionSeq(), approverId);

        req.setStatus(InterimSettleReversalRequest.Status.EXECUTED);
        req.setApprovedBy(approverId);
        req.setApprovedAt(LocalDateTime.now());
        req.setExecutedAt(LocalDateTime.now());
        req.setAffectedBatchNumbers(joinAffected(result));
        InterimSettleReversalRequest saved = requestRepository.save(req);
        log.info("[interim-reverse] 撤销申请已审批+执行: requestId={}, factory={}, plan={}, seq={}, approver={}, affected={}",
                requestId, factoryId, req.getProductionPlanId(), req.getSessionSeq(), approverId, saved.getAffectedBatchNumbers());
        return InterimSettleReversalRequestDTO.from(saved);
    }

    // ─────────────────────────────────────────────────────────────
    // ③ 驳回 (reject) — 零副作用
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public InterimSettleReversalRequestDTO reject(String requestId, String factoryId, String reason,
                                                  Long approverId, String requestRole) {
        assertApprovalRole(requestRole);
        InterimSettleReversalRequest req = loadPending(requestId, factoryId);
        req.setStatus(InterimSettleReversalRequest.Status.REJECTED);
        req.setRejectReason(reason);
        req.setApprovedBy(approverId);
        req.setApprovedAt(LocalDateTime.now());
        InterimSettleReversalRequest saved = requestRepository.save(req);
        log.info("[interim-reverse] 撤销申请已驳回 (零副作用): requestId={}, factory={}, by={}, reason={}",
                requestId, factoryId, approverId, reason);
        return InterimSettleReversalRequestDTO.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InterimSettleReversalRequestDTO> list(String factoryId,
            InterimSettleReversalRequest.Status status, String planId, Pageable pageable) {
        return requestRepository.findByFactoryIdWithFilters(factoryId, status, planId, pageable)
                .map(InterimSettleReversalRequestDTO::from);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private InterimSettleReversalRequest loadPending(String requestId, String factoryId) {
        InterimSettleReversalRequest req = requestRepository.findByIdAndFactoryId(requestId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "撤销申请不存在: " + requestId));
        if (req.getStatus() != InterimSettleReversalRequest.Status.PENDING_APPROVAL) {
            throw new BusinessException(409, "撤销申请状态 [" + req.getStatus() + "] 不支持该操作, 需 PENDING_APPROVAL")
                    .withCode("INTERIM_REVERSE_REQUEST_NOT_PENDING")
                    .withHint("该申请已审批/驳回, 请刷新后查看");
        }
        return req;
    }

    private void assertWithinWindow(LocalDateTime postedAt, int seq) {
        if (postedAt == null) {
            return; // 防御: 无锚点不阻断 (不应发生)
        }
        if (LocalDateTime.now().isAfter(postedAt.plusHours(REVERSE_WINDOW_HOURS))) {
            throw new BusinessException(409, "第 " + seq + " 次小结已超过24小时，不允许撤销，请走盘点调整")
                    .withCode("INTERIM_REVERSE_WINDOW_EXPIRED")
                    .withHint("超时的小结请通过半成品盘点调整库存")
                    .withHintTarget("撤销小结");
        }
    }

    private void assertApprovalRole(String requestRole) {
        String normalized = requestRole == null ? "" : requestRole.toLowerCase();
        if (!STOCKTAKE_APPROVAL_ROLES.contains(normalized)) {
            throw new BusinessException(403,
                    "撤销小结审批需要财务经理或工厂管理员角色，当前角色：" + requestRole)
                    .withHint("请联系财务经理 (finance_manager) 或工厂超管审批");
        }
    }

    /** 从逆转结果拼接被影响批次号 (逗号分隔) 供盘点告警快照。 */
    @SuppressWarnings("unchecked")
    private static String joinAffected(Map<String, Object> result) {
        Object v = result == null ? null : result.get("affectedBatchNumbers");
        if (v instanceof List<?> list && !list.isEmpty()) {
            return String.join(",", list.stream().map(String::valueOf).toList());
        }
        return null;
    }
}
