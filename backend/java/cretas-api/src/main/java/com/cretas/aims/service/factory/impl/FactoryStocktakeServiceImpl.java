package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.CreateStocktakeRequest;
import com.cretas.aims.dto.factory.StocktakeDiffPreviewDTO;
import com.cretas.aims.dto.factory.StocktakeDTO;
import com.cretas.aims.dto.factory.StocktakeItemUpdateDTO;
import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryStocktakeItem;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeItemRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.factory.FactoryStocktakeService;
import com.cretas.aims.service.voucher.VoucherService;
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
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    /** 🔴 Fix (previewDiff materialName 空白, 2026-07): 按 batch.materialTypeId 反查物料名, 镜像 StocktakeBulkImportServiceImpl.loadMaterialNames。 */
    private final RawMaterialTypeRepository rawMaterialTypeRepo;
    private final ProductTypeRepository productTypeRepo;
    /**
     * 🔴🔒🔒 生产仓「延迟扣减」盘点盲区修复 (2026-07-04): 快照/漂移比对须从货架实物量扣掉
     * 未小结的报工消耗 (报工写未结 MaterialConsumption 但不扣 usedQuantity, 小结才扣)。
     */
    private final MaterialConsumptionRepository materialConsumptionRepo;

    @Autowired(required = false)
    private UserRepository userRepository;

    /** SP12 §5.2: optional — 测试时不注入 (required=false 打破构造器注入限制) */
    @Autowired(required = false)
    private WorkflowEngineService workflowEngine;

    @Autowired
    private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;

    /**
     * 原料盘点差异过账 (Decision 3: 长期一致 — 逐项 UI 盘点 + 批量导入 apply 都过账)。
     * optional — voucherService 不注入时不过账 (向后兼容 + 测试不强制); 镜像 workflowEngine。
     */
    @Autowired(required = false)
    private VoucherService voucherService;

    // -------------------------------------------------------
    // 原料盘点差异过账 科目 (China GAAP, seed V20260701_02)。科目已 Steve 确认 (2026-07-02)。
    //   NORMAL/逐项 盘盈:  借 1403 原材料 / 贷 6301 营业外收入
    //   NORMAL/逐项 盘亏:  借 6602 管理费用 / 贷 1403 原材料
    //   OPENING 期初:      借 1403 原材料 / 贷 4001 实收资本 (盘盈方向); 盘亏方向借贷互换
    //      — 期初建账不进 6301 营业外收入, 避免虚增建账当期损益。
    //   Decision 3: 逐项 UI 盘点 (importMode=null) 现在也按 NORMAL 过账 (修复原料盘点不过账的历史缺口);
    //               apply 状态守卫 (!=APPLIED 才跑) 保证每单只过账一次, 无双过账。
    //   Decision 2: 期初/盘盈 绝不混 — 科目不同 (4001 vs 6301) + 摘要不同 + 来源业务类型不同 (可分别筛)。
    // -------------------------------------------------------
    private static final String SUBJECT_INVENTORY_CODE = "1403";
    private static final String SUBJECT_INVENTORY_NAME = "原材料";
    private static final String SUBJECT_SURPLUS_INCOME_CODE = "6301";
    private static final String SUBJECT_SURPLUS_INCOME_NAME = "营业外收入";
    private static final String SUBJECT_SHORTAGE_LOSS_CODE = "6602";
    private static final String SUBJECT_SHORTAGE_LOSS_NAME = "管理费用";
    private static final String SUBJECT_OPENING_EQUITY_CODE = "4001";
    private static final String SUBJECT_OPENING_EQUITY_NAME = "实收资本";
    /** 常规盘盈/盘亏 凭证来源业务类型 (逐项 + 批量 NORMAL 共用; 与 stocktakeId 组成幂等键)。*/
    private static final String VOUCHER_SOURCE_TYPE_NORMAL = "FACTORY_STOCKTAKE";
    /** 期初建账 凭证来源业务类型 (独立, 便于财务分开筛选/核对, Decision 2)。*/
    private static final String VOUCHER_SOURCE_TYPE_OPENING = "FACTORY_STOCKTAKE_OPENING";

    // -------------------------------------------------------
    // 月底约束：>=threshold 日才允许发起盘点
    // prod 默认 29（月底）；test env 可设 1 以跳过限制便于 E2E 验证
    // -------------------------------------------------------
    @Value("${cretas.stocktake.month-end-threshold:29}")
    private int monthEndThreshold;

    @Override
    @Transactional
    public StocktakeDTO initiate(String factoryId, CreateStocktakeRequest req, Long userId) {
        return initiate(factoryId, req, userId, null);
    }

    @Override
    @Transactional
    public StocktakeDTO initiate(String factoryId, CreateStocktakeRequest req, Long userId,
            FactoryStocktake.ImportMode importMode) {
        // 月底约束 — OPENING 期初建账 除外 (客户任意日建账, Decision 4)。
        // 全局 threshold 不降 (NORMAL/逐项盘点仍受月底约束), 只对 OPENING / 临时盘点跳过。
        // 🔒 fool-proof Rule 5 (2026-07): 月底约束此前无任何例外出口 (疑似失窃/货损等
        // 需要立即清点的场景被死死挡到月底) — 加 adHoc 逃生舱, 不新增枚举值/不动 DB CHECK
        // (project memory: 加枚举新值易漏迁移放宽 CHECK, 这里用轻量 boolean 规避)。
        boolean opening = importMode == FactoryStocktake.ImportMode.OPENING;
        boolean adHoc = req.isAdHoc();
        LocalDateTime inventoryCutoffAt = LocalDateTime.now();
        LocalDate today = inventoryCutoffAt.toLocalDate();
        String periodMonth = YearMonth.from(inventoryCutoffAt).toString();
        if (!opening && !adHoc && today.getDayOfMonth() < monthEndThreshold) {
            LocalDate nextAllowedDate = today.withDayOfMonth(monthEndThreshold);
            throw new BusinessException(409,
                    "盘点任务只能在月底（" + monthEndThreshold + "日后）发起，当前是 " + today +
                    "，下次可发起日期: " + nextAllowedDate)
                    .withHint("等到 " + monthEndThreshold + " 日再发起");
        }
        if (adHoc) {
            log.info("SP7: 临时/专项盘点发起 (跳过月底约束) factoryId={} warehouseId={} today={} reason={}",
                    factoryId, req.getWarehouseId(), today, req.getAdHocReason());
        }

        // 防重复发起（同仓库同月份已有未完成盘点）
        long existing = stocktakeRepo.countActiveStocktakeForWarehouseAndMonth(
                factoryId, req.getWarehouseId(), periodMonth);
        if (existing > 0) {
            throw new BusinessException(409,
                    "该仓库本月已有进行中的盘点任务，请完成或拒绝后再发起")
                    .withCode("DUPLICATE_STOCKTAKE");
        }

        FactoryStocktake stocktake = new FactoryStocktake();
        stocktake.setFactoryId(factoryId);
        stocktake.setStocktakeNo(generateStocktakeNo(factoryId));
        stocktake.setWarehouseId(req.getWarehouseId());
        stocktake.setPeriodMonth(periodMonth);
        stocktake.setInventoryCutoffAt(inventoryCutoffAt);
        stocktake.setReconciliationEndAt(inventoryCutoffAt);
        String reconciliationPreset = normalizeReconciliationPreset(req.getReconciliationPreset());
        stocktake.setReconciliationPreset(reconciliationPreset);
        stocktake.setReconciliationStartAt(resolveReconciliationStart(
                factoryId, req.getWarehouseId(), inventoryCutoffAt, reconciliationPreset,
                req.getReconciliationStartAt()));
        stocktake.setStatus(FactoryStocktake.Status.INITIATED);
        stocktake.setInitiatedBy(userId);
        stocktake.setInitiatedAt(inventoryCutoffAt);
        stocktake.setNotes(req.getNotes());
        stocktake.setImportMode(importMode); // null = 逐项 UI 盘点; NORMAL/OPENING = 批量导入

        // 快照该仓库所有 MaterialBatch 的当前库存
        List<MaterialBatch> batches = materialBatchRepo.findByFactoryIdAndWarehouseId(
                factoryId, req.getWarehouseId());
        // 🔴 Fix (🔒🔒 生产仓延迟扣减盲区, 2026-07-04): 逐批预取未小结报工消耗量 (见 loadUnsettledByBatch)。
        Map<String, BigDecimal> unsettledByBatch = loadUnsettledByBatch(factoryId,
                batches.stream().map(MaterialBatch::getId).collect(Collectors.toList()));
        List<FactoryStocktakeItem> items = new ArrayList<>();
        for (MaterialBatch batch : batches) {
            FactoryStocktakeItem item = new FactoryStocktakeItem();
            item.setStocktake(stocktake);
            item.setMaterialBatchId(batch.getId());
            // This legacy snapshot column stores the inventory identity used by the
            // batch. Raw batches use materialTypeId; WIP/product batches use
            // productTypeId. The public DTO splits the two identities again.
            item.setRawMaterialTypeId(inventoryTypeId(batch));
            // 🔴 Fix (🔒🔒 phantom-variance): 快照「货架实物量」= receiptQuantity − usedQuantity − 未小结报工消耗,
            // 既不是 gross receiptQuantity, 也不是可用量 getCurrentQuantity()(= receipt − used − reserved)。
            // 仓管盘点数的是货架上肉眼可见的实物: 已领用(used)的货已物理离开货架 → 扣减;
            // 预留(reserved)是逻辑占用(下游订单/生产计划挂占), 货物物理上仍在货架上 → 不扣减。
            //   • 用 gross receiptQuantity → 已领用量被误计为盘亏(旧幻影短缺 bug, #1201 已修)。
            //   • 用 getCurrentQuantity()(含 −reserved) → 预留量被误计为盘盈(#1201 overshoot)。
            // 🔴 生产仓延迟扣减盲区 (2026-07-04, 本次修): 报工写「未小结」MaterialConsumption 但不扣 usedQuantity
            //   (小结才扣)。故生产仓 WKS 批次 getPhysicalQuantity()(=receipt−used) 仍含「已投产但账面未扣」的量,
            //   高于仓管真正数到的货架实物。若不减未小结消耗: 仓管诚实数少 → 假盘亏 6 → 假 借6602/贷1403 凭证,
            //   随后小结对已被盘点划空 (receipt 下移) 的批次 usedQuantity+=6 扣成负 → 409 永久卡死 + 关单亦 409。
            //   减去未小结消耗后账面=真实货架实物 → 零差异 → 无假凭证 + 无 receipt 平移 → 小结正常扣减不 409。
            //   领料即时扣减 / #1201/#1213 原料仓场景无未结消耗 → 减 0, 口径不变。与 receipt−used 组合只减一次,
            //   无 double-subtract (getPhysicalQuantity 本身不含未结消耗, 小结扣 used 时同事务清 interimSettledAt)。
            // getPhysicalQuantity() 为 @Transient 计算属性, receiptQuantity=null 时返回 ZERO (恒非 null)。
            BigDecimal physical = batch.getPhysicalQuantity() != null
                    ? batch.getPhysicalQuantity() : BigDecimal.ZERO;
            BigDecimal shelf = physicalShelf(physical, unsettledByBatch.get(batch.getId()));
            item.setSystemQty(shelf.setScale(4, RoundingMode.HALF_UP));
            items.add(item);
        }
        stocktake.setItems(items);

        FactoryStocktake saved = stocktakeRepo.save(stocktake);
        log.info("SP7: 盘点任务已创建 factoryId={} stocktakeNo={} warehouseId={}",
                factoryId, saved.getStocktakeNo(), req.getWarehouseId());
        return toDetailedDTO(saved, factoryId);
    }

    @Override
    public Map<String, Object> getInitiateConstraint() {
        LocalDate today = LocalDate.now();
        boolean canInitiateToday = today.getDayOfMonth() >= monthEndThreshold;
        // 与 initiate() 同一算法: 当月 threshold 日 (若本月无该日, withDayOfMonth 会抛异常
        // 但 threshold 恒 <=29, 每月都有29日, 不会踩月末天数不足)。
        LocalDate nextAllowedDate = canInitiateToday ? today : today.withDayOfMonth(monthEndThreshold);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("monthEndThreshold", monthEndThreshold);
        result.put("canInitiateToday", canInitiateToday);
        result.put("today", today);
        LocalDateTime serverNow = LocalDateTime.now();
        result.put("serverNow", serverNow);
        result.put("periodMonth", YearMonth.from(serverNow).toString());
        result.put("nextAllowedDate", nextAllowedDate);
        return result;
    }

    @Override
    @Transactional
    public void updateItems(String stocktakeId, String factoryId, List<StocktakeItemUpdateDTO> updates, Long userId) {
        FactoryStocktake stocktake = findAndValidateForUpdate(stocktakeId, factoryId);
        if (stocktake.getStatus() != FactoryStocktake.Status.INITIATED
                && stocktake.getStatus() != FactoryStocktake.Status.COUNTING
                && stocktake.getStatus() != FactoryStocktake.Status.REJECTED) {
            throw new BusinessException(409,
                    "当前盘点任务状态 [" + stocktake.getStatus() + "] 已锁定盘点证据，不能修改实盘数量")
                    .withCode("STOCKTAKE_COUNT_EVIDENCE_LOCKED")
                    .withHint("待审批、已审批或已应用的盘点必须保留审批时证据；如需更正，请先由审批人驳回");
        }
        // 状态流转到 COUNTING
        if (stocktake.getStatus() == FactoryStocktake.Status.INITIATED
                || stocktake.getStatus() == FactoryStocktake.Status.REJECTED) {
            stocktake.setStatus(FactoryStocktake.Status.COUNTING);
            if (stocktake.getCountingStartedAt() == null) {
                stocktake.setCountingStartedAt(LocalDateTime.now());
            }
            stocktake.setCountedBy(userId);
            // A rejected task starts a new evidence cycle. Old approval metadata must
            // never make the revised count look as if it had already been reviewed.
            stocktake.setSubmittedBy(null);
            stocktake.setSubmittedAt(null);
            stocktake.setApprovedBy(null);
            stocktake.setApprovedAt(null);
            stocktake.setRejectReason(null);
            stocktake.setSelfConfirmedZeroDifference(false);
        }

        for (StocktakeItemUpdateDTO update : updates) {
            FactoryStocktakeItem item = stocktake.getItems().stream()
                    .filter(i -> i.getId().equals(update.getItemId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(404,
                            "盘点明细行不存在: " + update.getItemId()));

            validateInventoryScale(update.getActualQty(), "实盘数量");
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

    private static void validateInventoryScale(BigDecimal value, String fieldName) {
        if (value == null) {
            return;
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(400,
                    fieldName + "最多保留2位小数，请按库存数量精度重新填写: " + value.toPlainString())
                    .withCode("STOCKTAKE_QTY_SCALE_EXCEEDED")
                    .withHint("库存批次数量当前按2位小数保存；请在预览/提交前改成两位以内，避免系统静默四舍五入");
        }
    }

    private String normalizeReconciliationPreset(String preset) {
        String normalized = preset == null || preset.isBlank()
                ? "LAST_APPLIED" : preset.trim().toUpperCase();
        if (!java.util.Set.of("LAST_APPLIED", "LAST_7_DAYS", "MONTH", "QUARTER", "YEAR", "CUSTOM")
                .contains(normalized)) {
            throw new BusinessException(400, "不支持的对账回溯范围: " + preset)
                    .withCode("STOCKTAKE_RECONCILIATION_PRESET_INVALID");
        }
        return normalized;
    }

    private LocalDateTime resolveReconciliationStart(String factoryId, String warehouseId,
            LocalDateTime cutoff, String preset, LocalDateTime customStart) {
        LocalDateTime start = switch (preset) {
            case "LAST_7_DAYS" -> cutoff.minusDays(7);
            case "MONTH" -> cutoff.withDayOfMonth(1).toLocalDate().atStartOfDay();
            case "QUARTER" -> {
                int firstMonth = ((cutoff.getMonthValue() - 1) / 3) * 3 + 1;
                yield cutoff.withMonth(firstMonth).withDayOfMonth(1).toLocalDate().atStartOfDay();
            }
            case "YEAR" -> cutoff.with(TemporalAdjusters.firstDayOfYear()).toLocalDate().atStartOfDay();
            case "CUSTOM" -> {
                if (customStart == null) {
                    throw new BusinessException(400, "自定义对账范围必须提供开始时间")
                            .withCode("STOCKTAKE_RECONCILIATION_START_REQUIRED");
                }
                yield customStart;
            }
            default -> stocktakeRepo
                    .findFirstByFactoryIdAndWarehouseIdAndStatusOrderByAppliedAtDesc(
                            factoryId, warehouseId, FactoryStocktake.Status.APPLIED)
                    .map(previous -> previous.getInventoryCutoffAt() != null
                            ? previous.getInventoryCutoffAt() : previous.getAppliedAt())
                    .orElse(cutoff.withDayOfMonth(1).toLocalDate().atStartOfDay());
        };
        if (start == null) {
            start = cutoff.withDayOfMonth(1).toLocalDate().atStartOfDay();
        }
        if (start.isAfter(cutoff)) {
            throw new BusinessException(400, "对账回溯开始时间不能晚于盘点基准时间")
                    .withCode("STOCKTAKE_RECONCILIATION_RANGE_INVALID");
        }
        return start;
    }

    @Override
    @Transactional
    public void submit(String stocktakeId, String factoryId, Long userId) {
        submitForApproval(stocktakeId, factoryId, userId);
    }

    @Override
    @Transactional
    public void approve(String stocktakeId, String factoryId, Long approverId, String requestRole) {
        approve(stocktakeId, factoryId, approverId, requestRole, null);
    }

    @Override
    @Transactional
    public void approve(String stocktakeId, String factoryId, Long approverId, String requestRole,
            Long expectedVersion) {
        // 角色检查（C1 孪生坑：不用 SecurityContext）
        assertApprovalRole(requestRole);
        FactoryStocktake stocktake = findAndValidateForUpdate(stocktakeId, factoryId);
        assertExpectedVersion(stocktake, expectedVersion);
        assertStatus(stocktake, FactoryStocktake.Status.PENDING_APPROVAL, "审批");
        boolean hasDifference = normalizeAndValidateEvidence(stocktake);
        boolean sameMaker = Objects.equals(stocktake.getInitiatedBy(), approverId)
                || Objects.equals(stocktake.getCountedBy(), approverId)
                || Objects.equals(stocktake.getSubmittedBy(), approverId);
        if (hasDifference && sameMaker) {
            throw new BusinessException(409, "存在盘盈/盘亏时，发起人、盘点录入人或提交人不能审批自己的盘点")
                    .withCode("STOCKTAKE_SELF_APPROVAL_FORBIDDEN")
                    .withHint("请由另一名财务经理或工厂管理员复核审批");
        }
        stocktake.setStatus(FactoryStocktake.Status.APPROVED);
        stocktake.setApprovedBy(approverId);
        stocktake.setApprovedAt(LocalDateTime.now());
        stocktake.setSelfConfirmedZeroDifference(!hasDifference && sameMaker);
        stocktakeRepo.save(stocktake);
        log.info("SP7: 盘点任务已审批 stocktakeId={} approverId={}", stocktakeId, approverId);
    }

    @Override
    @Transactional
    public void reject(String stocktakeId, String factoryId, String reason, Long userId, String requestRole) {
        assertApprovalRole(requestRole);
        FactoryStocktake stocktake = findAndValidateForUpdate(stocktakeId, factoryId);
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
        apply(stocktakeId, factoryId, userId, null);
    }

    @Override
    @Transactional
    public void apply(String stocktakeId, String factoryId, Long userId, Long expectedVersion) {
        FactoryStocktake stocktake = findAndValidateForUpdate(stocktakeId, factoryId);
        assertExpectedVersion(stocktake, expectedVersion);

        // 幂等防重
        if (stocktake.getStatus() == FactoryStocktake.Status.APPLIED) {
            throw new BusinessException(409,
                    "盘点任务已于 " + stocktake.getAppliedAt() + " 生效，请勿重复操作")
                    .withCode("ALREADY_APPLIED")
                    .withHint("查看已生效记录");
        }
        assertStatus(stocktake, FactoryStocktake.Status.APPROVED, "生效");
        normalizeAndValidateEvidence(stocktake);

        // Validate every immutable snapshot identity before any inventory mutation. This is
        // deliberately broader than the non-zero difference set: a zero-difference apply is
        // still a final audited lock operation and must not silently accept a cross-tenant row.
        for (FactoryStocktakeItem item : stocktake.getItems()) {
            MaterialBatch identityBatch = materialBatchRepo.findByIdAndFactoryId(
                            item.getMaterialBatchId(), factoryId)
                    .orElseThrow(() -> new BusinessException(409,
                            "盘点批次不存在或不属于当前工厂: " + item.getMaterialBatchId())
                            .withCode("STOCKTAKE_BATCH_IDENTITY_MISMATCH"));
            if (!Objects.equals(stocktake.getWarehouseId(), identityBatch.getWarehouseId())
                    || !Objects.equals(item.getRawMaterialTypeId(), inventoryTypeId(identityBatch))) {
                throw new BusinessException(409,
                        "盘点批次的仓库或物料身份已不一致: " + identityBatch.getBatchNumber())
                        .withCode("STOCKTAKE_BATCH_IDENTITY_MISMATCH");
            }
        }

        // ---------------------------------------------------------------
        // Bug2 修复: 漂移守卫 — differenceQty 是"实盘数量 − 快照 systemQty"的冻结值
        // (计数 updateItems() 时算出)。如果批次在"计数"与"生效 apply"之间被并发
        // 消耗/入库改变了 (quantityBefore 漂移), 直接把这个冻结差异套到当前实时
        // quantityBefore 上既不是实盘结果也不是干净对账 (曾在客户租户产生莫名的
        // 0.5 困惑差异)。诚实失败 (禁止降级): 生效前统一预检全部待过账行, 任一批次
        // 漂移就整体拦截并给出可操作提示, 不静默套用过期差异。全部一致才进入第二遍
        // 真正 mutate (与 ReportReversalServiceImpl 的"先验证全部, 再统一执行"两阶段
        // 模式一致), 避免部分生效。
        // ---------------------------------------------------------------
        java.util.Map<String, MaterialBatch> batchCache = new java.util.LinkedHashMap<>();
        // 🔴 Fix (🔒🔒 生产仓延迟扣减盲区, 2026-07-04): 漂移比对的 live 口径也须减去「当前」未小结报工消耗,
        // 与快照口径 (货架实物 = physical − 未结消耗) 保持一致 — 否则生产仓 WKS 有真实变异时会被误判漂移拦截。
        // physical−unsettled 在小结前后是不变量 (小结把量从「未结消耗桶」移到 usedQuantity, 两侧同减 physical),
        // 故窗口内发生小结不会误报漂移; 而若窗口内又有新报工 (unsettled 增大) 则 live≠snapshot → 正当漂移拦截。
        List<String> driftCandidateBatchIds = stocktake.getItems().stream()
                .filter(it -> it.getDifferenceQty() != null
                        && it.getDifferenceQty().compareTo(BigDecimal.ZERO) != 0)
                .map(FactoryStocktakeItem::getMaterialBatchId)
                .collect(Collectors.toList());
        Map<String, BigDecimal> unsettledByBatch = loadUnsettledByBatch(factoryId, driftCandidateBatchIds);
        List<String> driftedBatches = new ArrayList<>();
        for (FactoryStocktakeItem item : stocktake.getItems()) {
            if (item.getDifferenceQty() == null ||
                    item.getDifferenceQty().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            MaterialBatch batch = materialBatchRepo.findById(item.getMaterialBatchId()).orElse(null);
            if (batch == null) {
                continue; // 批次已不存在 — 下面的主循环会照常 warn 跳过, 无需在此重复处理
            }
            batchCache.put(item.getMaterialBatchId(), batch);
            // 🔴 Fix (🔒🔒): 漂移比对必须与快照同口径 — snapshot 为「货架实物量」(receipt − used − 未结消耗),
            // live 也取 physical − 未结消耗, 否则:
            //   • 每个 usedQuantity>0 的批次会被误判为"盘点后漂移"而永久拦截生效 (STOCKTAKE_DRIFT),
            //     使消耗过的批次无法盘点 (#1201 修的口径);
            //   • 若用含 −reserved 的 getCurrentQuantity(), 有预留的批次 live≠snapshot 又会误判漂移
            //     (#1201 overshoot) —— snapshot 用 physical, live 也必须 physical;
            //   • 生产仓 WKS 批次若不减未结消耗, 有真实盘亏时 live(=physical) ≠ snapshot(=physical−未结) → 误判漂移。
            BigDecimal physicalLive = batch.getPhysicalQuantity() != null
                    ? batch.getPhysicalQuantity() : BigDecimal.ZERO;
            BigDecimal liveQty = physicalShelf(physicalLive, unsettledByBatch.get(item.getMaterialBatchId()));
            BigDecimal snapshotQty = item.getSystemQty() != null ? item.getSystemQty() : BigDecimal.ZERO;
            if (liveQty.setScale(2, RoundingMode.HALF_UP)
                    .compareTo(snapshotQty.setScale(2, RoundingMode.HALF_UP)) != 0) {
                driftedBatches.add(String.format("%s(盘点时=%s, 当前=%s)",
                        batch.getBatchNumber() != null ? batch.getBatchNumber() : batch.getId(),
                        snapshotQty, liveQty));
            }
        }
        if (!driftedBatches.isEmpty()) {
            throw new BusinessException(409,
                    "以下批次在盘点计数后库存已发生变动，无法按旧差异生效，请重新盘点: "
                            + String.join("；", driftedBatches))
                    .withCode("STOCKTAKE_DRIFT")
                    .withHint("请重新发起该批次的盘点或先处理完并发出入库后再生效");
        }

        // Decision 3: 逐项 + 批量 都过账 (voucherService 可用即过账)。科目按 importMode 选 (null → NORMAL)。
        boolean posting = voucherService != null;
        BigDecimal surplusValue = BigDecimal.ZERO;   // Σ 盘盈行 value (正)
        BigDecimal shortageValue = BigDecimal.ZERO;  // Σ |盘亏行 value| (正)
        int uncostedCount = 0;                       // unitPrice=null 无法估值 → 排除出凭证

        for (FactoryStocktakeItem item : stocktake.getItems()) {
            if (item.getDifferenceQty() == null ||
                    item.getDifferenceQty().compareTo(BigDecimal.ZERO) == 0) {
                continue; // 差异为 0 的行跳过
            }

            // 读取当前批次 (复用预检阶段已取的实例, 避免二次查询/防止两次读取间再度漂移)
            MaterialBatch batch = batchCache.get(item.getMaterialBatchId());
            if (batch == null) {
                log.warn("SP7 apply: 批次不存在，跳过 batchId={}", item.getMaterialBatchId());
                continue;
            }

            // 差异是 delta (= 实盘 − 快照货架实物量), 把它加到 receiptQuantity 上即可让
            // getPhysicalQuantity() (= receipt − used) 精确落到实盘真值 —— used/reserved 不变,
            // receiptQuantity 平移 delta ⇒ 货架实物 + 可用量 同步平移 delta。⚠️ 这里必须用
            // receiptQuantity 作 quantityBefore (不是 getPhysicalQuantity()/getCurrentQuantity()):
            // systemQty 快照已在货架实物口径扣过 used, 若这里再拿扣过 used 的量作基准会二次扣减 used
            // (双减)。快照口径=货架实物(receipt−used), 应用口径=receiptQuantity, 二者配套。
            // 例: receipt=100/used=0/reserved=30, 实盘=95 → systemQty=100, delta=−5,
            //     quantityBefore=receipt=100, quantityAfter=95 ⇒ physical=95=实盘, available=95−30=65 (无幻影)。
            BigDecimal quantityBefore = batch.getReceiptQuantity() != null
                    ? batch.getReceiptQuantity() : BigDecimal.ZERO;
            BigDecimal quantityAfter = quantityBefore.add(item.getDifferenceQty())
                    .setScale(2, RoundingMode.HALF_UP);
            // 防止盘点后数量为负
            if (quantityAfter.compareTo(BigDecimal.ZERO) < 0) {
                quantityAfter = BigDecimal.ZERO;
            }

            // 差异估值 (仅批量导入过账时): value = realizedDelta × unitPrice。
            //   realizedDelta = 实际发生的余量变化 (clamp 后), 保证凭证与库存变动一致, 不高估损耗。
            //   诚实 null: unitPrice=null (被价格脱敏/未录价) → 排除出凭证, 数量仍调整, 绝不臆造价值。
            if (posting) {
                BigDecimal realizedDelta = quantityAfter.subtract(quantityBefore);
                if (realizedDelta.compareTo(BigDecimal.ZERO) != 0) {
                    BigDecimal unitPrice = batch.getUnitPrice();
                    if (unitPrice == null) {
                        uncostedCount++;
                        log.warn("SP7 apply(import): unitPrice=null 未录价, 差异行排除出财务凭证 (数量仍调整) "
                                + "batchId={} realizedDelta={}", item.getMaterialBatchId(), realizedDelta);
                    } else {
                        BigDecimal itemValue = realizedDelta.multiply(unitPrice)
                                .setScale(2, RoundingMode.HALF_UP);
                        if (itemValue.signum() > 0) {
                            surplusValue = surplusValue.add(itemValue);
                        } else if (itemValue.signum() < 0) {
                            shortageValue = shortageValue.add(itemValue.negate());
                        }
                    }
                }
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

            // Bug1 修复: 盘盈把批次从 0 恢复为正数后, 必须同步把 stale 的 USED_UP/DEPLETED
            // 消耗终态重置为 AVAILABLE — 否则批次继续被 findAvailable / FEFO 等查询排除,
            // 恢复的库存在报工/发货/领料 picker 里不可见 = 事实上仍然"丢失"。反向: 盘亏把批次
            // 耗光时同步置 USED_UP, 与 markBatchAsUsedUp / adjustBatchQuantity 的耗尽语义一致。
            // 镜像 ReportReversalServiceImpl.restoreMaterialBatchConsumption /
            // InterimSettleReversalServiceImpl 的恢复判定 (USED_UP/DEPLETED → AVAILABLE
            // 当且仅当剩余可用量 > 0); 其他状态 (EXPIRED/SCRAPPED/DEFECTIVE/RESERVED/INSPECTING
            // 等各自独立生命周期) 不动, 避免误改无关状态机。
            BigDecimal availableAfter = batch.getCurrentQuantity(); // receiptQuantity - usedQuantity - reservedQuantity
            if (availableAfter != null && availableAfter.compareTo(BigDecimal.ZERO) > 0
                    && (batch.getStatus() == MaterialBatchStatus.USED_UP
                        || batch.getStatus() == MaterialBatchStatus.DEPLETED)) {
                batch.setStatus(MaterialBatchStatus.AVAILABLE);
            } else if (availableAfter != null && availableAfter.compareTo(BigDecimal.ZERO) == 0
                    && batch.getStatus() == MaterialBatchStatus.AVAILABLE) {
                batch.setStatus(MaterialBatchStatus.USED_UP);
            }

            materialBatchRepo.save(batch);
            if (item.getDifferenceQty().compareTo(BigDecimal.ZERO) < 0) {
                inventoryLowStockEventPublisher.publishIfLowStock(factoryId, batch, "ADJUST");
            }
        }

        // 原料盘点差异过账 (与库存调整同一 @Transactional — 过账失败则整个 apply 回滚,
        // 绝不留下"库存已调但凭证未过"的不一致态)。逐项 + 批量 一致过账 (Decision 3)。
        if (posting) {
            postStocktakeVoucher(stocktake, factoryId, surplusValue, shortageValue, uncostedCount, userId);
        }

        stocktake.setStatus(FactoryStocktake.Status.APPLIED);
        stocktake.setAppliedAt(LocalDateTime.now());
        stocktake.setAppliedBy(userId);
        stocktakeRepo.save(stocktake);
        log.info("SP7: 盘点任务已生效 stocktakeId={} importMode={}", stocktakeId, stocktake.getImportMode());
    }

    /**
     * 原料盘点差异过账。NORMAL/逐项=盘盈/盘亏损益; OPENING=期初建账权益 (Decision 2/3)。
     *
     * <p>逐项 UI 盘点 (importMode=null) 按 NORMAL 过账 — 修复原料盘点历史不过账缺口。
     * 复用 {@link VoucherService#createManual} (与半成品盘点同一过账机制, 非平行路径),
     * 经 (来源业务类型, stocktakeId) 唯一约束幂等; apply 状态守卫保证每单只跑一次 → 无双过账;
     * createManual 借贷必平校验 + 直建 POSTED。全部差异行 unitPrice=null → 不过账 (仅 warn), 绝不臆造价值。
     *
     * <p>Decision 2: 期初 (OPENING) 与 盘盈 (NORMAL) 绝不混 — 贷方科目不同 (4001 vs 6301)、
     * 摘要不同 ("期初建账" vs "盘点")、来源业务类型不同 (可分别筛选核对)。
     */
    private void postStocktakeVoucher(FactoryStocktake stocktake, String factoryId,
            BigDecimal surplusValue, BigDecimal shortageValue, int uncostedCount, Long userId) {
        boolean hasSurplus = surplusValue.signum() > 0;
        boolean hasShortage = shortageValue.signum() > 0;
        if (!hasSurplus && !hasShortage) {
            if (uncostedCount > 0) {
                log.warn("SP7 apply: 全部差异行未录价(unitPrice=null), 不过账凭证 stocktakeNo={} uncosted={}",
                        stocktake.getStocktakeNo(), uncostedCount);
            }
            return;
        }

        boolean opening = stocktake.getImportMode() == FactoryStocktake.ImportMode.OPENING;
        String no = stocktake.getStocktakeNo();
        List<VoucherEntrySpec> entries = new ArrayList<>();

        if (opening) {
            // 期初建账: 借 原材料 / 贷 实收资本 (盘盈方向); 盘亏方向借贷互换。不进 6301。
            if (hasSurplus) {
                entries.add(new VoucherEntrySpec(SUBJECT_INVENTORY_CODE, SUBJECT_INVENTORY_NAME,
                        surplusValue, null, "期初建账入库 " + no));
                entries.add(new VoucherEntrySpec(SUBJECT_OPENING_EQUITY_CODE, SUBJECT_OPENING_EQUITY_NAME,
                        null, surplusValue, "期初建账权益 " + no));
            }
            if (hasShortage) {
                entries.add(new VoucherEntrySpec(SUBJECT_OPENING_EQUITY_CODE, SUBJECT_OPENING_EQUITY_NAME,
                        shortageValue, null, "期初建账冲减权益 " + no));
                entries.add(new VoucherEntrySpec(SUBJECT_INVENTORY_CODE, SUBJECT_INVENTORY_NAME,
                        null, shortageValue, "期初建账减少存货 " + no));
            }
        } else {
            // NORMAL 常规盘点: 盘盈 借原材料/贷营业外收入; 盘亏 借管理费用/贷原材料。
            if (hasSurplus) {
                entries.add(new VoucherEntrySpec(SUBJECT_INVENTORY_CODE, SUBJECT_INVENTORY_NAME,
                        surplusValue, null, "原料盘盈 " + no));
                entries.add(new VoucherEntrySpec(SUBJECT_SURPLUS_INCOME_CODE, SUBJECT_SURPLUS_INCOME_NAME,
                        null, surplusValue, "原料盘盈收益 " + no));
            }
            if (hasShortage) {
                entries.add(new VoucherEntrySpec(SUBJECT_SHORTAGE_LOSS_CODE, SUBJECT_SHORTAGE_LOSS_NAME,
                        shortageValue, null, "原料盘亏损耗 " + no));
                entries.add(new VoucherEntrySpec(SUBJECT_INVENTORY_CODE, SUBJECT_INVENTORY_NAME,
                        null, shortageValue, "原料盘亏库存减少 " + no));
            }
        }

        String summary = no + (opening ? " 原料期初建账" : " 原料盘点")
                + (hasSurplus ? "盘盈" : "") + (hasShortage ? "盘亏" : "") + "凭证";
        String sourceType = opening ? VOUCHER_SOURCE_TYPE_OPENING : VOUCHER_SOURCE_TYPE_NORMAL;
        voucherService.createManual(factoryId, VoucherType.INVENTORY_STOCKTAKE,
                LocalDate.now(), entries, sourceType, stocktake.getId(), summary, userId);
        log.info("SP7 apply: 已过账差异凭证 stocktakeNo={} mode={} sourceType={} 盘盈={} 盘亏={} uncosted={}",
                no, stocktake.getImportMode(), sourceType, surplusValue, shortageValue, uncostedCount);
    }

    @Override
    public StocktakeDiffPreviewDTO previewDiff(String stocktakeId, String factoryId) {
        FactoryStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        StocktakeDiffPreviewDTO preview = new StocktakeDiffPreviewDTO();
        preview.setStocktakeId(stocktakeId);
        preview.setStocktakeNo(stocktake.getStocktakeNo());
        preview.setPeriodMonth(stocktake.getPeriodMonth());
        preview.setInventoryCutoffAt(effectiveInventoryCutoff(stocktake));
        preview.setCountingStartedAt(stocktake.getCountingStartedAt());
        preview.setReconciliationStartAt(stocktake.getReconciliationStartAt());
        preview.setReconciliationEndAt(stocktake.getReconciliationEndAt() != null
                ? stocktake.getReconciliationEndAt() : effectiveInventoryCutoff(stocktake));

        // 🔴 Fix (materialName 空白, 2026-07): 批量反查 batch → materialTypeId → 物料名,
        // 镜像 StocktakeBulkImportServiceImpl.loadMaterialNames() 的解法, 避免 previewDiff
        // 表格「物料名称」列一直渲染空白 (此前只 setBatchNumber, 从未 setMaterialName)。
        List<String> batchIds = stocktake.getItems().stream()
                .map(FactoryStocktakeItem::getMaterialBatchId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toList());
        Map<String, MaterialBatch> batchById = materialBatchRepo.findAllById(batchIds).stream()
                .filter(batch -> factoryId.equals(batch.getFactoryId()))
                .filter(batch -> Objects.equals(stocktake.getWarehouseId(), batch.getWarehouseId()))
                .collect(Collectors.toMap(MaterialBatch::getId, b -> b, (a, b) -> a));
        List<String> typeIds = batchById.values().stream()
                .map(MaterialBatch::getMaterialTypeId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .collect(Collectors.toList());
        Map<String, String> nameByTypeId = rawMaterialTypeRepo.findAllById(typeIds).stream()
                .filter(material -> factoryId.equals(material.getFactoryId()))
                .collect(Collectors.toMap(RawMaterialType::getId, RawMaterialType::getName, (a, b) -> a));
        List<String> productTypeIds = batchById.values().stream()
                .map(MaterialBatch::getProductTypeId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .collect(Collectors.toList());
        Map<String, String> productNameByTypeId = productTypeRepo.findAllById(productTypeIds).stream()
                .filter(product -> factoryId.equals(product.getFactoryId()))
                .collect(Collectors.toMap(ProductType::getId, ProductType::getName, (a, b) -> a));

        List<StocktakeDiffPreviewDTO.DiffLine> lines = new ArrayList<>();
        int surplus = 0, shortage = 0, match = 0;
        for (FactoryStocktakeItem item : stocktake.getItems()) {
            StocktakeDiffPreviewDTO.DiffLine line = new StocktakeDiffPreviewDTO.DiffLine();
            line.setItemId(item.getId());
            line.setMaterialBatchId(item.getMaterialBatchId());
            line.setSystemQty(item.getSystemQty());
            line.setActualQty(item.getActualQty());
            line.setDifferenceQty(item.getDifferenceQty());
            String differenceType = item.getDifferenceType() != null ? item.getDifferenceType().name() : null;
            if (differenceType == null && item.getDifferenceQty() != null
                    && item.getDifferenceQty().compareTo(BigDecimal.ZERO) == 0) {
                differenceType = FactoryStocktakeItem.DifferenceType.MATCH.name();
            }
            line.setDifferenceType(differenceType);

            // 获取批次号 + 物料名称用于展示
            MaterialBatch batch = batchById.get(item.getMaterialBatchId());
            if (batch != null) {
                line.setBatchNumber(batch.getBatchNumber());
                line.setQuantityUnit(batch.getQuantityUnit());
                line.setMaterialName(batch.getMaterialTypeId() != null
                        ? nameByTypeId.get(batch.getMaterialTypeId())
                        : productNameByTypeId.get(batch.getProductTypeId()));
            }

            // This endpoint is the approval/application impact preview: zero-difference
            // rows remain visible in the stocktake detail, not in affected-batch output.
            if (item.getDifferenceQty() != null
                    && item.getDifferenceQty().compareTo(BigDecimal.ZERO) != 0) {
                lines.add(line);
            }
            if (FactoryStocktakeItem.DifferenceType.SURPLUS.name().equals(differenceType)) surplus++;
            else if (FactoryStocktakeItem.DifferenceType.SHORTAGE.name().equals(differenceType)) shortage++;
            else if (FactoryStocktakeItem.DifferenceType.MATCH.name().equals(differenceType)) match++;
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
                .map(stocktake -> enrichHeader(StocktakeDTO.from(stocktake), stocktake, factoryId));
    }

    @Override
    public StocktakeDTO getDetail(String stocktakeId, String factoryId) {
        FactoryStocktake stocktake = findAndValidate(stocktakeId, factoryId);
        return toDetailedDTO(stocktake, factoryId);
    }

    @Override
    @Transactional
    public String submitForApproval(String stocktakeId, String factoryId, Long userId) {
        FactoryStocktake stocktake = findAndValidateForUpdate(stocktakeId, factoryId);
        if (stocktake.getStatus() == FactoryStocktake.Status.PENDING_APPROVAL) {
            if (stocktake.getWorkflowInstanceId() != null && !stocktake.getWorkflowInstanceId().isBlank()) {
                return stocktake.getWorkflowInstanceId();
            }
            throw new BusinessException(409, "历史待审批盘点未关联 OA 实例，不能静默补建")
                    .withCode("STOCKTAKE_LEGACY_WORKFLOW_MISSING")
                    .withHint("请按受控迁移流程处理历史记录");
        }
        if (stocktake.getStatus() != FactoryStocktake.Status.COUNTING &&
            stocktake.getStatus() != FactoryStocktake.Status.INITIATED &&
            stocktake.getStatus() != FactoryStocktake.Status.REJECTED) {
            throw new BusinessException(409,
                    "当前盘点任务状态 [" + stocktake.getStatus() + "] 不支持提交审批，需要 COUNTING 或 INITIATED 或 REJECTED（重提）"
                    + " — 请前往[审批中心]查看进行中的审批")
                .withHint("前往审批中心");
        }
        assertAllItemsCounted(stocktake);

        if (workflowEngine == null || !workflowEngine.hasActiveWorkflow(factoryId, "INVENTORY_ADJUSTMENT")) {
            throw new BusinessException(422, "未配置可用的盘点 OA 审批流程，不能提交")
                    .withCode("STOCKTAKE_WORKFLOW_REQUIRED")
                    .withHint("请配置 INVENTORY_ADJUSTMENT 审批流程");
        }
        stocktake.setStatus(FactoryStocktake.Status.PENDING_APPROVAL);
        stocktake.setSubmittedBy(userId);
        stocktake.setSubmittedAt(LocalDateTime.now());
        ApprovalWorkflowInstance instance = workflowEngine.startWorkflow(
                factoryId, "INVENTORY_ADJUSTMENT", stocktakeId,
                Map.of("stocktakeNo", stocktake.getStocktakeNo(),
                       "periodMonth", stocktake.getPeriodMonth(),
                       "warehouseId", stocktake.getWarehouseId()), userId);
        stocktake.setWorkflowInstanceId(instance.getId());

        stocktakeRepo.save(stocktake);
        log.info("SP12: 盘点任务已提交审批 stocktakeId={} workflowInstanceId={}",
                stocktakeId, stocktake.getWorkflowInstanceId());
        return stocktake.getWorkflowInstanceId();
    }

    @Override
    @Transactional
    public FactoryStocktake applyWorkflowAction(String factoryId, String stocktakeId, String instanceId,
            Long actorId, String actorRole, HistoryAction action, String notes) {
        FactoryStocktake stocktake = findAndValidateForUpdate(stocktakeId, factoryId);
        if (workflowEngine == null) {
            throw new BusinessException(503, "OA 审批服务不可用").withCode("STOCKTAKE_WORKFLOW_UNAVAILABLE");
        }
        ApprovalWorkflowInstance instance = workflowEngine.getInstance(factoryId, instanceId)
                .orElseThrow(() -> new BusinessException(404, "OA 审批实例不存在")
                        .withCode("STOCKTAKE_WORKFLOW_NOT_FOUND"));
        if (!"INVENTORY_ADJUSTMENT".equals(instance.getModuleCode())
                || !stocktakeId.equals(instance.getBusinessEntityId())
                || !instanceId.equals(stocktake.getWorkflowInstanceId())) {
            throw new BusinessException(400, "OA 审批实例与盘点任务不匹配")
                    .withCode("STOCKTAKE_WORKFLOW_IDENTITY_MISMATCH");
        }
        if (instance.getStatus() != ApprovalWorkflowInstance.InstanceStatus.RUNNING) return stocktake;
        if (action == HistoryAction.REJECT && (notes == null || notes.isBlank())) {
            throw new BusinessException(422, "驳回盘点必须填写原因")
                    .withCode("STOCKTAKE_REJECT_REASON_REQUIRED");
        }
        boolean hasDifference = normalizeAndValidateEvidence(stocktake);
        boolean sameMaker = Objects.equals(stocktake.getInitiatedBy(), actorId)
                || Objects.equals(stocktake.getCountedBy(), actorId)
                || Objects.equals(stocktake.getSubmittedBy(), actorId);
        if (action == HistoryAction.APPROVE && hasDifference && sameMaker) {
            throw new BusinessException(409, "存在盘盈/盘亏时，发起人、录入人或提交人不能审批自己的盘点")
                    .withCode("STOCKTAKE_SELF_APPROVAL_FORBIDDEN");
        }
        ApprovalWorkflowInstance updated = workflowEngine.transitionNode(instanceId, actorId, actorRole, action, notes);
        if (updated.getStatus() == ApprovalWorkflowInstance.InstanceStatus.APPROVED) {
            stocktake.setStatus(FactoryStocktake.Status.APPROVED);
            stocktake.setApprovedBy(actorId);
            stocktake.setApprovedAt(LocalDateTime.now());
            stocktake.setSelfConfirmedZeroDifference(!hasDifference && sameMaker);
        } else if (updated.getStatus() == ApprovalWorkflowInstance.InstanceStatus.REJECTED) {
            stocktake.setStatus(FactoryStocktake.Status.REJECTED);
            stocktake.setRejectReason(notes);
            stocktake.setApprovedBy(actorId);
            stocktake.setApprovedAt(LocalDateTime.now());
        }
        return stocktakeRepo.save(stocktake);
    }

    private void assertAllItemsCounted(FactoryStocktake stocktake) {
        long uncounted = stocktake.getItems() == null ? 0 : stocktake.getItems().stream()
                .filter(item -> item.getActualQty() == null)
                .count();
        if (uncounted > 0) {
            throw new BusinessException(400, "尚有" + uncounted + "行未盘点，空白不等于账实一致")
                    .withCode("STOCKTAKE_ITEMS_UNCOUNTED")
                    .withHint("请逐行录入，或使用“全部按账面数量填入”后保存暂存");
        }
    }

    private void enrichItemIdentity(StocktakeDTO dto, FactoryStocktake stocktake, String factoryId) {
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            return;
        }
        List<String> batchIds = dto.getItems().stream()
                .map(StocktakeDTO.StocktakeItemDTO::getMaterialBatchId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, MaterialBatch> batchById = materialBatchRepo.findAllById(batchIds).stream()
                .filter(batch -> factoryId.equals(batch.getFactoryId()))
                .filter(batch -> Objects.equals(stocktake.getWarehouseId(), batch.getWarehouseId()))
                .collect(Collectors.toMap(MaterialBatch::getId, batch -> batch, (left, right) -> left));
        List<String> materialTypeIds = batchById.values().stream()
                .map(MaterialBatch::getMaterialTypeId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, RawMaterialType> materialById = rawMaterialTypeRepo.findAllById(materialTypeIds).stream()
                .filter(material -> factoryId.equals(material.getFactoryId()))
                .collect(Collectors.toMap(RawMaterialType::getId, material -> material, (left, right) -> left));
        List<String> productTypeIds = batchById.values().stream()
                .map(MaterialBatch::getProductTypeId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, ProductType> productById = productTypeRepo.findAllById(productTypeIds).stream()
                .filter(product -> factoryId.equals(product.getFactoryId()))
                .collect(Collectors.toMap(ProductType::getId, product -> product, (left, right) -> left));
        dto.getItems().forEach(item -> {
            MaterialBatch batch = batchById.get(item.getMaterialBatchId());
            if (batch == null) {
                return;
            }
            item.setBatchNumber(batch.getBatchNumber());
            item.setQuantityUnit(batch.getQuantityUnit());
            RawMaterialType material = materialById.get(batch.getMaterialTypeId());
            if (material != null) {
                item.setRawMaterialTypeId(material.getId());
                item.setProductTypeId(null);
                item.setMaterialCode(material.getCode());
                item.setMaterialName(material.getName());
                return;
            }
            ProductType product = productById.get(batch.getProductTypeId());
            if (product != null) {
                item.setRawMaterialTypeId(null);
                item.setProductTypeId(product.getId());
                item.setMaterialCode(product.getCode());
                item.setMaterialName(product.getName());
            }
        });
    }

    private String inventoryTypeId(MaterialBatch batch) {
        if (batch.getMaterialTypeId() != null && !batch.getMaterialTypeId().isBlank()) {
            return batch.getMaterialTypeId();
        }
        return batch.getProductTypeId();
    }

    private StocktakeDTO toDetailedDTO(FactoryStocktake stocktake, String factoryId) {
        StocktakeDTO dto = enrichHeader(StocktakeDTO.from(stocktake), stocktake, factoryId);
        enrichItemIdentity(dto, stocktake, factoryId);
        dto.setApprovalEvidence(buildApprovalEvidence(dto));
        return dto;
    }

    private StocktakeDTO enrichHeader(StocktakeDTO dto, FactoryStocktake stocktake, String factoryId) {
        LocalDateTime effectiveCutoff = effectiveInventoryCutoff(stocktake);
        if (dto.getInventoryCutoffAt() == null) {
            dto.setInventoryCutoffAt(effectiveCutoff);
        }
        if (dto.getReconciliationEndAt() == null) {
            dto.setReconciliationEndAt(effectiveCutoff);
        }
        dto.setInitiatedByDisplay(actorDisplay(factoryId, stocktake.getInitiatedBy()));
        dto.setCountedByDisplay(actorDisplay(factoryId, stocktake.getCountedBy()));
        dto.setSubmittedByDisplay(actorDisplay(factoryId, stocktake.getSubmittedBy()));
        dto.setApprovedByDisplay(actorDisplay(factoryId, stocktake.getApprovedBy()));
        dto.setAppliedByDisplay(actorDisplay(factoryId, stocktake.getAppliedBy()));
        return dto;
    }

    private LocalDateTime effectiveInventoryCutoff(FactoryStocktake stocktake) {
        if (stocktake.getInventoryCutoffAt() != null) return stocktake.getInventoryCutoffAt();
        if (stocktake.getInitiatedAt() != null) return stocktake.getInitiatedAt();
        return stocktake.getCreatedAt();
    }

    private String actorDisplay(String factoryId, Long userId) {
        if (userId == null) return null;
        if (userRepository == null) return "用户 " + userId;
        return userRepository.findByIdAndFactoryId(userId, factoryId)
                .map(user -> {
                    String username = user.getUsername();
                    return user.getFullName() != null && !user.getFullName().isBlank()
                            ? user.getFullName() + (username == null ? "" : "（" + username + "）")
                            : (username != null ? username : "用户 " + userId);
                })
                .orElse("用户 " + userId);
    }

    private StocktakeDTO.ApprovalEvidence buildApprovalEvidence(StocktakeDTO dto) {
        StocktakeDTO.ApprovalEvidence evidence = new StocktakeDTO.ApprovalEvidence();
        List<StocktakeDTO.StocktakeItemDTO> items = dto.getItems() == null ? List.of() : dto.getItems();
        Map<String, BigDecimal> surplusByUnit = new LinkedHashMap<>();
        Map<String, BigDecimal> shortageByUnit = new LinkedHashMap<>();
        int counted = 0, match = 0, surplus = 0, shortage = 0;
        for (StocktakeDTO.StocktakeItemDTO item : items) {
            if (item.getActualQty() == null) continue;
            counted++;
            BigDecimal difference = item.getDifferenceQty() != null
                    ? item.getDifferenceQty() : item.getActualQty().subtract(item.getSystemQty());
            String unit = item.getQuantityUnit() == null ? "-" : item.getQuantityUnit();
            if (difference.signum() > 0) {
                surplus++;
                surplusByUnit.merge(unit, difference, BigDecimal::add);
            } else if (difference.signum() < 0) {
                shortage++;
                shortageByUnit.merge(unit, difference.abs(), BigDecimal::add);
            } else {
                match++;
            }
        }
        evidence.setTotalCount(items.size());
        evidence.setCountedCount(counted);
        evidence.setUncountedCount(items.size() - counted);
        evidence.setMatchCount(match);
        evidence.setSurplusCount(surplus);
        evidence.setShortageCount(shortage);
        evidence.setSurplusQuantityByUnit(surplusByUnit);
        evidence.setShortageQuantityByUnit(shortageByUnit);
        evidence.setInventoryImpact(surplus > 0 || shortage > 0);
        evidence.setInventoryImpactMessage(surplus > 0 || shortage > 0
                ? "存在盘盈/盘亏，应用差异后将调整库存"
                : "零差异，不会调整库存；应用后完成盘点");
        return evidence;
    }

    /** Recomputes evidence under the stocktake row lock; never trusts a UI summary. */
    private boolean normalizeAndValidateEvidence(FactoryStocktake stocktake) {
        assertAllItemsCounted(stocktake);
        boolean hasDifference = false;
        for (FactoryStocktakeItem item : stocktake.getItems()) {
            if (item.getSystemQty() == null || item.getActualQty() == null) {
                throw new BusinessException(409, "盘点快照或实盘数量不完整")
                        .withCode("STOCKTAKE_EVIDENCE_INCOMPLETE");
            }
            BigDecimal expected = item.getActualQty().subtract(item.getSystemQty())
                    .setScale(4, RoundingMode.HALF_UP);
            if (item.getDifferenceQty() != null
                    && item.getDifferenceQty().setScale(4, RoundingMode.HALF_UP).compareTo(expected) != 0) {
                throw new BusinessException(409, "盘点差异已发生漂移，请重新打开并复核")
                        .withCode("STOCKTAKE_EVIDENCE_DRIFT");
            }
            item.setDifferenceQty(expected);
            if (expected.signum() > 0) {
                item.setDifferenceType(FactoryStocktakeItem.DifferenceType.SURPLUS);
                hasDifference = true;
            } else if (expected.signum() < 0) {
                item.setDifferenceType(FactoryStocktakeItem.DifferenceType.SHORTAGE);
                hasDifference = true;
            } else {
                item.setDifferenceType(FactoryStocktakeItem.DifferenceType.MATCH);
            }
        }
        return hasDifference;
    }

    private void assertExpectedVersion(FactoryStocktake stocktake, Long expectedVersion) {
        if (expectedVersion != null && !Objects.equals(expectedVersion, stocktake.getVersion())) {
            throw new BusinessException(409, "盘点数据已更新，请刷新后重新核对")
                    .withCode("STALE_STOCKTAKE_VERSION");
        }
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

    /**
     * 🔴🔒🔒 生产仓延迟扣减盲区 (2026-07-04): 逐批查未小结报工消耗量 (报工写未结
     * MaterialConsumption 但不即时扣 usedQuantity, 小结才扣)。返回 batchId → Σ未结消耗量,
     * 无未结消耗的批次不在 map 中 (调用方 {@link #physicalShelf} 按 0 处理 → 口径不变)。
     * 空/全 null 输入短路返回空 map, 不打无谓 SQL。谓词与 #1215 关单守卫同源, 继承其正确域。
     */
    private Map<String, BigDecimal> loadUnsettledByBatch(String factoryId, List<String> batchIds) {
        Map<String, BigDecimal> map = new java.util.HashMap<>();
        if (batchIds == null || batchIds.isEmpty()) {
            return map;
        }
        List<String> ids = batchIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return map;
        }
        for (Object[] row : materialConsumptionRepo.sumUnsettledConsumptionGroupedByBatch(factoryId, ids)) {
            if (row == null || row.length < 2 || row[0] == null) {
                continue;
            }
            map.put((String) row[0], toBigDecimal(row[1]));
        }
        return map;
    }

    /**
     * 货架实物量 = max(0, physical − 未结报工消耗)。clamp 到 0 防超投场景 (未结消耗 > physical,
     * 小结本身会 BATCH_INSUFFICIENT 拦) 下算出负账面 → 反被仓管数 0 误判为假盘盈。unsettled=null → 减 0。
     */
    private static BigDecimal physicalShelf(BigDecimal physical, BigDecimal unsettled) {
        BigDecimal p = physical != null ? physical : BigDecimal.ZERO;
        BigDecimal u = unsettled != null ? unsettled : BigDecimal.ZERO;
        BigDecimal shelf = p.subtract(u);
        return shelf.signum() < 0 ? BigDecimal.ZERO : shelf;
    }

    /** SUM(BigDecimal) 聚合结果类型 driver 可能返 BigDecimal/别的 Number, 统一转 BigDecimal (不丢精度)。 */
    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(v.toString());
    }

    private FactoryStocktake findAndValidate(String stocktakeId, String factoryId) {
        FactoryStocktake stocktake = stocktakeRepo.findById(stocktakeId)
                .orElseThrow(() -> new BusinessException(404, "盘点任务不存在: " + stocktakeId));
        if (!factoryId.equals(stocktake.getFactoryId())) {
            throw new BusinessException(403, "无权操作该盘点任务");
        }
        return stocktake;
    }

    private FactoryStocktake findAndValidateForUpdate(String stocktakeId, String factoryId) {
        return stocktakeRepo.findByIdAndFactoryIdForUpdate(stocktakeId, factoryId)
                .orElseThrow(() -> new BusinessException(404,
                        "盘点任务不存在或不属于当前工厂: " + stocktakeId));
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
