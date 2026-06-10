package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.CreateStocktakeRequest;
import com.cretas.aims.dto.factory.StocktakeDiffPreviewDTO;
import com.cretas.aims.dto.factory.StocktakeDTO;
import com.cretas.aims.dto.factory.StocktakeItemUpdateDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryStocktakeItem;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeItemRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.service.factory.FactoryStocktakeService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 工厂盘点任务服务实现 (SP7 §5.1).
 *
 * <p>红线 §3.4:
 * <ul>
 *   <li>apply() 必须在 APPROVED 状态下执行，幂等防重</li>
 *   <li>库存变动通过 MaterialBatchAdjustment 留痕</li>
 *   <li>角色检查通过 requestRole 参数（非 SecurityContext，C1 孪生坑）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactoryStocktakeServiceImpl implements FactoryStocktakeService {

    private final FactoryStocktakeRepository stocktakeRepo;
    private final FactoryStocktakeItemRepository stocktakeItemRepo;
    private final MaterialBatchRepository materialBatchRepo;
    private final MaterialBatchAdjustmentRepository adjustmentRepo;

    /** SP12 §5.2: optional — 测试时不注入 (required=false 打破构造器注入限制) */
    @Autowired(required = false)
    private WorkflowEngineService workflowEngine;

    // -------------------------------------------------------
    // 月底约束：>=threshold 日才允许发起盘点
    // prod 默认 29（月底）；test env 可设 1 以跳过限制便于 E2E 验证
    // -------------------------------------------------------
    @Value("${cretas.stocktake.month-end-threshold:29}")
    private int monthEndThreshold;

    @Override
    @Transactional
    public StocktakeDTO initiate(String factoryId, CreateStocktakeRequest req, Long userId) {
        // 月底约束
        LocalDate today = LocalDate.now();
        if (today.getDayOfMonth() < monthEndThreshold) {
            LocalDate nextAllowedDate = today.withDayOfMonth(monthEndThreshold);
            throw new BusinessException(409,
                    "盘点任务只能在月底（" + monthEndThreshold + "日后）发起，当前是 " + today +
                    "，下次可发起日期: " + nextAllowedDate)
                    .withHint("等到 " + monthEndThreshold + " 日再发起");
        }

        // 防重复发起（同仓库同月份已有未完成盘点）
        long existing = stocktakeRepo.countActiveStocktakeForWarehouseAndMonth(
                factoryId, req.getWarehouseId(), req.getPeriodMonth());
        if (existing > 0) {
            throw new BusinessException(409,
                    "该仓库本月已有进行中的盘点任务，请完成或拒绝后再发起")
                    .withCode("DUPLICATE_STOCKTAKE");
        }

        FactoryStocktake stocktake = new FactoryStocktake();
        stocktake.setFactoryId(factoryId);
        stocktake.setStocktakeNo(generateStocktakeNo(factoryId));
        stocktake.setWarehouseId(req.getWarehouseId());
        stocktake.setPeriodMonth(req.getPeriodMonth());
        stocktake.setStatus(FactoryStocktake.Status.INITIATED);
        stocktake.setInitiatedBy(userId);
        stocktake.setInitiatedAt(LocalDateTime.now());
        stocktake.setNotes(req.getNotes());

        // 快照该仓库所有 MaterialBatch 的当前库存
        List<MaterialBatch> batches = materialBatchRepo.findByFactoryIdAndWarehouseId(
                factoryId, req.getWarehouseId());
        List<FactoryStocktakeItem> items = new ArrayList<>();
        for (MaterialBatch batch : batches) {
            FactoryStocktakeItem item = new FactoryStocktakeItem();
            item.setStocktake(stocktake);
            item.setMaterialBatchId(batch.getId());
            item.setRawMaterialTypeId(batch.getMaterialTypeId());
            item.setSystemQty(batch.getReceiptQuantity() != null ?
                    batch.getReceiptQuantity().setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO);
            items.add(item);
        }
        stocktake.setItems(items);

        FactoryStocktake saved = stocktakeRepo.save(stocktake);
        log.info("SP7: 盘点任务已创建 factoryId={} stocktakeNo={} warehouseId={}",
                factoryId, saved.getStocktakeNo(), req.getWarehouseId());
        return StocktakeDTO.from(saved);
    }

    @Override
    @Transactional
    public void updateItems(String stocktakeId, String factoryId, List<StocktakeItemUpdateDTO> updates, Long userId) {
        FactoryStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        assertNotApplied(stocktake);
        // 状态流转到 COUNTING
        if (stocktake.getStatus() == FactoryStocktake.Status.INITIATED) {
            stocktake.setStatus(FactoryStocktake.Status.COUNTING);
        }

        for (StocktakeItemUpdateDTO update : updates) {
            FactoryStocktakeItem item = stocktake.getItems().stream()
                    .filter(i -> i.getId().equals(update.getItemId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(404,
                            "盘点明细行不存在: " + update.getItemId()));

            item.setActualQty(update.getActualQty().setScale(4, RoundingMode.HALF_UP));
            if (update.getPhotoUrls() != null) {
                item.setPhotoUrls(update.getPhotoUrls());
            }
            if (update.getNotes() != null) {
                item.setNotes(update.getNotes());
            }
            // 计算差异
            if (item.getSystemQty() != null && item.getActualQty() != null) {
                BigDecimal diff = item.getActualQty().subtract(item.getSystemQty())
                        .setScale(4, RoundingMode.HALF_UP);
                item.setDifferenceQty(diff);
                int cmp = diff.compareTo(BigDecimal.ZERO);
                if (cmp > 0) {
                    item.setDifferenceType(FactoryStocktakeItem.DifferenceType.SURPLUS);
                } else if (cmp < 0) {
                    item.setDifferenceType(FactoryStocktakeItem.DifferenceType.SHORTAGE);
                } else {
                    item.setDifferenceType(FactoryStocktakeItem.DifferenceType.MATCH);
                }
            }
        }
        stocktakeRepo.save(stocktake);
    }

    @Override
    @Transactional
    public void submit(String stocktakeId, String factoryId, Long userId) {
        FactoryStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        if (stocktake.getStatus() != FactoryStocktake.Status.COUNTING &&
            stocktake.getStatus() != FactoryStocktake.Status.INITIATED &&
            stocktake.getStatus() != FactoryStocktake.Status.REJECTED) {
            throw new BusinessException(409,
                    "当前盘点任务状态 [" + stocktake.getStatus() + "] 不支持提交，需要 COUNTING 或 INITIATED 或 REJECTED（重提）")
                    .withHint("请先录入实盘数量后再提交");
        }
        stocktake.setStatus(FactoryStocktake.Status.PENDING_APPROVAL);
        stocktake.setSubmittedBy(userId);
        stocktake.setSubmittedAt(LocalDateTime.now());
        stocktakeRepo.save(stocktake);
        log.info("SP7: 盘点任务已提交审批 stocktakeId={}", stocktakeId);
    }

    @Override
    @Transactional
    public void approve(String stocktakeId, String factoryId, Long approverId, String requestRole) {
        // 角色检查（C1 孪生坑：不用 SecurityContext）
        assertApprovalRole(requestRole);
        FactoryStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        assertStatus(stocktake, FactoryStocktake.Status.PENDING_APPROVAL, "审批");
        stocktake.setStatus(FactoryStocktake.Status.APPROVED);
        stocktake.setApprovedBy(approverId);
        stocktake.setApprovedAt(LocalDateTime.now());
        stocktakeRepo.save(stocktake);
        log.info("SP7: 盘点任务已审批 stocktakeId={} approverId={}", stocktakeId, approverId);
    }

    @Override
    @Transactional
    public void reject(String stocktakeId, String factoryId, String reason, Long userId, String requestRole) {
        assertApprovalRole(requestRole);
        FactoryStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        assertStatus(stocktake, FactoryStocktake.Status.PENDING_APPROVAL, "驳回");
        stocktake.setStatus(FactoryStocktake.Status.REJECTED);
        stocktake.setRejectReason(reason);
        stocktake.setApprovedBy(userId);
        stocktake.setApprovedAt(LocalDateTime.now());
        stocktakeRepo.save(stocktake);
        log.info("SP7: 盘点任务已驳回 stocktakeId={} reason={}", stocktakeId, reason);
    }

    /**
     * 盘点审批角色守卫 (W2 验证战役修复, 2026-06-10)。
     *
     * <p>原实现比对大写常量 "FINANCE"/"FACTORY_SUPER_ADMIN"/"PLATFORM_SUPER_ADMIN"，
     * 而 JWT/request attribute "role" 携带的是小写角色码 (factory_super_admin 等,
     * 见 FactoryUserRole 枚举)，且 "FINANCE"/"PLATFORM_SUPER_ADMIN" 根本不是合法角色码
     * → 三个条件永远不匹配，盘点审批/驳回对所有人 403 (审批环节整体死路, test env
     * 盘点链 E2E 解锁后首跑即暴露)。修: 按真实角色码集合判断, 大小写不敏感兜底。
     */
    private static final java.util.Set<String> STOCKTAKE_APPROVAL_ROLES = java.util.Set.of(
            "finance_manager", "factory_super_admin", "platform_admin");

    private void assertApprovalRole(String requestRole) {
        String normalized = requestRole == null ? "" : requestRole.toLowerCase();
        if (!STOCKTAKE_APPROVAL_ROLES.contains(normalized)) {
            throw new BusinessException(403,
                    "盘点审批需要财务经理或工厂管理员角色，当前角色：" + requestRole)
                    .withHint("请联系财务经理 (finance_manager) 或工厂超管审批");
        }
    }

    /**
     * 生效：写差异到 MaterialBatch + 生成 MaterialBatchAdjustment。
     * 红线 §3.4: 必须经 APPROVED 状态；幂等防重；原子事务。
     * ⚠️ 蓝图 §3.4: 通过 MaterialBatchAdjustment 留痕，不直接裸调 materialBatchRepo.save()。
     */
    @Override
    @Transactional
    public void apply(String stocktakeId, String factoryId, Long userId) {
        FactoryStocktake stocktake = stocktakeRepo.findById(stocktakeId)
                .orElseThrow(() -> new BusinessException(404, "盘点任务不存在: " + stocktakeId));
        if (!factoryId.equals(stocktake.getFactoryId())) {
            throw new BusinessException(403, "无权操作该盘点任务");
        }

        // 幂等防重
        if (stocktake.getStatus() == FactoryStocktake.Status.APPLIED) {
            throw new BusinessException(409,
                    "盘点任务已于 " + stocktake.getAppliedAt() + " 生效，请勿重复操作")
                    .withCode("ALREADY_APPLIED")
                    .withHint("查看已生效记录");
        }
        assertStatus(stocktake, FactoryStocktake.Status.APPROVED, "生效");

        for (FactoryStocktakeItem item : stocktake.getItems()) {
            if (item.getDifferenceQty() == null ||
                    item.getDifferenceQty().compareTo(BigDecimal.ZERO) == 0) {
                continue; // 差异为 0 的行跳过
            }

            // 读取当前批次
            MaterialBatch batch = materialBatchRepo.findById(item.getMaterialBatchId())
                    .orElse(null);
            if (batch == null) {
                log.warn("SP7 apply: 批次不存在，跳过 batchId={}", item.getMaterialBatchId());
                continue;
            }

            BigDecimal quantityBefore = batch.getReceiptQuantity() != null
                    ? batch.getReceiptQuantity() : BigDecimal.ZERO;
            BigDecimal quantityAfter = quantityBefore.add(item.getDifferenceQty())
                    .setScale(2, RoundingMode.HALF_UP);
            // 防止盘点后数量为负
            if (quantityAfter.compareTo(BigDecimal.ZERO) < 0) {
                quantityAfter = BigDecimal.ZERO;
            }

            // 创建 audit 记录（红线 §3.4：每次变动必须有 MaterialBatchAdjustment）
            MaterialBatchAdjustment adj = new MaterialBatchAdjustment();
            adj.setId(UUID.randomUUID().toString());
            adj.setMaterialBatchId(item.getMaterialBatchId());
            adj.setAdjustmentType("STOCKTAKE");
            adj.setQuantityBefore(quantityBefore.setScale(2, RoundingMode.HALF_UP));
            adj.setAdjustmentQuantity(item.getDifferenceQty().setScale(2, RoundingMode.HALF_UP));
            adj.setQuantityAfter(quantityAfter);
            adj.setReason("盘点生效 [" + stocktake.getStocktakeNo() + "] 差异: " +
                    item.getDifferenceType() + " " + item.getDifferenceQty());
            adj.setAdjustmentTime(LocalDateTime.now());
            adj.setAdjustedBy(userId);
            adj.setNotes("stocktakeId=" + stocktakeId);
            adjustmentRepo.save(adj);

            // 更新批次数量（null 安全，通过 receiptQuantity 字段）
            batch.setReceiptQuantity(quantityAfter);
            materialBatchRepo.save(batch);
        }

        stocktake.setStatus(FactoryStocktake.Status.APPLIED);
        stocktake.setAppliedAt(LocalDateTime.now());
        stocktakeRepo.save(stocktake);
        log.info("SP7: 盘点任务已生效 stocktakeId={}", stocktakeId);
    }

    @Override
    public StocktakeDiffPreviewDTO previewDiff(String stocktakeId, String factoryId) {
        FactoryStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        StocktakeDiffPreviewDTO preview = new StocktakeDiffPreviewDTO();
        preview.setStocktakeId(stocktakeId);
        preview.setStocktakeNo(stocktake.getStocktakeNo());
        preview.setPeriodMonth(stocktake.getPeriodMonth());

        List<StocktakeDiffPreviewDTO.DiffLine> lines = new ArrayList<>();
        int surplus = 0, shortage = 0, match = 0;
        for (FactoryStocktakeItem item : stocktake.getItems()) {
            StocktakeDiffPreviewDTO.DiffLine line = new StocktakeDiffPreviewDTO.DiffLine();
            line.setItemId(item.getId());
            line.setMaterialBatchId(item.getMaterialBatchId());
            line.setSystemQty(item.getSystemQty());
            line.setActualQty(item.getActualQty());
            line.setDifferenceQty(item.getDifferenceQty());
            line.setDifferenceType(item.getDifferenceType() != null ? item.getDifferenceType().name() : null);

            // 获取批次号用于展示
            materialBatchRepo.findById(item.getMaterialBatchId()).ifPresent(b -> {
                line.setBatchNumber(b.getBatchNumber());
            });

            lines.add(line);
            if (item.getDifferenceType() == FactoryStocktakeItem.DifferenceType.SURPLUS) surplus++;
            else if (item.getDifferenceType() == FactoryStocktakeItem.DifferenceType.SHORTAGE) shortage++;
            else if (item.getDifferenceType() == FactoryStocktakeItem.DifferenceType.MATCH) match++;
        }
        preview.setDiffLines(lines);
        preview.setSurplusCount(surplus);
        preview.setShortageCount(shortage);
        preview.setMatchCount(match);
        return preview;
    }

    @Override
    public Page<StocktakeDTO> list(String factoryId, FactoryStocktake.Status status, Pageable pageable) {
        return stocktakeRepo.findByFactoryIdAndOptionalStatus(factoryId, status, pageable)
                .map(StocktakeDTO::from);
    }

    @Override
    public StocktakeDTO getDetail(String stocktakeId, String factoryId) {
        FactoryStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        return StocktakeDTO.from(stocktake);
    }

    @Override
    @Transactional
    public String submitForApproval(String stocktakeId, String factoryId, Long userId) {
        FactoryStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        if (stocktake.getStatus() != FactoryStocktake.Status.COUNTING &&
            stocktake.getStatus() != FactoryStocktake.Status.INITIATED &&
            stocktake.getStatus() != FactoryStocktake.Status.REJECTED) {
            throw new BusinessException(409,
                    "当前盘点任务状态 [" + stocktake.getStatus() + "] 不支持提交审批，需要 COUNTING 或 INITIATED 或 REJECTED（重提）"
                    + " — 请前往[审批中心]查看进行中的审批")
                    .withHint("前往审批中心");
        }

        // 幂等: 如果已有 workflowInstanceId 且状态 PENDING_APPROVAL → 不重复创建
        if (stocktake.getWorkflowInstanceId() != null &&
                stocktake.getStatus() == FactoryStocktake.Status.PENDING_APPROVAL) {
            throw new BusinessException(409,
                    "盘点审批已提交 (PENDING_APPROVAL)，请勿重复提交 — 请前往[审批中心]查看")
                    .withCode("DUPLICATE_APPROVAL_REQUEST")
                    .withHint("前往审批中心: /approval-center");
        }

        stocktake.setStatus(FactoryStocktake.Status.PENDING_APPROVAL);
        stocktake.setSubmittedBy(userId);
        stocktake.setSubmittedAt(LocalDateTime.now());

        // 启动 INVENTORY_ADJUSTMENT workflow（若 workflowEngine 可用）
        if (workflowEngine != null && workflowEngine.hasActiveWorkflow(factoryId, "INVENTORY_ADJUSTMENT")) {
            ApprovalWorkflowInstance instance = workflowEngine.startWorkflow(
                    factoryId,
                    "INVENTORY_ADJUSTMENT",
                    stocktakeId,
                    Map.of("stocktakeNo", stocktake.getStocktakeNo(),
                           "periodMonth", stocktake.getPeriodMonth(),
                           "warehouseId", stocktake.getWarehouseId()),
                    userId);
            stocktake.setWorkflowInstanceId(instance.getId());
        }

        stocktakeRepo.save(stocktake);
        log.info("SP12: 盘点任务已提交审批 stocktakeId={} workflowInstanceId={}",
                stocktakeId, stocktake.getWorkflowInstanceId());
        return stocktake.getWorkflowInstanceId();
    }

    /**
     * SP12 §5.2 + 红线 R1: 仅供 workflow callback 调用，不对外暴露 REST。
     * 校验 workflowInstanceId 不为 null（有审批流经历），状态必须 APPROVED。
     */
    @Override
    @Transactional
    public void executeAdjustment(String stocktakeId) {
        FactoryStocktake stocktake = stocktakeRepo.findById(stocktakeId)
                .orElseThrow(() -> new BusinessException(404, "盘点任务不存在: " + stocktakeId));

        // 红线 R1: 必须有 workflowInstanceId（不允许绕过 workflow）
        if (stocktake.getWorkflowInstanceId() == null) {
            throw new BusinessException(403,
                    "盘点调账必须经过 INVENTORY_ADJUSTMENT 工作流审批，无法直接调账")
                    .withCode("WORKFLOW_BYPASS_FORBIDDEN")
                    .withHint("请先通过工作流提交审批");
        }

        // 幂等防重
        if (stocktake.getStatus() == FactoryStocktake.Status.APPLIED) {
            throw new BusinessException(409,
                    "盘点任务已于 " + stocktake.getAppliedAt() + " 生效，请勿重复操作")
                    .withCode("ALREADY_APPLIED")
                    .withHint("查看已生效记录");
        }
        assertStatus(stocktake, FactoryStocktake.Status.APPROVED, "生效");

        // 复用 apply() 的库存调整逻辑
        apply(stocktakeId, stocktake.getFactoryId(), null);
    }

    // -------------------------------------------------------
    // private helpers
    // -------------------------------------------------------

    private FactoryStocktake findAndValidate(String stocktakeId, String factoryId) {
        FactoryStocktake stocktake = stocktakeRepo.findById(stocktakeId)
                .orElseThrow(() -> new BusinessException(404, "盘点任务不存在: " + stocktakeId));
        if (!factoryId.equals(stocktake.getFactoryId())) {
            throw new BusinessException(403, "无权操作该盘点任务");
        }
        return stocktake;
    }

    private void assertStatus(FactoryStocktake stocktake, FactoryStocktake.Status expected, String action) {
        if (stocktake.getStatus() != expected) {
            throw new BusinessException(409,
                    "当前盘点任务状态 [" + stocktake.getStatus() + "] 不支持操作 [" + action + "]，需要状态: " + expected);
        }
    }

    private void assertNotApplied(FactoryStocktake stocktake) {
        if (stocktake.getStatus() == FactoryStocktake.Status.APPLIED) {
            throw new BusinessException(409,
                    "盘点任务已于 " + stocktake.getAppliedAt() + " 生效，无法修改")
                    .withCode("ALREADY_APPLIED");
        }
    }

    private String generateStocktakeNo(String factoryId) {
        String month = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        return "ST-" + month + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
