package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.foodsafety.FoodSample;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.foodsafety.FoodSampleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 食品留样创建 Tool (WRITE + Preview, R4 5min dedup) — Sprint 9 P2.A.
 *
 * <p>法定: GB 31654-2021 餐饮服务从业人员食品安全管理规范 — 每批食品出货时
 * 留样 >= 125g, 冷藏 (<= 8.0 ℃) 保存 48h.
 *
 * <p>自动计算 {@code expireTime = sampleTime + 48h}, 默认 {@code storageTemperatureMax = 8.0 ℃}.
 *
 * <p>防呆 4 位一体 (per .claude/rules/fool-proof-design.md):
 * <ul>
 *   <li>R1: preview 显批次产品名 + 物料 + 留样要求 (>= 125g)</li>
 *   <li>R2: message 含 batchNumber + productName (上下文)</li>
 *   <li>R3: sampleQuantity < 125g 警告 (默认 enforcement)</li>
 *   <li>R4: 5min 内同 batchNumber 留样 → 409 dedup, 返 existingId</li>
 * </ul>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"留样 B-20260521-A01"</li>
 *   <li>"卤猪蹄 200g 留样"</li>
 *   <li>"今天这批留样 130g 在 1号冷柜"</li>
 *   <li>"创建留样记录"</li>
 * </ul>
 *
 * <p>Intent Code: {@code FOOD_SAMPLE_CREATE}
 */
@Slf4j
@Component
public class FoodSampleCreateTool extends AbstractBusinessTool {

    /** GB 31654-2021 法定最小留样重量 (g). */
    private static final BigDecimal MIN_SAMPLE_QUANTITY_G = new BigDecimal("125");

    /** GB 31654-2021 法定留样时长 (h). */
    private static final int RETENTION_HOURS = 48;

    /** R4 dedup 窗口 (min). */
    private static final int DEDUP_WINDOW_MINUTES = 5;

    @Autowired
    private FoodSampleRepository foodSampleRepository;

    @Autowired
    private MaterialBatchRepository materialBatchRepository;

    @Override
    public String getToolName() {
        return "food_sample_create";
    }

    @Override
    public String getDescription() {
        return "创建食品留样记录 (GB 31654-2021 强制法规, 48h 冷藏 >= 125g). "
                + "自动算 expireTime = now + 48h, 默认冷藏 <= 8.0 ℃. "
                + "LLM 触发: '留样 B-X' / '卤猪蹄留样' / '今天这批留样 130g 在 1号冷柜' / "
                + "'创建留样记录'. WRITE + Preview + R4 5min 幂等 dedup.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "批次号 (必填). e.g. B-20260521-A01");
        properties.put("batchNumber", batchNumber);

        Map<String, Object> materialBatchId = new HashMap<>();
        materialBatchId.put("type", "string");
        materialBatchId.put("description", "MaterialBatch.id (必填) UUID 字符串. 可通过 batchNumber 查找");
        properties.put("materialBatchId", materialBatchId);

        Map<String, Object> productName = new HashMap<>();
        productName.put("type", "string");
        productName.put("description", "产品名称 (必填). e.g. 卤猪蹄 200g");
        properties.put("productName", productName);

        Map<String, Object> sampleQuantity = new HashMap<>();
        sampleQuantity.put("type", "number");
        sampleQuantity.put("description", "留样重量 g (必填). GB 31654-2021 法规 >= 125g, 低于警告");
        properties.put("sampleQuantity", sampleQuantity);

        Map<String, Object> storageLocation = new HashMap<>();
        storageLocation.put("type", "string");
        storageLocation.put("description", "冷柜位置 (必填). e.g. 1号冷柜A区");
        properties.put("storageLocation", storageLocation);

        schema.put("properties", properties);
        schema.put("required", List.of("batchNumber", "materialBatchId",
                "productName", "sampleQuantity", "storageLocation"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("batchNumber", "materialBatchId",
                "productName", "sampleQuantity", "storageLocation");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.WRITE;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.MEDIUM;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchNumber = getString(params, "batchNumber");
        String materialBatchId = getString(params, "materialBatchId");
        String productName = getString(params, "productName");
        BigDecimal sampleQuantity = getBigDecimal(params, "sampleQuantity");
        String storageLocation = getString(params, "storageLocation");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PREVIEW");
        result.put("batchNumber", batchNumber);
        result.put("materialBatchId", materialBatchId);
        result.put("productName", productName);
        result.put("sampleQuantity", sampleQuantity);
        result.put("unit", "g");
        result.put("storageLocation", storageLocation);
        result.put("minSampleQuantity", MIN_SAMPLE_QUANTITY_G);
        result.put("retentionHours", RETENTION_HOURS);
        result.put("storageTemperatureMax", new BigDecimal("8.0"));

        // R1 防呆: < 125g 警告
        boolean belowLegalMin = sampleQuantity != null
                && sampleQuantity.compareTo(MIN_SAMPLE_QUANTITY_G) < 0;
        result.put("belowLegalMin", belowLegalMin);

        // R4 dedup check: 5min 窗口内同 batch 已留样
        LocalDateTime dedupSince = LocalDateTime.now().minusMinutes(DEDUP_WINDOW_MINUTES);
        List<FoodSample> recent = foodSampleRepository
                .findByFactoryIdAndBatchNumberAndSampleTimeAfter(factoryId, batchNumber, dedupSince);
        if (!recent.isEmpty()) {
            FoodSample existing = recent.get(0);
            result.put("canDo", false);
            result.put("status", "DUPLICATE");
            result.put("existingId", existing.getId());
            result.put("existingSampleTime", existing.getSampleTime().toString());
            result.put("actionHint", "/foodsafety/food-samples/" + existing.getId());
            result.put("message", String.format(
                    "ℹ️ 批次 %s 5 分钟内已有留样记录 (id=%d, sample_time=%s). " +
                    "如需新建请等 5 分钟或查看现有记录",
                    batchNumber, existing.getId(), existing.getSampleTime()));
            return result;
        }

        // 验证 MaterialBatch 存在
        Optional<MaterialBatch> mbOpt = materialBatchRepository.findById(materialBatchId);
        if (mbOpt.isEmpty()) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ MaterialBatch id=%s 不存在, 无法关联留样", materialBatchId));
            return result;
        }

        LocalDateTime sampleTime = LocalDateTime.now();
        LocalDateTime expireTime = sampleTime.plusHours(RETENTION_HOURS);
        result.put("sampleTime", sampleTime.toString());
        result.put("expireTime", expireTime.toString());
        result.put("canDo", true);

        String warning = belowLegalMin
                ? String.format(" ⚠️ 留样重量 %sg 低于法规 %sg, 不符合 GB 31654-2021",
                        sampleQuantity, MIN_SAMPLE_QUANTITY_G)
                : "";
        result.put("message", String.format(
                "📋 创建留样预览 — 批次 %s, 产品 %s, %sg @ %s. " +
                "销毁时间 %s (留样 %dh 冷藏 ≤ 8.0 ℃).%s",
                batchNumber, productName, sampleQuantity, storageLocation,
                expireTime, RETENTION_HOURS, warning));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchNumber = getString(params, "batchNumber");
        String materialBatchId = getString(params, "materialBatchId");
        String productName = getString(params, "productName");
        BigDecimal sampleQuantity = getBigDecimal(params, "sampleQuantity");
        String storageLocation = getString(params, "storageLocation");
        Long userId = getUserId(context);

        log.info("food_sample_create — factory={} batch={} productName={} quantity={} userId={}",
                factoryId, batchNumber, productName, sampleQuantity, userId);

        // R4 幂等: 5min 内同 batchNumber 已留样 → 返 existingId 不创建
        LocalDateTime dedupSince = LocalDateTime.now().minusMinutes(DEDUP_WINDOW_MINUTES);
        List<FoodSample> recent = foodSampleRepository
                .findByFactoryIdAndBatchNumberAndSampleTimeAfter(factoryId, batchNumber, dedupSince);
        if (!recent.isEmpty()) {
            FoodSample existing = recent.get(0);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "DUPLICATE");
            data.put("existingId", existing.getId());
            data.put("batchNumber", batchNumber);
            data.put("sampleTime", existing.getSampleTime().toString());
            data.put("count", 0);
            data.put("actionHint", "/foodsafety/food-samples/" + existing.getId());
            return buildSimpleResult(String.format(
                    "ℹ️ 批次 %s 5 分钟内已有留样记录 (id=%d), 跳过重复创建",
                    batchNumber, existing.getId()), data);
        }

        // 验证 MaterialBatch 存在
        Optional<MaterialBatch> mbOpt = materialBatchRepository.findById(materialBatchId);
        if (mbOpt.isEmpty()) {
            throw new BusinessException(404,
                    String.format("MaterialBatch id=%s 不存在, 无法关联留样", materialBatchId))
                    .withSeverity("BLOCKING");
        }

        LocalDateTime sampleTime = LocalDateTime.now();
        LocalDateTime expireTime = sampleTime.plusHours(RETENTION_HOURS);

        FoodSample sample = FoodSample.builder()
                .factoryId(factoryId)
                .batchNumber(batchNumber)
                .materialBatchId(materialBatchId)
                .productName(productName)
                .sampleQuantity(sampleQuantity)
                .unit("g")
                .sampleTime(sampleTime)
                .expireTime(expireTime)
                .storageTemperatureMax(new BigDecimal("8.0"))
                .storageLocation(storageLocation)
                .retainedByUserId(userId)
                .status("RETAINED")
                .build();

        sample = foodSampleRepository.save(sample);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", sample.getId());
        data.put("batchNumber", batchNumber);
        data.put("materialBatchId", materialBatchId);
        data.put("productName", productName);
        data.put("sampleQuantity", sampleQuantity);
        data.put("unit", "g");
        data.put("sampleTime", sampleTime.toString());
        data.put("expireTime", expireTime.toString());
        data.put("status", "RETAINED");
        data.put("storageLocation", storageLocation);
        data.put("retainedByUserId", userId);
        data.put("actionHint", "/foodsafety/food-samples/" + sample.getId());

        boolean belowLegalMin = sampleQuantity.compareTo(MIN_SAMPLE_QUANTITY_G) < 0;
        String warning = belowLegalMin
                ? String.format(" ⚠️ 留样重量 %sg 低于法规 %sg (GB 31654-2021)",
                        sampleQuantity, MIN_SAMPLE_QUANTITY_G)
                : "";
        String message = String.format(
                "✅ 已留样 — 批次 %s (%s), %sg @ %s. 销毁时间 %s (48h 冷藏 ≤ 8.0 ℃).%s",
                batchNumber, productName, sampleQuantity, storageLocation, expireTime, warning);
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
