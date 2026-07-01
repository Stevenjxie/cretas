package com.cretas.aims.service.wip;

import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * C3: WipInventoryService.listWipByFactory — 工厂级半成品重量库存视图单元测试。
 *
 * <p>验证:
 * <ul>
 *   <li>空列表直接返回 (不触发 productTypeRepo 查询)。</li>
 *   <li>单行 AVAILABLE — productTypeName 正确回填, batchId 正确, 成本字段不在 DTO 中。</li>
 *   <li>多行含 null productTypeId — productTypeName 留 null (不 NPE)。</li>
 *   <li>多行同一 productTypeId — 只查一次 productTypeRepo (批量 N+1 防护)。</li>
 * </ul>
 *
 * @since C3 (2026-06-11, fix/c3-semi-weight-view)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("C3: WipInventoryService.listWipByFactory — 工厂级半成品重量库存视图")
class WipInventoryServiceWeightViewTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private WipInventoryServiceImpl service;

    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private BatchLineageEdgeRepository lineageEdgeRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkProcessRepository workProcessRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private ApplicationEventPublisher eventPublisher;

    // ==================== Helpers ====================

    private SemiFinishedInventory wipRow(Long id, String ptId, Long batchId,
                                         BigDecimal produced, BigDecimal consumed, BigDecimal avail,
                                         String status) {
        SemiFinishedInventory w = new SemiFinishedInventory();
        w.setId(id);
        w.setFactoryId(FACTORY_ID);
        w.setProductTypeId(ptId);
        w.setBatchId(batchId);
        w.setIntermediateBatchNo("IB-" + id);
        w.setProcessOrder(1);
        w.setProducedQuantity(produced);
        w.setConsumedQuantity(consumed);
        w.setAvailableQuantity(avail);
        w.setUnit("kg");
        w.setStatus(status);
        return w;
    }

    private ProductType pt(String id, String name) {
        ProductType pt = new ProductType();
        pt.setId(id);
        pt.setName(name);
        pt.setFactoryId(FACTORY_ID);
        pt.setCode("C-" + id);
        pt.setUnit("kg");
        return pt;
    }

    // ==================== Tests ====================

    @Nested
    @DisplayName("空列表场景")
    class EmptyList {

        @Test
        @DisplayName("空列表 → 返回空 List, 不触发 productTypeRepo 查询")
        void emptyRepo_returnsEmptyList_noProductTypeQuery() {
            when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID))
                    .thenReturn(Collections.emptyList());

            List<WipRowDTO> result = service.listWipByFactory(FACTORY_ID);

            assertThat(result).isEmpty();
            verifyNoInteractions(productTypeRepo);
        }
    }

    @Nested
    @DisplayName("单行 AVAILABLE")
    class SingleRowAvailable {

        @Test
        @DisplayName("单行 AVAILABLE — DTO 字段正确映射, productTypeName 回填")
        void singleAvailableRow_correctMapping() {
            String ptId = "PT-001";
            SemiFinishedInventory row = wipRow(1L, ptId, 42L,
                    new BigDecimal("100.000"), new BigDecimal("30.000"), new BigDecimal("70.000"),
                    "AVAILABLE");
            when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID)).thenReturn(List.of(row));
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt(ptId, "猪舌")));

            List<WipRowDTO> result = service.listWipByFactory(FACTORY_ID);

            assertThat(result).hasSize(1);
            WipRowDTO dto = result.get(0);
            assertThat(dto.getIntermediateBatchNo()).isEqualTo("IB-1");
            assertThat(dto.getProductTypeId()).isEqualTo(ptId);
            assertThat(dto.getProductTypeName()).isEqualTo("猪舌");
            assertThat(dto.getBatchId()).isEqualTo(42L);
            assertThat(dto.getProducedQuantity()).isEqualByComparingTo("100.000");
            assertThat(dto.getConsumedQuantity()).isEqualByComparingTo("30.000");
            assertThat(dto.getAvailableQuantity()).isEqualByComparingTo("70.000");
            assertThat(dto.getUnit()).isEqualTo("kg");
            assertThat(dto.getStatus()).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("单行 — processName 留 null (entity 无此字段, 工厂级视图不回填)")
        void singleRow_processNameIsNull() {
            SemiFinishedInventory row = wipRow(2L, "PT-001", 42L,
                    BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, "AVAILABLE");
            when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID)).thenReturn(List.of(row));
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(pt("PT-001", "牛腱")));

            List<WipRowDTO> result = service.listWipByFactory(FACTORY_ID);

            assertThat(result.get(0).getProcessName()).isNull();
        }
    }

    @Nested
    @DisplayName("空/null productTypeId")
    class NullProductTypeId {

        @Test
        @DisplayName("null productTypeId → productTypeName 留 null, 不 NPE")
        void nullProductTypeId_productTypeNameIsNull() {
            SemiFinishedInventory row = wipRow(3L, null, 10L,
                    BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, "AVAILABLE");
            when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID)).thenReturn(List.of(row));
            // ptIds set will be empty → no call to productTypeRepo
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(Collections.emptyList());

            List<WipRowDTO> result = service.listWipByFactory(FACTORY_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getProductTypeName()).isNull();
            // productTypeRepo should not be called when ptIds is empty
            verify(productTypeRepo, never()).findByIdIn(anyCollection());
        }
    }

    @Nested
    @DisplayName("N+1 防护")
    class NoPlusOne {

        @Test
        @DisplayName("3行同一 productTypeId → productTypeRepo 只调用一次 (批量查)")
        void threeRowsSameProductType_onlyOneRepoCall() {
            String ptId = "PT-002";
            List<SemiFinishedInventory> rows = List.of(
                    wipRow(4L, ptId, 20L, new BigDecimal("50"), BigDecimal.ZERO, new BigDecimal("50"), "AVAILABLE"),
                    wipRow(5L, ptId, 20L, new BigDecimal("30"), new BigDecimal("10"), new BigDecimal("20"), "AVAILABLE"),
                    wipRow(6L, ptId, 20L, new BigDecimal("20"), new BigDecimal("20"), BigDecimal.ZERO, "DEPLETED")
            );
            when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID)).thenReturn(rows);
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt(ptId, "掌中宝")));

            List<WipRowDTO> result = service.listWipByFactory(FACTORY_ID);

            assertThat(result).hasSize(3);
            assertThat(result).extracting(WipRowDTO::getProductTypeName).containsOnly("掌中宝");
            // Only one batched call — not 3 individual calls
            verify(productTypeRepo, times(1)).findByIdIn(anyCollection());
        }
    }

    @Nested
    @DisplayName("防呆过滤 (同类 productTypeId + 阶段 maxProcessOrder)")
    class FoolProofFilter {

        private SemiFinishedInventory rowWithOrder(Long id, String ptId, Integer order) {
            SemiFinishedInventory w = wipRow(id, ptId, 40L,
                    new BigDecimal("50"), BigDecimal.ZERO, new BigDecimal("50"), "AVAILABLE");
            w.setProcessOrder(order);
            return w;
        }

        @Test
        @DisplayName("无过滤参数 (null,null) → 全部返回 (向后兼容, 与单参重载一致)")
        void noParams_returnsAll() {
            List<SemiFinishedInventory> rows = List.of(
                    rowWithOrder(1L, "PT-ZHUTI", 2),
                    rowWithOrder(2L, "PT-NIUROU", 2));
            when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID)).thenReturn(rows);
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-ZHUTI", "卤猪蹄"), pt("PT-NIUROU", "卤牛肉")));

            assertThat(service.listWipByFactory(FACTORY_ID, null, null)).hasSize(2);
            assertThat(service.listWipByFactory(FACTORY_ID)).hasSize(2);
        }

        @Test
        @DisplayName("同类 productTypeId → 只返回同产品半成品 (猪蹄计划不显牛肉)")
        void typeFilter_sameProductOnly() {
            when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID)).thenReturn(List.of(
                    rowWithOrder(1L, "PT-ZHUTI", 2),
                    rowWithOrder(2L, "PT-NIUROU", 2)));
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-ZHUTI", "卤猪蹄")));

            List<WipRowDTO> result = service.listWipByFactory(FACTORY_ID, "PT-ZHUTI", null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getProductTypeId()).isEqualTo("PT-ZHUTI");
        }

        @Test
        @DisplayName("阶段 maxProcessOrder → 只返回更早阶段 (processOrder < max, 防回锅)")
        void stageFilter_earlierStagesOnly() {
            when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID)).thenReturn(List.of(
                    rowWithOrder(1L, "PT-A", 1),
                    rowWithOrder(2L, "PT-A", 2),
                    rowWithOrder(3L, "PT-A", 3),   // == current 道, 排除
                    rowWithOrder(4L, "PT-A", 4)));  // 后道, 排除 (防回锅)
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-A", "卤猪蹄")));

            List<WipRowDTO> result = service.listWipByFactory(FACTORY_ID, null, 3);

            assertThat(result).extracting(WipRowDTO::getProcessOrder)
                    .containsExactlyInAnyOrder(1, 2);
        }

        @Test
        @DisplayName("同类 + 阶段 组合 → 同产品且更早阶段")
        void typeAndStage_combined() {
            when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID)).thenReturn(List.of(
                    rowWithOrder(1L, "PT-ZHUTI", 1),   // ✓ 同类且更早
                    rowWithOrder(2L, "PT-ZHUTI", 5),   // ✗ 同类但后道
                    rowWithOrder(3L, "PT-NIUROU", 1))); // ✗ 更早但异类
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-ZHUTI", "卤猪蹄")));

            List<WipRowDTO> result = service.listWipByFactory(FACTORY_ID, "PT-ZHUTI", 3);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getIntermediateBatchNo()).isEqualTo("IB-1");
        }

        @Test
        @DisplayName("null productTypeId 行在同类过滤下严格排除 (防呆宁缺勿滥)")
        void typeFilter_strictExcludesNullProductType() {
            when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID)).thenReturn(List.of(
                    rowWithOrder(1L, null, 2),
                    rowWithOrder(2L, "PT-ZHUTI", 2)));
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-ZHUTI", "卤猪蹄")));

            List<WipRowDTO> result = service.listWipByFactory(FACTORY_ID, "PT-ZHUTI", null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getIntermediateBatchNo()).isEqualTo("IB-2");
        }

        @Test
        @DisplayName("null processOrder 行在阶段过滤下严格排除 (无法确认阶段 → 防回锅)")
        void stageFilter_strictExcludesNullProcessOrder() {
            when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID)).thenReturn(List.of(
                    rowWithOrder(1L, "PT-A", null),
                    rowWithOrder(2L, "PT-A", 1)));
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-A", "卤猪蹄")));

            List<WipRowDTO> result = service.listWipByFactory(FACTORY_ID, null, 3);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getIntermediateBatchNo()).isEqualTo("IB-2");
        }

        @Test
        @DisplayName("过滤后为空 → 返回空 List 且不查 productTypeRepo (短路)")
        void filteredToEmpty_noProductTypeQuery() {
            when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID))
                    .thenReturn(List.of(rowWithOrder(1L, "PT-NIUROU", 2)));

            List<WipRowDTO> result = service.listWipByFactory(FACTORY_ID, "PT-ZHUTI", null);

            assertThat(result).isEmpty();
            verify(productTypeRepo, never()).findByIdIn(anyCollection());
        }
    }

    @Nested
    @DisplayName("全状态覆盖 (AVAILABLE/DEPLETED/RETURNED)")
    class AllStatuses {

        @Test
        @DisplayName("DEPLETED + RETURNED 行也出现在结果中 (全状态快照)")
        void depletedAndReturnedRowsIncluded() {
            String ptId = "PT-003";
            List<SemiFinishedInventory> rows = List.of(
                    wipRow(7L, ptId, 30L, new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, "DEPLETED"),
                    wipRow(8L, ptId, 30L, new BigDecimal("50"), new BigDecimal("50"), BigDecimal.ZERO, "RETURNED")
            );
            when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID)).thenReturn(rows);
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt(ptId, "猪蹄")));

            List<WipRowDTO> result = service.listWipByFactory(FACTORY_ID);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(WipRowDTO::getStatus)
                    .containsExactlyInAnyOrder("DEPLETED", "RETURNED");
        }
    }
}
