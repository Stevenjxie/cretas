package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.service.inventory.impl.TransferServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸 —— 包材调拨的<b>扣减侧和建仓侧必须用同一个判断</b>。
 *
 * <h2>🔴 线上事故 (2026-08-18, F006 TRF-20260818-0897)</h2>
 *
 * 用户报「调拨单推进到确认调拨入库报错」「刷新、新建都不行」。单据：成品盒 1100 盒，原料仓 → 生产仓。
 *
 * <p>日志时序（trace 6C5EE082）：
 * <pre>
 * 11:25:09.336  扣减原料批次: deduct=1000, remaining=100
 * 11:25:09.340  扣减原料批次: deduct=100,  remaining=0
 * 11:25:09.346  调拨确认: transferId=e7e1b944…, 库存已更新      ← 业务逻辑跑完了
 * 11:25:09.373  [6C5EE082] 事务回滚: Could not commit JPA transaction
 * </pre>
 *
 * <h3>根因：同一个判断写了两遍, 漂了</h3>
 * <pre>
 * 扣减侧 deductSourceInventory : RAW_MATERIAL || PACKAGING_MATERIAL → 物料批次 ✅
 * 建仓侧 createTargetInventory : 只有 RAW_MATERIAL                  → 包材掉进成品批次 ❌
 * </pre>
 * 包材是<b>物料</b>（只有 materialTypeId），给它造 {@code FinishedGoodsBatch} 时
 * {@code productTypeId} 是空的，而那个字段有 {@code @NotBlank}
 * ⇒ <b>flush 时 Bean Validation 失败</b>。
 *
 * <p>⚠️ 为什么日志里什么都看不到：Bean Validation 失败<b>不产生 SQL 错误</b>，
 * 只留下一句 {@code Could not commit JPA transaction}，用户看到通用报错 + 追踪码。
 * ⚠️ 为什么「刷新、新建都不行」：这个失败是<b>确定性</b>的，每次都撞同一条校验。
 *
 * <h2>这道闸钉两层</h2>
 * <ol>
 *   <li><b>行为</b>：{@code storedAsMaterialBatch} 对包材/原料为真、对成品为假</li>
 *   <li><b>结构</b>：全文件只剩<b>一处</b>写 {@code TransferItemType.RAW_MATERIAL}（helper 里那处），
 *       防止再长出第三份类型判断（形态 D）</li>
 * </ol>
 */
class TransferPackagingMaterialContractTest {

    private static final Path SRC = Path.of(
            "src/main/java/com/cretas/aims/service/inventory/impl/TransferServiceImpl.java");

    private static boolean storedAsMaterialBatch(TransferItemType type) {
        InternalTransferItem item = new InternalTransferItem();
        item.setItemType(type);
        return Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(TransferServiceImpl.class, "storedAsMaterialBatch", item));
    }

    @Test
    @DisplayName("阳性对照: 原料走物料批次 (否则下面的断言可能只是因为它对谁都返回真)")
    void rawMaterialGoesToMaterialBatch() {
        assertTrue(storedAsMaterialBatch(TransferItemType.RAW_MATERIAL));
    }

    @Test
    @DisplayName("🔴 包材也走物料批次 —— 掉进成品批次就是那次线上事故")
    void packagingMaterialGoesToMaterialBatch() {
        assertTrue(storedAsMaterialBatch(TransferItemType.PACKAGING_MATERIAL),
                "包材被当成成品建批次 ⇒ FinishedGoodsBatch.productTypeId(@NotBlank) 为空 ⇒ "
                        + "确认入库必失败, 且刷新/新建都一样");
    }

    @Test
    @DisplayName("🔴 阴性对照: 成品不许走物料批次 —— 否则这个判断就退化成恒真")
    void finishedGoodsDoesNotGoToMaterialBatch() {
        assertFalse(storedAsMaterialBatch(TransferItemType.FINISHED_GOODS));
    }

    @Test
    @DisplayName("null 明细不炸")
    void nullItemIsFalse() {
        assertFalse(Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(
                        TransferServiceImpl.class, "storedAsMaterialBatch", (InternalTransferItem) null)));
    }

    @Test
    @DisplayName("🔴 结构闸: 类型判断只许有一处 —— 两份必漂, 这次就是漂出来的")
    void onlyOnePlaceDecidesTheItemType() throws Exception {
        String raw = Files.readString(SRC);
        // 先剥注释: 否则会数到讲这件事的那几段说明 (本仓形态 A⁗)
        String src = raw.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
        int rawMentions = src.split("TransferItemType\\.RAW_MATERIAL", -1).length - 1;
        assertEquals(1, rawMentions,
                "TransferItemType.RAW_MATERIAL 出现 " + rawMentions + " 处。"
                        + "散落的类型判断迟早会像扣减侧/建仓侧那样漂开 —— 请改用 storedAsMaterialBatch(item)。");
        int helperAt = src.indexOf("private static boolean storedAsMaterialBatch(");
        assertTrue(helperAt > 0, "找不到 storedAsMaterialBatch, 这道闸在读空气");
        int calls = src.split("storedAsMaterialBatch\\(", -1).length - 1;
        assertTrue(calls >= 5,
                "storedAsMaterialBatch 只出现 " + calls + " 次 (应 = 1 定义 + 5 调用) —— 有调用点被改回去了");
    }
}
