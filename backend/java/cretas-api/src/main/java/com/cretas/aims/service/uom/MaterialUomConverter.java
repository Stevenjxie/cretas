package com.cretas.aims.service.uom;

import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.UnitConversionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * MaterialUomConverter — 物料单位换算器 (T143, 2026-06-08).
 *
 * <p><b>背景 (六扇门 F006 prod 数据确认):</b> BOM 配方用 RECIPE 单位 (克/g,
 * 例 猪舌 BOM 冷冻猪舌 standardQuantity=120 unit=g, yield 55.24% → 217g/份),
 * 而库存 ({@link RawMaterialType#unit}) 用 STOCK 单位 (箱). 4 个断点直接
 * 比较两者 (g vs 箱) 不换算 → 误报"原料不足" / 错误调拨数量 ("217 箱").
 *
 * <p>桥接数据 {@link MaterialPackagingHierarchy#getLevel1PerLevel2()} (例 10 kg/箱)
 * 之前只用于展示, 从未接进库存校验. 本类把它接进唯一换算入口.
 *
 * <p><b>抄码料 ({@link RawMaterialType#getIsAbacaPackaging()}=true):</b> 每箱重量
 * 不一 → 没有确定的 箱↔重量 系数. 客户原话 (六扇门第四次 May10 line 83-85):
 * "我拆一箱鸡爪可能十公斤…我拆牛肉可能看了个公斤…它一箱是超码的". 返回
 * {@link ConversionResult#abacaSkip()} 让 caller 跳过按规格的库存校验 (不阻断).
 *
 * <p><b>无法换算 (非抄码, 单位不同, 无装箱规格, 非同维度):</b> 返回
 * {@link ConversionResult#unconvertible()} — <b>绝不</b>静默 default 成 1.0 或
 * 裸比较大小 (那正是 T143 要修的 bug). Caller 必须 fail-loud (抛
 * {@code MATERIAL_UOM_UNCONFIGURED} 引导用户去配置).
 *
 * <p>禁止读 {@code MaterialBatch.weightPerUnit} 做换算 — 它默认 1.0 是地雷;
 * 只信 {@link MaterialPackagingHierarchy} 配置数据 + g↔kg/ml↔L 维度换算.
 *
 * @author Cretas Team / T143
 * @since 2026-06-08
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialUomConverter {

    private final MaterialPackagingHierarchyRepository packagingRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final UnitConversionService unitConversionService;

    /**
     * 把一个 (qty, fromUnit) 换算到 materialStockUnit, 使其可与库存量直接比较.
     *
     * @param materialTypeId    物料类型 ID (查装箱规格 + 抄码标记)
     * @param qty               原值 (BOM 配方需求量, 单位 = fromUnit)
     * @param fromUnit          源单位 (BOM 单位, e.g. "g")
     * @param materialStockUnit 库存单位 (RawMaterialType.unit, e.g. "箱")
     * @return 换算结果 — CONVERTED(可比) / ABACA_SKIP(跳过校验) / UNCONVERTIBLE(必须 fail-loud)
     */
    public ConversionResult toComparableQuantity(String materialTypeId, BigDecimal qty,
                                                 String fromUnit, String materialStockUnit) {
        if (qty == null) {
            return ConversionResult.unconvertible("数量为空");
        }
        String from = normalize(fromUnit);
        String stock = normalize(materialStockUnit);

        // 1. 同单位 (或 stock 单位缺失但与 from 一致) → 直接透传, 无需换算.
        if (from != null && from.equals(stock)) {
            return ConversionResult.converted(qty, materialStockUnit);
        }

        // 2. 同维度 weight/volume (g↔kg, ml↔L) → 用 UnitConversionService.
        //    传归一后的 token (from/stock), 使中文重量别名 (克→g, 千克/公斤→kg)
        //    也能命中 UnitConversionService 的 g↔kg 系数换算 (T156).
        BigDecimal dimConverted = unitConversionService.convert(qty, from, stock);
        if (dimConverted != null) {
            return ConversionResult.converted(dimConverted, materialStockUnit);
        }

        // 抄码料检测 (在尝试装箱规格之前) — 抄码料每箱重量不一, 没有确定系数.
        RawMaterialType material = loadMaterial(materialTypeId);
        if (material != null && Boolean.TRUE.equals(material.getIsAbacaPackaging())) {
            log.info("[UOM] 抄码料 {} ({}↔{}) 无确定箱重, 跳过规格校验",
                    materialTypeId, fromUnit, materialStockUnit);
            return ConversionResult.abacaSkip(material.getName());
        }

        // 3. 跨 箱(level)↔base(kg/g): 查 MaterialPackagingHierarchy.
        MaterialPackagingHierarchy hierarchy =
                materialTypeId == null ? null
                        : packagingRepository.findByMaterialTypeId(materialTypeId).orElse(null);

        if (hierarchy != null) {
            ConversionResult viaHierarchy = convertViaHierarchy(qty, from, stock, hierarchy,
                    materialName(material, materialTypeId));
            if (viaHierarchy != null) {
                return viaHierarchy;
            }
        }

        // 4. 无法换算 — 绝不静默 default. Caller 必须 fail-loud.
        log.warn("[UOM] 无法换算 material={} {}{} → {} (非抄码, 无装箱规格 / 非同维度)",
                materialTypeId, qty, fromUnit, materialStockUnit);
        return ConversionResult.unconvertible(
                String.format("原料「%s」未配置装箱规格(%s↔%s)",
                        materialName(material, materialTypeId),
                        materialStockUnit != null ? materialStockUnit : "?",
                        fromUnit != null ? fromUnit : "?"));
    }

    /**
     * 经装箱规格换算 level1PerLevel2 (例 10 kg/箱) 把 fromUnit 折算到 stock 单位 (箱).
     *
     * <p>level1Unit = 基础单位 (例 kg), level2Unit = 装箱单位 (例 箱),
     * level1PerLevel2 = 例 10 (10 kg = 1 箱).
     *
     * <p>支持两个方向:
     * <ul>
     *   <li>from=base/g/kg, stock=level2(箱): qty(g)→base(kg via dim)→÷10→箱</li>
     *   <li>from=level2(箱), stock=base(kg): qty(箱)×10→base(kg)→stock(via dim)</li>
     * </ul>
     *
     * @return 换算结果; null 表示此 hierarchy 无法覆盖该 from↔stock (caller 继续走 UNCONVERTIBLE)
     */
    private ConversionResult convertViaHierarchy(BigDecimal qty, String from, String stock,
                                                 MaterialPackagingHierarchy h, String matName) {
        String level1 = normalize(h.getLevel1Unit());   // 基础单位 e.g. kg
        String level2 = normalize(h.getLevel2Unit());   // 装箱单位 e.g. 箱
        BigDecimal perBox = h.getLevel1PerLevel2();      // 例 10 (10 kg/箱)

        if (level1 == null || level2 == null || perBox == null
                || perBox.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[UOM] 装箱规格不完整 material={} level1={} level2={} perBox={}",
                    matName, h.getLevel1Unit(), h.getLevel2Unit(), perBox);
            return null;
        }

        // 方向 A: from=(g/kg/level1) → stock=level2(箱)
        if (stock != null && stock.equals(level2)) {
            BigDecimal inBase = toBase(qty, from, level1);
            if (inBase == null) return null;
            BigDecimal inBoxes = inBase.divide(perBox, 6, RoundingMode.HALF_UP);
            return ConversionResult.converted(inBoxes, h.getLevel2Unit());
        }

        // 方向 B: from=level2(箱) → stock=(g/kg/level1)
        if (from != null && from.equals(level2)) {
            BigDecimal inBase = qty.multiply(perBox);    // 箱 → level1 (kg)
            BigDecimal inStock = fromBase(inBase, level1, stock);
            if (inStock == null) return null;
            return ConversionResult.converted(inStock, h.getLevel2Unit() == null ? stock : (stock != null ? stock : level1));
        }

        return null;
    }

    /** 把 (qty, fromUnit) 折到 base(level1) 单位. fromUnit==level1 透传, 否则走维度换算 (g↔kg). */
    private BigDecimal toBase(BigDecimal qty, String fromUnit, String level1) {
        if (fromUnit == null) return null;
        if (fromUnit.equals(level1)) return qty;
        return unitConversionService.convert(qty, fromUnit, level1);  // e.g. g → kg
    }

    /** 把 base(level1) 量折到 stock 单位. stock==level1 透传, 否则走维度换算. */
    private BigDecimal fromBase(BigDecimal baseQty, String level1, String stock) {
        if (stock == null) return baseQty;          // stock 单位缺失, 用 base 量 (caller 已知 stock)
        if (stock.equals(level1)) return baseQty;
        return unitConversionService.convert(baseQty, level1, stock);  // e.g. kg → g
    }

    private RawMaterialType loadMaterial(String materialTypeId) {
        if (materialTypeId == null) return null;
        try {
            return rawMaterialTypeRepository.findById(materialTypeId).orElse(null);
        } catch (Exception e) {
            log.debug("[UOM] 加载物料失败 {}: {}", materialTypeId, e.getMessage());
            return null;
        }
    }

    private static String materialName(RawMaterialType material, String fallbackId) {
        if (material != null && material.getName() != null && !material.getName().isBlank()) {
            return material.getName();
        }
        return fallbackId != null ? fallbackId : "未知原料";
    }

    /**
     * 单位别名 → 规范 token 映射 (T156, 2026-06-08).
     *
     * <p><b>背景 (六扇门 F006 prod 事故):</b> 气调盒 BOM 单位 = {@code pcs}, 库存单位
     * (RawMaterialType.unit) = {@code 个}. 二者本是<b>同一计数单位</b> (件/个/piece),
     * 但旧 {@code normalize()} 只 trim+lowercase → {@code pcs} ≠ {@code 个} → 走到
     * UNCONVERTIBLE → 转批次/开工时误报 409 "原料单位无法换算" 阻断生产.
     *
     * <p>本表把已知等同单位归一到<b>单一规范 token</b>, 使其 {@link #normalize}
     * 后 equals 比较相等 → 同单位 1:1 透传, 不再误判.
     *
     * <p><b>归一规则 (只归一真正等同的单位, 不过度别名):</b>
     * <ul>
     *   <li><b>计数 → "个"</b>: pcs / pc / pces / pieces / piece / 个 / 件 / 只
     *       (本域 个/件/只 均为离散计数单位; 气调盒计数)</li>
     *   <li><b>重量 g → "g"</b>: g / 克 ; <b>kg → "kg"</b>: kg / 千克 / 公斤
     *       (归到 {@link UnitConversionService} 认得的 latin token, 保 g↔kg 通路)</li>
     *   <li><b>斤 → "斤"</b>: 斤 (本身已是规范, 仅占位; 无装箱规格时仍 UNCONVERTIBLE)</li>
     * </ul>
     *
     * <p>查表前先 trim + lowercase (latin part), 故 "PCS" / "Pcs" / " pcs " 均命中.
     * 表中 key 全为已 lowercase 形式 (中文不受 lowercase 影响).
     *
     * <p><b>不在表中的单位</b> (e.g. 箱 / ml / l / 桶 / 袋) 原样返回 (lowercased),
     * 走原有 维度换算 / 装箱规格 / UNCONVERTIBLE 路径 — 行为不变.
     */
    private static final Map<String, String> UNIT_ALIASES = Map.ofEntries(
            // 计数 / piece → 个
            Map.entry("pcs", "个"),
            Map.entry("pc", "个"),
            Map.entry("pce", "个"),
            Map.entry("pces", "个"),
            Map.entry("piece", "个"),
            Map.entry("pieces", "个"),
            Map.entry("个", "个"),
            Map.entry("件", "个"),
            Map.entry("只", "个"),
            // 重量 → latin token (保 UnitConversionService g↔kg 通路)
            Map.entry("g", "g"),
            Map.entry("克", "g"),
            Map.entry("kg", "kg"),
            Map.entry("千克", "kg"),
            Map.entry("公斤", "kg")
    );

    /**
     * 归一单位 token: trim + lowercase, 再查别名表把等同单位折到单一规范 token.
     *
     * <p>表外单位返回 lowercased 原值 (不改变旧行为). 表内别名返回规范 token,
     * 使 {@code pcs}/{@code 个}/{@code 件} 等等同单位 equals 比较相等.
     */
    static String normalize(String unit) {
        if (unit == null) {
            return null;
        }
        String trimmed = unit.trim().toLowerCase();
        return UNIT_ALIASES.getOrDefault(trimmed, trimmed);
    }

    // ================================================================

    /** 换算结果状态机. */
    public enum Status {
        /** 成功换算, quantity 已与 stock 单位可比. */
        CONVERTED,
        /** 抄码料 — 每箱重量不一, 跳过按规格的库存校验 (不阻断). */
        ABACA_SKIP,
        /** 无法换算 — caller 必须 fail-loud, 绝不静默 default. */
        UNCONVERTIBLE
    }

    /**
     * 换算结果. 不可变.
     */
    public static final class ConversionResult {
        private final Status status;
        private final BigDecimal quantity;
        private final String unit;
        private final String reason;
        private final String materialName;

        private ConversionResult(Status status, BigDecimal quantity, String unit,
                                 String reason, String materialName) {
            this.status = status;
            this.quantity = quantity;
            this.unit = unit;
            this.reason = reason;
            this.materialName = materialName;
        }

        public static ConversionResult converted(BigDecimal quantity, String unit) {
            return new ConversionResult(Status.CONVERTED, quantity, unit, null, null);
        }

        public static ConversionResult abacaSkip(String materialName) {
            return new ConversionResult(Status.ABACA_SKIP, null, null, "抄码料无确定箱重", materialName);
        }

        public static ConversionResult unconvertible(String reason) {
            return new ConversionResult(Status.UNCONVERTIBLE, null, null, reason, null);
        }

        public Status getStatus() { return status; }

        /** 换算后的量 (仅 CONVERTED 时非 null). */
        public BigDecimal getQuantity() { return quantity; }

        /** 换算后的单位 (仅 CONVERTED 时非 null). */
        public String getUnit() { return unit; }

        public String getReason() { return reason; }
        public String getMaterialName() { return materialName; }

        public boolean isConverted() { return status == Status.CONVERTED; }
        public boolean isAbacaSkip() { return status == Status.ABACA_SKIP; }
        public boolean isUnconvertible() { return status == Status.UNCONVERTIBLE; }
    }
}
