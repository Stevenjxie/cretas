package com.cretas.aims.service.factory;

import com.cretas.aims.dto.factory.ByproductCreditDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryStocktakeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeItemRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.service.factory.impl.FactoryStocktakeServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 盘点侧「副产价值确认」—— 列表与确认单价。
 *
 * <p>🔴 <b>抵扣基数是盘点重量而非报工重量</b> (Steve 2026-07-31: 盘点以实物为准)。
 * 两个重量都返回是为了让差异可见, 不是让前端去挑用哪个。</p>
 *
 * <p>🔴 <b>null 与 0 分得开</b>: 未确认 → credit 为 null(前端显示「未抵扣」);
 * 确认为 0 → credit 为 0.00。把 null 当 0 会让「漏确认」看起来像「已确认为 0」。</p>
 *
 * <p>🔴 <b>写路径 fail-closed 三连</b>: 批次要属于本工厂、要是副产批次、要在这张盘点单里。
 * 这个写端点产出的数直接从主产品成本里扣, 宁可拒绝也不能放错。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ByproductCreditStocktakeTest {

    private static final String FACTORY = "F006";
    private static final String STOCKTAKE = "ST-1";
    private static final String BATCH = "BATCH-BP-1";

    @Mock private FactoryStocktakeRepository stocktakeRepo;
    @Mock private FactoryStocktakeItemRepository stocktakeItemRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepo;

    @InjectMocks private FactoryStocktakeServiceImpl service;

    // ---------- 列表 ----------

    @Test
    void listReturnsOnlyByproductBatchesAndCreditsOnStocktakeWeight() {
        MaterialBatch byproduct = byproductBatch(new BigDecimal("36"));
        byproduct.setByproductUnitPrice(new BigDecimal("8"));
        byproduct.setByproductPriceConfirmedAt(LocalDateTime.now());
        MaterialBatch ordinary = ordinaryBatch();
        stubStocktake();
        when(stocktakeItemRepo.findByStocktakeId(STOCKTAKE)).thenReturn(List.of(
                item(BATCH, new BigDecimal("30")),          // 盘点只盘到 30
                item("BATCH-ORD", new BigDecimal("99"))));
        when(materialBatchRepo.findByIdAndFactoryId(BATCH, FACTORY)).thenReturn(Optional.of(byproduct));
        when(materialBatchRepo.findByIdAndFactoryId("BATCH-ORD", FACTORY)).thenReturn(Optional.of(ordinary));
        when(rawMaterialTypeRepo.findById("MT-1")).thenReturn(Optional.of(material("肥油")));

        List<ByproductCreditDTO> rows = service.listByproductCredits(STOCKTAKE, FACTORY);

        assertThat(rows).hasSize(1); // 普通批次不混进来
        ByproductCreditDTO row = rows.get(0);
        assertThat(row.getMaterialName()).isEqualTo("肥油");
        assertThat(row.getReportedQuantity()).isEqualByComparingTo("36");
        assertThat(row.getStocktakeQuantity()).isEqualByComparingTo("30");
        assertThat(row.getDifferenceQuantity()).isEqualByComparingTo("-6");
        // 🔴 30 × 8 = 240, 不是 36 × 8 = 288 —— 基数必须是盘点重量
        assertThat(row.getCredit()).isEqualByComparingTo("240.00");
        assertThat(row.getCreditStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void unconfirmedPriceYieldsNullCreditNotZero() {
        MaterialBatch byproduct = byproductBatch(new BigDecimal("36")); // 无单价无确认时间
        stubStocktake();
        when(stocktakeItemRepo.findByStocktakeId(STOCKTAKE))
                .thenReturn(List.of(item(BATCH, new BigDecimal("30"))));
        when(materialBatchRepo.findByIdAndFactoryId(BATCH, FACTORY)).thenReturn(Optional.of(byproduct));
        when(rawMaterialTypeRepo.findById("MT-1")).thenReturn(Optional.of(material("肥油")));

        ByproductCreditDTO row = service.listByproductCredits(STOCKTAKE, FACTORY).get(0);

        assertThat(row.getCredit()).as("未确认不臆造 0").isNull();
        assertThat(row.getCreditStatus()).isEqualTo("PENDING");
    }

    /** 有价但没有确认时间 = BOM/SKU 带来的参考价, 不算确认, 不能参与抵扣。 */
    @Test
    void referencePriceWithoutConfirmationTimeDoesNotCredit() {
        MaterialBatch byproduct = byproductBatch(new BigDecimal("36"));
        byproduct.setByproductUnitPrice(new BigDecimal("8"));
        byproduct.setByproductPriceConfirmedAt(null);
        stubStocktake();
        when(stocktakeItemRepo.findByStocktakeId(STOCKTAKE))
                .thenReturn(List.of(item(BATCH, new BigDecimal("30"))));
        when(materialBatchRepo.findByIdAndFactoryId(BATCH, FACTORY)).thenReturn(Optional.of(byproduct));
        when(rawMaterialTypeRepo.findById("MT-1")).thenReturn(Optional.of(material("肥油")));

        ByproductCreditDTO row = service.listByproductCredits(STOCKTAKE, FACTORY).get(0);

        assertThat(row.getUnitPrice()).isEqualByComparingTo("8"); // 参考价照样给人看
        assertThat(row.getCredit()).as("但不参与抵扣").isNull();
        assertThat(row.getCreditStatus()).isEqualTo("PENDING");
    }

    /** 还没盘的行: 盘点重量为 null → 抵扣额 null, 差异也 null(不拿报工重量顶替)。 */
    @Test
    void notYetCountedRowHasNullCreditAndNullDifference() {
        MaterialBatch byproduct = byproductBatch(new BigDecimal("36"));
        byproduct.setByproductUnitPrice(new BigDecimal("8"));
        byproduct.setByproductPriceConfirmedAt(LocalDateTime.now());
        stubStocktake();
        when(stocktakeItemRepo.findByStocktakeId(STOCKTAKE)).thenReturn(List.of(item(BATCH, null)));
        when(materialBatchRepo.findByIdAndFactoryId(BATCH, FACTORY)).thenReturn(Optional.of(byproduct));
        when(rawMaterialTypeRepo.findById("MT-1")).thenReturn(Optional.of(material("肥油")));

        ByproductCreditDTO row = service.listByproductCredits(STOCKTAKE, FACTORY).get(0);

        assertThat(row.getCredit()).isNull();
        assertThat(row.getDifferenceQuantity()).isNull();
    }

    // ---------- 确认单价 ----------

    @Test
    void confirmZeroPriceIsARealConfirmationNotAMissingOne() {
        MaterialBatch byproduct = byproductBatch(new BigDecimal("36"));
        stubStocktake();
        stubConfirmTargets(byproduct, new BigDecimal("30"));

        ByproductCreditDTO row = service.confirmByproductPrice(
                STOCKTAKE, FACTORY, BATCH, BigDecimal.ZERO, 7L);

        assertThat(byproduct.getByproductUnitPrice()).isEqualByComparingTo("0");
        assertThat(byproduct.getByproductPriceConfirmedAt()).isNotNull();
        assertThat(byproduct.getByproductPriceConfirmedBy()).isEqualTo(7L);
        assertThat(row.getCreditStatus()).isEqualTo("CONFIRMED");
        assertThat(row.getCredit()).as("确认为 0 → 0.00, 不是 null").isEqualByComparingTo("0.00");
    }

    @Test
    void confirmRejectsNullAndNegativePriceWithoutWriting() {
        MaterialBatch byproduct = byproductBatch(new BigDecimal("36"));
        stubStocktake();
        stubConfirmTargets(byproduct, new BigDecimal("30"));

        assertThat(assertThrows(BusinessException.class, () -> service.confirmByproductPrice(
                STOCKTAKE, FACTORY, BATCH, null, 7L)).getMessage()).contains("请填写副产单价");
        assertThat(assertThrows(BusinessException.class, () -> service.confirmByproductPrice(
                STOCKTAKE, FACTORY, BATCH, new BigDecimal("-1"), 7L)).getMessage()).contains("不能为负");

        verify(materialBatchRepo, never()).save(any(MaterialBatch.class));
        assertThat(byproduct.getByproductUnitPrice()).isNull();
    }

    /** fail-closed: 不在本盘点单里的批次不许确认 —— 否则可以拿任意批次去改成本。 */
    @Test
    void confirmRejectsBatchOutsideThisStocktake() {
        stubStocktake();
        when(stocktakeItemRepo.findByStocktakeId(STOCKTAKE))
                .thenReturn(List.of(item("BATCH-OTHER", new BigDecimal("5"))));

        assertThat(assertThrows(BusinessException.class, () -> service.confirmByproductPrice(
                STOCKTAKE, FACTORY, BATCH, new BigDecimal("8"), 7L)).getMessage())
                .contains("不在本次盘点范围内");
        verify(materialBatchRepo, never()).save(any(MaterialBatch.class));
    }

    /** fail-closed: 普通批次不是副产, 不许走副产单价这条路。 */
    @Test
    void confirmRejectsNonByproductBatch() {
        MaterialBatch ordinary = ordinaryBatch();
        stubStocktake();
        when(stocktakeItemRepo.findByStocktakeId(STOCKTAKE))
                .thenReturn(List.of(item(BATCH, new BigDecimal("30"))));
        when(materialBatchRepo.findByIdAndFactoryId(BATCH, FACTORY)).thenReturn(Optional.of(ordinary));

        assertThat(assertThrows(BusinessException.class, () -> service.confirmByproductPrice(
                STOCKTAKE, FACTORY, BATCH, new BigDecimal("8"), 7L)).getMessage())
                .contains("不是副产批次");
        verify(materialBatchRepo, never()).save(any(MaterialBatch.class));
    }

    /** 已过账的盘点单不许再改单价 —— 抵扣额已经进过成本, 改了会和已出的数字对不上。 */
    @Test
    void confirmRejectedAfterStocktakeApplied() {
        FactoryStocktake applied = new FactoryStocktake();
        applied.setId(STOCKTAKE);
        applied.setFactoryId(FACTORY);
        applied.setStatus(FactoryStocktake.Status.APPLIED);
        when(stocktakeRepo.findById(STOCKTAKE)).thenReturn(Optional.of(applied));

        assertThat(assertThrows(BusinessException.class, () -> service.confirmByproductPrice(
                STOCKTAKE, FACTORY, BATCH, new BigDecimal("8"), 7L)).getMessage())
                .contains("已过账");
        verify(materialBatchRepo, never()).save(any(MaterialBatch.class));
    }

    /** 多租户: 别的工厂的盘点单一律 403。 */
    @Test
    void otherFactoryCannotReadOrWrite() {
        FactoryStocktake foreign = new FactoryStocktake();
        foreign.setId(STOCKTAKE);
        foreign.setFactoryId("F999");
        foreign.setStatus(FactoryStocktake.Status.COUNTING);
        when(stocktakeRepo.findById(STOCKTAKE)).thenReturn(Optional.of(foreign));

        assertThrows(BusinessException.class,
                () -> service.listByproductCredits(STOCKTAKE, FACTORY));
        assertThrows(BusinessException.class, () -> service.confirmByproductPrice(
                STOCKTAKE, FACTORY, BATCH, new BigDecimal("8"), 7L));
        verify(materialBatchRepo, never()).save(any(MaterialBatch.class));
    }

    // ---------- helpers ----------

    private void stubStocktake() {
        FactoryStocktake stocktake = new FactoryStocktake();
        stocktake.setId(STOCKTAKE);
        stocktake.setFactoryId(FACTORY);
        stocktake.setStatus(FactoryStocktake.Status.COUNTING);
        when(stocktakeRepo.findById(STOCKTAKE)).thenReturn(Optional.of(stocktake));
    }

    private void stubConfirmTargets(MaterialBatch batch, BigDecimal actualQty) {
        when(stocktakeItemRepo.findByStocktakeId(STOCKTAKE)).thenReturn(List.of(item(BATCH, actualQty)));
        when(materialBatchRepo.findByIdAndFactoryId(BATCH, FACTORY)).thenReturn(Optional.of(batch));
        when(rawMaterialTypeRepo.findById("MT-1")).thenReturn(Optional.of(material("肥油")));
    }

    private FactoryStocktakeItem item(String batchId, BigDecimal actualQty) {
        FactoryStocktakeItem item = new FactoryStocktakeItem();
        item.setMaterialBatchId(batchId);
        item.setActualQty(actualQty);
        return item;
    }

    private MaterialBatch byproductBatch(BigDecimal reported) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(BATCH);
        batch.setFactoryId(FACTORY);
        batch.setMaterialTypeId("MT-1");
        batch.setBatchNumber("BP-20260731-01");
        batch.setQuantityUnit("kg");
        batch.setReceiptQuantity(reported);
        batch.setByproductSourceReportId(22020L);
        return batch;
    }

    private MaterialBatch ordinaryBatch() {
        MaterialBatch batch = new MaterialBatch();
        batch.setId("BATCH-ORD");
        batch.setFactoryId(FACTORY);
        batch.setMaterialTypeId("MT-2");
        batch.setQuantityUnit("kg");
        batch.setReceiptQuantity(new BigDecimal("100"));
        batch.setByproductSourceReportId(null); // 关键: 不是副产
        return batch;
    }

    private RawMaterialType material(String name) {
        RawMaterialType type = new RawMaterialType();
        type.setId("MT-1");
        type.setName(name);
        return type;
    }
}
