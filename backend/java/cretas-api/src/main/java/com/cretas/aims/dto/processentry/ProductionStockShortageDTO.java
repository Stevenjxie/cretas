package com.cretas.aims.dto.processentry;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** 正式报工提交时生产库不足的结构化明细。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductionStockShortageDTO {

    private BigDecimal required;
    private BigDecimal available;
    private BigDecimal shortage;
    private String unit;
    private List<Item> items;

    /** 短缺成因 —— 「工厂里没有」和「工厂里有但没领到生产仓」要给完全不同的动作。 */
    public enum Cause {
        /**
         * 工厂里有货, 只是**没领到生产仓**。下一步动作是「去领料」, 不是采购。
         * 🔴 2026-08-18 实测: 冻猪蹄工厂在手 30kg, 生产仓只有 5kg, 报工被拦 ——
         *    而当时的提示是「请联系仓管补料」, 与「工厂真没货」说的是同一句话。
         */
        NOT_REQUISITIONED,
        /** 工厂在手也为 0 —— 这才是真的缺货, 下一步是采购/入库。 */
        TRULY_OUT_OF_STOCK,
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String materialTypeId;
        private String materialName;
        private String sourceType;
        private BigDecimal required;
        /** 生产仓里能投的量。 */
        private BigDecimal available;
        private BigDecimal shortage;
        private String unit;
        /**
         * 全厂在手量 (不限仓库, 同一套单位匹配规则)。
         * 它减去 {@link #available} 就是「压在别的仓、还没领到生产仓」的量。
         */
        private BigDecimal factoryOnHand;
        /** 由 factoryOnHand 推出来, 不是手填 —— 见 {@link Cause}。 */
        private Cause cause;

        public Item(
                String materialTypeId,
                BigDecimal required,
                BigDecimal available,
                BigDecimal shortage,
                String unit) {
            this(materialTypeId, null, null, required, available, shortage, unit, null, null);
        }

        public Item(
                String materialTypeId,
                String materialName,
                String sourceType,
                BigDecimal required,
                BigDecimal available,
                BigDecimal shortage,
                String unit) {
            this(materialTypeId, materialName, sourceType, required, available, shortage, unit, null, null);
        }
    }
}
