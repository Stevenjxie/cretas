package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.bom.ByproductDeclarationResolver;
import com.cretas.aims.service.factory.WarehouseResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报工副产 → 生产仓批次 的接线。
 *
 * <p>🔴 起因: {@code buildByproductBatch} 早就写好了, 但 2026-08-01 走查发现它<b>唯一的调用方
 * 是自己的测试</b> —— prod 895 条批次里 {@code source_doc_type='BYPRODUCT'} <b>0 条</b>。
 * 整条链在这里断掉: 没有副产批次 → 盘点看不到副产 → 抵扣永远没有输入。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ByproductBatchMaterializerTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "PT-JI";
    private static final Long REPORT = 90001L;

    @Mock private ByproductDeclarationResolver declarationResolver;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private MaterialBatchRepository materialBatchRepository;

    @InjectMocks private ByproductBatchMaterializer materializer;

    private void stubEnv(List<Map<String, Object>> declarations, String workshopId) {
        when(declarationResolver.resolve(anyString(), any(), any())).thenReturn(declarations);
        when(warehouseResolver.resolveWorkshopId(FACTORY)).thenReturn(workshopId);
        when(materialBatchRepository.save(any(MaterialBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void declaredByproductLandsInTheWorkshopWarehouseWithoutAPrice() {
        stubEnv(List.of(declaration("肥油", "MT-FEIYOU")), "WH-WORKSHOP");

        List<MaterialBatch> created = materializer.materialize(
                FACTORY, PRODUCT, REPORT, List.of(reported("肥油", "36", "kg", "8")));

        assertThat(created).hasSize(1);
        MaterialBatch batch = created.get(0);
        assertThat(batch.getMaterialTypeId()).isEqualTo("MT-FEIYOU");
        assertThat(batch.getReceiptQuantity()).isEqualByComparingTo("36");
        assertThat(batch.getQuantityUnit()).isEqualTo("kg");
        assertThat(batch.getWarehouseId()).as("去向是生产仓, 不是原料仓").isEqualTo("WH-WORKSHOP");
        assertThat(batch.getByproductSourceReportId()).isEqualTo(REPORT);
        // 🔴 报工时不写单价 —— 连报工里填的 8 也不写。单价在盘点确认, 此处写任何值都是臆造
        assertThat(batch.getByproductUnitPrice()).isNull();
        assertThat(batch.getUnitPrice()).isNull();
    }

    /**
     * 🔴 匹配不上 BOM 声明的副产: 跳过, 但**不拦报工**。
     * 线上既有副产录入全是自由文本(prod 15 条, 名称如「肥油」「料头」), 改成硬拦会当场断掉那些流程。
     */
    @Test
    void undeclaredByproductIsSkippedWithoutBlockingTheReport() {
        stubEnv(List.of(declaration("肥油", "MT-FEIYOU")), "WH-WORKSHOP");

        List<MaterialBatch> created = materializer.materialize(
                FACTORY, PRODUCT, REPORT,
                List.of(reported("料头", "5", "kg", null), reported("肥油", "36", "kg", null)));

        assertThat(created).as("只落声明过的那条").hasSize(1);
        assertThat(created.get(0).getMaterialTypeId()).isEqualTo("MT-FEIYOU");
    }

    /** 没有生产仓就不落库 —— 禁降级: 不随便找个仓塞进去。 */
    @Test
    void missingWorkshopWarehouseMeansNoBatch() {
        stubEnv(List.of(declaration("肥油", "MT-FEIYOU")), null);

        assertThat(materializer.materialize(
                FACTORY, PRODUCT, REPORT, List.of(reported("肥油", "36", "kg", null)))).isEmpty();
        verify(materialBatchRepository, never()).save(any(MaterialBatch.class));
    }

    @Test
    void zeroOrMalformedQuantityProducesNoBatch() {
        stubEnv(List.of(declaration("肥油", "MT-FEIYOU")), "WH-WORKSHOP");

        assertThat(materializer.materialize(FACTORY, PRODUCT, REPORT,
                List.of(reported("肥油", "0", "kg", null)))).isEmpty();
        assertThat(materializer.materialize(FACTORY, PRODUCT, REPORT,
                List.of(reported("肥油", "abc", "kg", null)))).isEmpty();
        verify(materialBatchRepository, never()).save(any(MaterialBatch.class));
    }

    @Test
    void noDeclarationsOrNoReportedByproductsIsANoOp() {
        when(declarationResolver.resolve(anyString(), any(), any())).thenReturn(List.of());
        assertThat(materializer.materialize(FACTORY, PRODUCT, REPORT,
                List.of(reported("肥油", "36", "kg", null)))).isEmpty();

        stubEnv(List.of(declaration("肥油", "MT-FEIYOU")), "WH-WORKSHOP");
        assertThat(materializer.materialize(FACTORY, PRODUCT, REPORT, List.of())).isEmpty();
        assertThat(materializer.materialize(FACTORY, PRODUCT, REPORT, null)).isEmpty();
        verify(materialBatchRepository, never()).save(any(MaterialBatch.class));
    }

    /** 多条副产各落各的批次, 且都挂同一个来源报工。 */
    @Test
    void multipleDeclaredByproductsEachGetTheirOwnBatch() {
        stubEnv(List.of(declaration("肥油", "MT-FEIYOU"), declaration("碎肉", "MT-SUIROU")),
                "WH-WORKSHOP");

        List<MaterialBatch> created = materializer.materialize(FACTORY, PRODUCT, REPORT,
                List.of(reported("肥油", "36", "kg", null), reported("碎肉", "8", "kg", null)));

        ArgumentCaptor<MaterialBatch> captor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(created).hasSize(2);
        assertThat(captor.getAllValues()).extracting(MaterialBatch::getMaterialTypeId)
                .containsExactly("MT-FEIYOU", "MT-SUIROU");
        assertThat(captor.getAllValues()).allSatisfy(
                b -> assertThat(b.getByproductSourceReportId()).isEqualTo(REPORT));
    }

    private Map<String, Object> declaration(String name, String materialTypeId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("materialTypeId", materialTypeId);
        row.put("unit", "kg");
        row.put("source", ByproductDeclarationResolver.SOURCE_BOM);
        return row;
    }

    private Map<String, Object> reported(String name, String qty, String unit, String unitPrice) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("quantity", qtyOrRaw(qty));
        row.put("unit", unit);
        if (unitPrice != null) row.put("unitPrice", new BigDecimal(unitPrice));
        return row;
    }

    private Object qtyOrRaw(String qty) {
        try {
            return new BigDecimal(qty);
        } catch (NumberFormatException e) {
            return qty; // 故意留成非法字符串, 验证不会被当成数量
        }
    }
}
