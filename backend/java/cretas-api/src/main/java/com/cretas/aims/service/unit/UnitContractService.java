package com.cretas.aims.service.unit;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UnitContractService {

    List<CanonicalUnit> catalog(String factoryId);

    List<CanonicalUnit> catalog(String factoryId, UnitUsageScope usageScope);

    UnitNormalizationResult normalize(String factoryId, String rawUnit);

    Optional<CanonicalUnit> describe(String factoryId, String rawUnit);

    /**
     * 单位<b>落库时该写什么字面值</b> —— 全系统唯一口径, 任何写入路径都必须走这里。
     *
     * <p>Steve 2026-08-03 拍板「存中文」, 并在看过存量后定为<b>分两步</b>: 先让自定义单位存中文,
     * 内置单位的 2400 行存量保持英文码不动。存量实测: {@code raw_material_types.unit} 766 行
     * <b>100% 英文码</b>、{@code product_types.unit} 771 行只有 1 行中文、
     * {@code material_batches.quantity_unit} 885 行里中文仅 11 行 —— 全量中文化要动 2400 行,
     * 而它要治的中英混写只有 11 行, 不成比例。
     *
     * <p>四条规则, 从上往下第一条命中者生效:
     * <ol>
     *   <li><b>权威表认不出</b> → 原样 trim 返回。未登记的自由文本(如「半只」在登记前)保持旧行为,
     *       不因未登记就判非法。</li>
     *   <li><b>别名组含多个中文写法</b> → <b>保用户字面</b>。权威表里
     *       {@code alias("pcs","pcs","件","个","只")} 把三个中文写法编到同一个码上; 归一到 {@code pcs}
     *       (或归一到 displayName「件」) 都等于<b>替工厂断定「一只 = 一件」</b>。#1976 已就此定过案,
     *       LIUSHANMEN 2026-07-30 的事故正是「只」被写成 {@code pcs} 后报工按字面比较看不见整批 501 只。
     *       <b>换成存中文并不能解决它</b> —— 只是把英文塌陷换成中文塌陷。</li>
     *   <li><b>工厂自定义单位</b> (码不在内置表里) → {@code displayName()} 中文名。
     *       自定义单位的码是按中文名自动生成的拼音, 存 {@code banzhi} 没有可读性也不可追溯。</li>
     *   <li><b>内置单位</b> → {@code code()} 英文码, 与 2400 行存量一致。</li>
     * </ol>
     *
     * <p>为什么规则 3 是安全的: 调拨侧 {@code canonicalTransferUnit} 与报工侧
     * {@code canonicalNativeUnit} 对 COUNT/PACKAGE <b>本来就回落字面比较</b>(只归一 MASS/VOLUME),
     * 所以档案存「半只」、批次也存「半只」时两侧字面相等, 无需改动那两个函数。
     *
     * @return 该落库的字面值; 入参为空时返回空串
     */
    String storageUnit(String factoryId, String rawUnit);

    boolean areEquivalent(String factoryId, String leftUnit, String rightUnit);

    boolean supportsUsage(String factoryId, String rawUnit, UnitUsageScope usageScope);

    UnitConversionResult convert(UnitConversionContext context);

    UnitConversionResult convert(BigDecimal quantity, UnitConversionContext context);

    List<String> validateConversionGraph(
            String factoryId,
            String productTypeId,
            LocalDateTime at);
}
