package com.cretas.aims.event.listener;

import com.cretas.aims.dto.bom.CreateBomRecipeRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.rd.ProductSample;
import com.cretas.aims.entity.rd.QuotationTask;
import com.cretas.aims.event.SampleApprovedEvent;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.rd.ProductSampleRepository;
import com.cretas.aims.repository.rd.QuotationTaskRepository;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 样品审核通过事件监听器 — Sprint 2 / Track F (S-RD-1 / N48) extended.
 *
 * <p>Approve 后自动编排 3 件事:
 * <ol>
 *   <li><b>QuotationTask</b> — 必建. status=PENDING, 等报价员接单.</li>
 *   <li><b>BomRecipe 草稿</b> — best-effort. 仅在 prerequisites 满足时建
 *       (sample.productTypeId 非空 + sample.mainMaterial 存在于 raw_material_types 字典).
 *       缺数据时跳过 (log warn), 不阻塞主流程; 用户后续在 BomConfigScreen 手动建.</li>
 *   <li><b>销售主管通知</b> — 必发. 调 NotificationService.notifyRole(factoryId, "SALES_MANAGER", title, body).
 *       当前 impl=LoggingNotificationServiceImpl 只 log; Track B1 merge 后 @Primary 切到
 *       DingTalkNotificationServiceImpl 业务代码 0 改. 内容根据 BOM 是否自动建成调整.</li>
 * </ol>
 *
 * <p>@Async + @Transactional: listener 异步执行, 不阻塞 approveSample HTTP 响应.
 * Approve 主事务 commit 后才 publish event, 这里 listener 内的写都是新事务.
 *
 * @author Cretas Team / Track F (Sprint 2)
 * @since 2026-05-15 (extended from QuotationTask-only listener)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SampleApprovedEventListener {

    private final ProductSampleRepository productSampleRepository;
    private final QuotationTaskRepository quotationTaskRepository;
    private final BomRecipeService bomRecipeService;
    private final RawMaterialTypeRepository materialTypeRepository;
    private final NotificationService notificationService;

    @EventListener
    @Async
    @Transactional
    public void handleSampleApproved(SampleApprovedEvent event) {
        ProductSample sample = productSampleRepository.findById(event.getSampleId()).orElse(null);
        if (sample == null) {
            log.warn("样品不存在: {}", event.getSampleId());
            return;
        }

        // 1. 自动建 QuotationTask (必建, 跟原 listener 兼容)
        QuotationTask task = createQuotationTask(event, sample);

        // 2. Best-effort 自动建 BOM 草稿 (失败不阻塞后续通知)
        BomRecipe bomRecipe = autoCreateBomDraft(sample);
        String bomRecipeId = bomRecipe != null ? bomRecipe.getId() : null;
        if (bomRecipeId != null) {
            sample.setBomProductTypeId(bomRecipeId);
            productSampleRepository.save(sample);
            // SP10: BOM 材料成本回写报价任务 (预报价"自动带入"的真实来源).
            // 草稿 BOM 若主原料已定价则 totalMaterialCost 非 null → 归一化到 per-kg 写入.
            // 主原料未定价 (totalMaterialCost=null) → 保持 task.bomMaterialCost=null (诚实留空).
            writeBackBomMaterialCost(task, bomRecipe);
        }

        // 3. 通知销售主管 (失败不阻塞主流程)
        notifySalesManager(event, sample, task, bomRecipeId);
    }

    // ==================== 1. QuotationTask ====================

    private QuotationTask createQuotationTask(SampleApprovedEvent event, ProductSample sample) {
        QuotationTask task = new QuotationTask();
        task.setFactoryId(event.getFactoryId());
        task.setTaskNumber(String.format("QT-%s-%04d",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")),
                System.nanoTime() % 10000));
        task.setSampleId(sample.getId());
        task.setProductTypeId(sample.getProductTypeId());
        task.setStatus("PENDING");
        task = quotationTaskRepository.save(task);
        log.info("样品审核通过 → 自动建报价任务: sampleId={}, taskNumber={}",
                sample.getId(), task.getTaskNumber());
        return task;
    }

    // ==================== 2. BOM 草稿 (best-effort) ====================

    /**
     * Best-effort 自动建 BOM 草稿. Prerequisites 不满足 (productTypeId 空 / 主原料字典缺)
     * 时返回 null, 让用户后续在 BomConfigScreen 手动建. 不抛异常.
     *
     * <p>设计意图: sample → BOM 是"种子草稿"语义, 不是完整配方. 仅保证 BomRecipe
     * 主表 + 1 个 placeholder item 建好, 用户在 BomConfigScreen 编辑 items 完成真正配方.
     */
    private BomRecipe autoCreateBomDraft(ProductSample sample) {
        if (sample.getProductTypeId() == null || sample.getProductTypeId().isBlank()) {
            log.warn("[BOM 自动建跳过] ProductSample 缺 productTypeId: sampleId={}", sample.getId());
            return null;
        }
        if (sample.getMainMaterial() == null || sample.getMainMaterial().isBlank()) {
            log.warn("[BOM 自动建跳过] ProductSample 缺 mainMaterial: sampleId={}", sample.getId());
            return null;
        }

        Optional<RawMaterialType> materialOpt = findMaterialByName(
                sample.getFactoryId(), sample.getMainMaterial());
        if (materialOpt.isEmpty()) {
            log.warn("[BOM 自动建跳过] 主原料 [{}] 未在 raw_material_types 字典找到: sampleId={}",
                    sample.getMainMaterial(), sample.getId());
            return null;
        }

        try {
            CreateBomRecipeRequest req = buildBomDraftRequest(sample, materialOpt.get().getId());
            BomRecipe recipe = bomRecipeService.createRecipe(sample.getFactoryId(), req);
            log.info("样品审核通过 → 自动建 BOM 草稿: sampleId={}, recipeId={}, recipeCode={}",
                    sample.getId(), recipe.getId(), recipe.getRecipeCode());
            return recipe;
        } catch (Exception e) {
            // best-effort: BOM 失败不让 QuotationTask 通知失败
            log.error("[BOM 自动建失败 — best-effort, 不阻塞] sampleId={}: {}",
                    sample.getId(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * SP10: 把 BOM 草稿的材料成本归一化到 per-kg 后回写报价任务 bomMaterialCost.
     *
     * <p>BomRecipe.totalMaterialCost 是"每 outputQuantityPerUnit (outputUnit)"的材料成本.
     * 报价以 元/kg 为口径 → 换算: perKg = totalMaterialCost / (outputQuantityPerUnit 折 kg).
     * 草稿默认 outputUnit=g, outputQuantityPerUnit=100 → 100g 成品的材料成本 ×10 = 每 kg.</p>
     *
     * <p>诚实留空: totalMaterialCost=null (主原料未定价) 或 outputQuantity 非法 → 不写 (保持 null).
     * best-effort: 任何异常都不阻塞主流程.</p>
     */
    private void writeBackBomMaterialCost(QuotationTask task, BomRecipe recipe) {
        try {
            if (recipe.getTotalMaterialCost() == null
                    || recipe.getOutputQuantityPerUnit() == null
                    || recipe.getOutputQuantityPerUnit().compareTo(BigDecimal.ZERO) <= 0) {
                return;
            }
            // outputQuantityPerUnit 折算到 kg
            BigDecimal outputKg = toKilograms(
                    recipe.getOutputQuantityPerUnit(), recipe.getOutputUnit());
            if (outputKg == null || outputKg.compareTo(BigDecimal.ZERO) <= 0) {
                return;
            }
            BigDecimal perKg = recipe.getTotalMaterialCost()
                    .divide(outputKg, 4, RoundingMode.HALF_UP);
            task.setBomMaterialCost(perKg);
            quotationTaskRepository.save(task);
            log.info("[SP10] BOM 材料成本回写报价任务: taskId={}, bomMaterialCost={}/kg (recipe={})",
                    task.getId(), perKg, recipe.getId());
        } catch (Exception e) {
            log.error("[SP10] BOM 材料成本回写失败 — best-effort, 不阻塞: taskId={}: {}",
                    task.getId(), e.getMessage(), e);
        }
    }

    /** 输出量折算到 kg. 仅支持常见 g/kg, 其他单位返 null (不写, 诚实留空). */
    private BigDecimal toKilograms(BigDecimal quantity, String unit) {
        if (unit == null) {
            return null;
        }
        switch (unit.trim().toLowerCase()) {
            case "kg":
            case "千克":
            case "公斤":
                return quantity;
            case "g":
            case "克":
                return quantity.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
            default:
                return null;
        }
    }

    /**
     * 字典通常 < 500 records, 用现存 findByFactoryId 拿全部 + name 精确匹配 (case-insensitive).
     * 避免给 RawMaterialTypeRepository 加新 query 方法 (ownership 外文件).
     */
    private Optional<RawMaterialType> findMaterialByName(String factoryId, String name) {
        return materialTypeRepository.findByFactoryId(factoryId).stream()
                .filter(m -> name.equalsIgnoreCase(m.getName()))
                .findFirst();
    }

    private CreateBomRecipeRequest buildBomDraftRequest(ProductSample sample, String materialTypeId) {
        CreateBomRecipeRequest req = new CreateBomRecipeRequest();
        req.setProductTypeId(sample.getProductTypeId());
        req.setProductName(sample.getName());
        req.setOutputQuantityPerUnit(new BigDecimal("100"));  // 默认 100g/份
        req.setOutputUnit("g");
        req.setSourceType(BomRecipe.SourceType.SAMPLE_AUTOGEN);
        req.setSourceSampleId(sample.getId());
        req.setNotes("由样品 " + sample.getSampleCode() + " (" + sample.getName()
                + ") 审核通过自动生成草稿. 请在 BomConfigScreen 完善配方项.");

        CreateBomRecipeRequest.BomRecipeItemDTO item = new CreateBomRecipeRequest.BomRecipeItemDTO();
        item.setMaterialTypeId(materialTypeId);
        item.setStandardQuantity(new BigDecimal("100"));  // 占位: 1 份成品需 100g 主原料
        item.setUnit("g");
        item.setMaterialCategory("RAW");
        item.setSortOrder(0);
        req.setItems(List.of(item));
        return req;
    }

    // ==================== 3. 销售主管通知 ====================

    private void notifySalesManager(SampleApprovedEvent event, ProductSample sample,
                                     QuotationTask task, String bomRecipeId) {
        try {
            String title = "样品审核通过";
            String body = bomRecipeId != null
                    ? String.format(
                            "样品 [%s] %s 已审核通过. BOM 草稿已自动生成 (id=%s), 报价任务 [%s] 等待报价员处理. 请尽快联系客户确认报价.",
                            sample.getSampleCode(), sample.getName(), bomRecipeId, task.getTaskNumber())
                    : String.format(
                            "样品 [%s] %s 已审核通过, 报价任务 [%s] 等待报价员处理. 注: BOM 需研发员手动建立 (sample 缺 productTypeId 或主原料未在字典).",
                            sample.getSampleCode(), sample.getName(), task.getTaskNumber());

            notificationService.notifyRole(event.getFactoryId(), "SALES_MANAGER", title, body);
            log.info("销售主管通知发送 (via NotificationService.notifyRole): factoryId={}, sampleId={}",
                    event.getFactoryId(), sample.getId());
        } catch (Exception e) {
            // 通知失败不阻塞主流程 — QuotationTask 已建, BOM 已建 (或 best-effort 失败), 业务可继续
            log.error("[销售通知发送失败 — 不阻塞主流程] sampleId={}: {}",
                    sample.getId(), e.getMessage(), e);
        }
    }
}
