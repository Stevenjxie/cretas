-- 渠道侧成本（外卖平台抽佣 / 团购券核销费）落库。
--
-- 🔴 补的是一个**建模缺口**，不是加个字段那么简单：
--    2026-08-09 实测 MOCK_REST 三个渠道的毛利率是 67.66% / 67.68% / 67.81%
--    —— 几乎完全相同。原因是成本只跟菜品配方走、与渠道无关，
--    订单侧只有「毛额 - 折扣 = 净额」，**没有任何渠道成本**。
--
--    于是「渠道毛利倒挂」这条发现规则永远产出 0 条 —— 而那恰恰是真实餐饮里
--    最要紧的问题之一：**外卖做得越大越不赚钱**。缺了抽佣这一维，
--    系统在结构上就没法发现它。
--
-- ⛔ 单独一列，不并进 discount_amount：
--    折扣是**让给顾客**的，抽佣是**付给平台**的。两者的处置动作完全不同 ——
--    前者调价格/套餐策略，后者谈费率或把客人引到私域。混在一起，
--    老板看到「让利 4.5%」时无法区分哪部分是自己主动让的、哪部分是被抽走的。
--
-- ⚠️ 默认 0 且可空语义明确：0 表示「这个渠道没有抽佣」（堂食就是 0），
--    **不是**「不知道」。历史数据回填前也是 0 —— 那些订单确实没记过这笔钱，
--    写 0 是如实，不是编。

ALTER TABLE fact_pos_transaction
    ADD COLUMN IF NOT EXISTS platform_fee_amount NUMERIC(18, 2) NOT NULL DEFAULT 0;

ALTER TABLE agg_daily
    ADD COLUMN IF NOT EXISTS platform_fee_amount NUMERIC(18, 2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN fact_pos_transaction.platform_fee_amount IS
    '渠道侧成本：外卖平台抽佣 / 团购券核销费，按实付净额抽。堂食为 0。'
    '⛔ 与 discount_amount 分开——折扣让给顾客，抽佣付给平台，处置动作不同。';

COMMENT ON COLUMN agg_daily.platform_fee_amount IS
    '当日渠道侧成本合计，由 fact_pos_transaction.platform_fee_amount 汇总。';
