package com.cretas.aims.repository;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MES↔ERP Fix #5 (2026-07-04) — JPA slice: 仓库侧 FEFO 拣批必须排除 WIP 半成品批次.
 *
 * <p>背景: {@code findAvailableBatchesFEFOByWarehouse} 原先只过滤 (status=AVAILABLE, qty>0),
 * 未排除 {@code sourceDocType='PRODUCTION_BATCH'} 的 WIP 批次。这些批次 (materialTypeId=原料血缘,
 * warehouseId=WH-WKS) 与真实原料库存不可区分 → 仓库侧 FEFO (调拨/领料回退/报损) 会把在制半成品
 * 当原料吃掉 → labor 双计 / 下游小结超扣负库存。本 fix 对齐已修的 {@code findAvailableBatchesFEFO}
 * / {@code findAllAvailableInWarehouse}, 加 PRODUCTION_BATCH 排除。
 *
 * <p>生产侧真正需要投料 WIP 的路径 (报工混锅 SFI) 走独立的
 * {@code findByFactoryIdAndSourceDocTypeAndSourceDocId} (按 sourceDocId 精确解析), 不受影响 —
 * 本测试同时验证该 resolve-by-id 查询仍能取到 WIP (production picker 不被误伤)。
 *
 * <p>H2 PG-compat (application-test.properties), 模式同 WastageReportRepositoryTest。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("MaterialBatchRepository — 仓库侧 FEFO 排除 WIP 半成品 (Fix #5)")
class MaterialBatchFefoWipExclusionTest {

    private static final String F1 = "F-MBW-1";
    private static final String MT1 = "MT-MBW-1";
    private static final String WH_WKS = "WH-WKS";

    @Autowired
    private MaterialBatchRepository repo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void relaxFk() {
        // material_batches.created_by → users(id) FK: 只 seed 批次不建全依赖树, 关 H2 FK 校验 (连接级)
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
    }

    private MaterialBatch batch(String id, String sourceDocType, String sourceDocId, BigDecimal receipt) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(F1);
        b.setBatchNumber("BN-" + id);
        b.setMaterialTypeId(MT1);
        b.setReceiptDate(LocalDate.now());
        b.setWarehouseId(WH_WKS);
        b.setReceiptQuantity(receipt);
        b.setQuantityUnit("kg");
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        b.setCreatedBy(1L);
        b.setSourceDocType(sourceDocType);
        b.setSourceDocId(sourceDocId);
        return b;
    }

    @Test
    @DisplayName("findAvailableBatchesFEFOByWarehouse — 排除 PRODUCTION_BATCH WIP, 只返真实原料")
    void byWarehouse_excludesWip() {
        repo.saveAndFlush(batch("RAW1", null, null, new BigDecimal("50")));
        repo.saveAndFlush(batch("PURCHASE1", "PURCHASE_RECEIVE", "PO-1", new BigDecimal("30")));
        repo.saveAndFlush(batch("WIP1", "PRODUCTION_BATCH", "PB-1", new BigDecimal("40")));

        List<MaterialBatch> result = repo.findAvailableBatchesFEFOByWarehouse(F1, MT1, WH_WKS);

        assertThat(result).extracting(MaterialBatch::getId)
                .containsExactlyInAnyOrder("RAW1", "PURCHASE1")
                .doesNotContain("WIP1");
    }

    @Test
    @DisplayName("findAvailableBatchesFEFO (无仓过滤) — 同样排除 WIP (回归锚点, SP-D Fix 1b)")
    void global_excludesWip() {
        repo.saveAndFlush(batch("RAW2", null, null, new BigDecimal("50")));
        repo.saveAndFlush(batch("WIP2", "PRODUCTION_BATCH", "PB-2", new BigDecimal("40")));

        List<MaterialBatch> result = repo.findAvailableBatchesFEFO(F1, MT1);

        assertThat(result).extracting(MaterialBatch::getId)
                .contains("RAW2")
                .doesNotContain("WIP2");
    }

    @Test
    @DisplayName("生产侧 WIP picker 不被误伤: findByFactoryIdAndSourceDocTypeAndSourceDocId 仍能取到 WIP")
    void productionWipPicker_stillResolvesWip() {
        repo.saveAndFlush(batch("WIP3", "PRODUCTION_BATCH", "PB-3", new BigDecimal("40")));

        var found = repo.findByFactoryIdAndSourceDocTypeAndSourceDocId(F1, "PRODUCTION_BATCH", "PB-3");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("WIP3");
    }
}
