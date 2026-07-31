package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报工副产物化成生产仓里的原料批次。
 *
 * <p>🔴 两条关键约束: 去向是<b>生产仓</b>(它是生产出来的, 不是采购入库的),
 * 且报工时<b>不写单价</b>(单价在盘点确认 —— 报工时没人知道这批副产值多少)。</p>
 */
class ProcessSheetByproductMaterializationTest {

    @Test
    @DisplayName("副产落生产仓, 带来源报工 ID, 且不写单价")
    void byproductBatchLandsInWorkshopWithoutUnitPrice() {
        MaterialBatch batch = ProcessSheetServiceImpl.buildByproductBatch(
                "F006", "RAW-0031", new BigDecimal("2.8"), "kg", "WH-WKS-1", 4001L);

        assertThat(batch.getFactoryId()).isEqualTo("F006");
        assertThat(batch.getMaterialTypeId()).isEqualTo("RAW-0031");
        assertThat(batch.getWarehouseId())
                .as("去向是生产仓, 不是原料仓")
                .isEqualTo("WH-WKS-1");
        assertThat(batch.getSourceDocType()).isEqualTo("BYPRODUCT");
        assertThat(batch.getReceiptQuantity()).isEqualByComparingTo("2.8");
        assertThat(batch.getQuantityUnit()).isEqualTo("kg");
        assertThat(batch.getByproductSourceReportId()).isEqualTo(4001L);

        assertThat(batch.getByproductUnitPrice())
                .as("单价在盘点时才确认, 报工不写 —— 此时写任何值都是臆造")
                .isNull();
        assertThat(batch.getByproductPriceConfirmedAt()).isNull();
        assertThat(batch.getByproductPriceConfirmedBy()).isNull();
    }

    @Test
    @DisplayName("来源标记与 WIP 的 PRODUCTION_BATCH 并列, 不复用它")
    void byproductSourceDocTypeIsItsOwnValue() {
        assertThat(ProcessSheetServiceImpl.SOURCE_DOC_TYPE_BYPRODUCT)
                .isEqualTo("BYPRODUCT")
                .isNotEqualTo("PRODUCTION_BATCH");
    }
}
