package com.cretas.aims.service.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 副产抵扣额的<b>唯一</b>计算入口。
 *
 * <p>🔴 <b>为什么必须只有一处</b>: 本仓 2026-07-31 一天内连修五处「同一件事多套实现」——
 * 单位别名表在五个地方各抄一份 (覆盖 2~21 组不等), 造成客户报工被误拦、投料折算静默偏大 25%。
 * 副产抵扣只在这里算; 前端只负责展示后端返回的值, <b>不得自行 quantity × unitPrice</b>。</p>
 *
 * <p>🔴 <b>禁降级: 单价未确认返回 null, 不是 0</b>。0 会被读成「这批副产不值钱」,
 * 而且看起来像已经算完了; null 才如实表达「还没人确认过」。单价确认为 0 是另一回事 ——
 * 那是个真实的确认结果, 两者必须分得开 (与本仓既有的「未归集」同一套诚实语义)。</p>
 *
 * <p>抵扣基数用<b>盘点重量</b>而非报工重量 —— 盘点就是以实物为准 (Steve 2026-07-31)。</p>
 */
public final class ByproductCreditService {

    /** 金额保留两位, 与本仓其它金额口径一致。 */
    private static final int MONEY_SCALE = 2;

    private ByproductCreditService() {
    }

    /**
     * 副产抵扣额 = 盘点重量 × 确认单价。
     *
     * @param stocktakeQuantity 盘点实际重量; null 表示还没盘
     * @param unitPrice         盘点时确认的单价; null 表示还没确认
     * @return 抵扣额; <b>任一入参为 null 即返回 null</b>(不臆造 0)
     */
    public static BigDecimal creditOf(BigDecimal stocktakeQuantity, BigDecimal unitPrice) {
        if (stocktakeQuantity == null || unitPrice == null) {
            return null;
        }
        return stocktakeQuantity.multiply(unitPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
