package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProductionStockShortageDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺料 409 文案里的单位必须是<b>客户读得懂的中文</b>。
 *
 * <p>2026-08-01 prod 走查实录: 逐工序报工缺包材时后端原样返回单位码 ——</p>
 * <pre>需要 7box，可用 0box，缺少 7box，请联系仓管补料</pre>
 * <p>这正是 V20261029_32 开头点名要消灭的那种文案(「用户从来不认识 pcs」),
 * 但那条 migration 改的是数据、改不到文案。</p>
 */
class ProductionStockShortageMessageUnitTest {

    private static ProductionStockShortageDTO.Item item(String name, String unit, String source) {
        return new ProductionStockShortageDTO.Item(
                "RMT_1785388331640", name, source,
                new BigDecimal("7"), BigDecimal.ZERO, new BigDecimal("7"), unit);
    }

    @Test
    @DisplayName("回归 prod 实录: 包材缺料文案不得出现 box, 必须显示「盒」")
    void rendersPackagingUnitInChinese() {
        ProductionStockShortageDTO dto = new ProductionStockShortageDTO(
                new BigDecimal("7"), BigDecimal.ZERO, new BigDecimal("7"), "box",
                List.of(item("SOP-20260730-01-黄油鸡-成品盒", "box", "PACKAGING")));

        String message = new ProductionStockShortageException(dto).getMessage();

        assertThat(message)
                .contains("需要 7盒", "可用 0盒", "缺少 7盒")
                .contains("SOP-20260730-01-黄油鸡-成品盒（包材）")
                .doesNotContain("box");
    }

    @Test
    @DisplayName("汇总段与明细段两处都要翻 —— 只翻一处会留半截英文")
    void translatesBothSummaryAndDetail() {
        ProductionStockShortageDTO dto = new ProductionStockShortageDTO(
                new BigDecimal("7"), BigDecimal.ZERO, new BigDecimal("7"), "bag",
                List.of(item("某原料", "bag", null)));

        String message = new ProductionStockShortageException(dto).getMessage();

        // 汇总段
        assertThat(message).contains("需要 7袋，可用 0袋，缺少 7袋");
        // 明细段
        assertThat(message).contains("某原料：需要 7袋，可用 0袋，缺少 7袋");
        assertThat(message).doesNotContain("bag");
    }

    @Test
    @DisplayName("⛔ kg 不翻 —— 科学计量符号保持原样, 别把「需要 7kg」改成「需要 7公斤」")
    void keepsKilogramSymbol() {
        ProductionStockShortageDTO dto = new ProductionStockShortageDTO(
                new BigDecimal("7"), BigDecimal.ZERO, new BigDecimal("7"), "kg",
                List.of(item("冻猪蹄", "kg", null)));

        String message = new ProductionStockShortageException(dto).getMessage();

        assertThat(message).contains("需要 7kg", "可用 0kg", "缺少 7kg");
        assertThat(message).doesNotContain("公斤");
    }

    @Test
    @DisplayName("多单位混合时 mixed 是哨兵值, 不能被当单位翻译")
    void keepsMixedSentinel() {
        ProductionStockShortageDTO dto = new ProductionStockShortageDTO(
                new BigDecimal("7"), BigDecimal.ZERO, new BigDecimal("7"), "mixed",
                List.of(item("成品盒", "box", "PACKAGING"), item("冻猪蹄", "kg", null)));

        String message = new ProductionStockShortageException(dto).getMessage();

        assertThat(message).contains("短缺明细");
        assertThat(message).contains("成品盒（包材）：需要 7盒");
        assertThat(message).contains("冻猪蹄：需要 7kg");
        assertThat(message).doesNotContain("box");
    }
}
