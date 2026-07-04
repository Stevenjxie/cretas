package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.CreateStocktakeRequest;
import com.cretas.aims.dto.factory.StocktakeDiffPreviewDTO;
import com.cretas.aims.dto.factory.StocktakeDTO;
import com.cretas.aims.dto.factory.StocktakeItemUpdateDTO;
import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryStocktakeItem;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
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
    /**
     * 🔴🔒🔒 生产仓「延迟扣减」盘点盲区修复 (2026-07-04): 快照/漂移比对须从货架实物量扣掉
     * 未小结的报工消耗 (报工写未结 MaterialConsumption 但不扣 usedQuantity, 小结才扣)。
     */
    private final MaterialConsumptionRepository materialConsumptionRepo;

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
        // 全局 threshold 不降 (NORMAL/逐项盘点仍受月底约束), 只对 OPENING 跳过。
        boolean opening = importMode == FactoryStocktake.ImportMode.OPENING;
        LocalDate today = LocalDate.now();
        if (!opening && today.getDayOfMonth() < monthEndThreshold) {
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
            item.setRawMaterialTypeId(batch.getMaterialTypeId());
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
        return StocktakeDTO.from(saved);
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
        result.put("nextAllowedDate", nextAllowedDate);
        return result;
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
