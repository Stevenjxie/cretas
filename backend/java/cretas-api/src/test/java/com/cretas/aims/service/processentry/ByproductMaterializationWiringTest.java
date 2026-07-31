package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.processentry.impl.ClerkProcessEntryServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接线测试: 报工写完 YIELD 报工后, <b>确实</b>去把副产落成生产仓批次。
 *
 * <p>🔴 <b>为什么必须单独有这条</b>: 只测 materializer 本身是不够的 ——
 * {@code buildByproductBatch} 就是活生生的例子: 函数写好了、单测也绿, 但<b>没有任何生产代码调它</b>,
 * 于是 prod 895 条批次里 {@code source_doc_type='BYPRODUCT'} 一条没有, 整条副产链断在这里。
 * 本项目已经数出 5 次「建好了没人调」。这条用例盯的就是**调用点**: 摘掉它就会红。</p>
 *
 * <p>(写这条之前我先做了变异: 把接线短路掉, materializer 的 6 条单测<b>全都还是绿的</b> ——
 * 正好证明只测实现挡不住这类缺陷。)</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ByproductMaterializationWiringTest {

    @Mock private ProductionReportRepository reportRepo;
    @Mock private ByproductBatchMaterializer materializer;

    private ClerkProcessEntryServiceImpl service() {
        // 15 个 final 依赖(@RequiredArgsConstructor); 本测试只用得到 reportRepo 与 objectMapper,
        // 其余传 null —— 走的是 writeYieldAuxReport 这一条不碰它们的路径。
        ClerkProcessEntryServiceImpl service = new ClerkProcessEntryServiceImpl(
                null, null, null, null, null, null, null, reportRepo,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "byproductBatchMaterializer", materializer);
        return service;
    }

    @Test
    void yieldAuxReportHandsReportedByproductsToTheMaterializer() {
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(inv -> {
            ProductionReport saved = inv.getArgument(0);
            saved.setId(90001L); // 落库后才有 id —— 批次要靠它记来源报工
            return saved;
        });

        ProcessChainEntryRequest.StepEntry st = new ProcessChainEntryRequest.StepEntry();
        st.setProcessOrder(3);
        ProcessChainEntryRequest.Byproduct bp = new ProcessChainEntryRequest.Byproduct();
        bp.setName("肥油");
        bp.setQuantity(new BigDecimal("36"));
        bp.setUnit("kg");
        List<ProcessChainEntryRequest.Byproduct> byproducts = new ArrayList<>();
        byproducts.add(bp);
        st.setByproducts(byproducts);

        ReflectionTestUtils.invokeMethod(service(), "writeYieldAuxReport",
                "F006", 501L, st, 7L, "PT-JI");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(materializer).materialize(eq("F006"), eq("PT-JI"), eq(90001L), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0)).containsEntry("name", "肥油");
    }

    /** 没有副产就不该去打扰 materializer —— 免得每条报工都白跑一次 BOM 查询。 */
    @Test
    void reportWithoutByproductsDoesNotCallTheMaterializer() {
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(inv -> inv.getArgument(0));

        ProcessChainEntryRequest.StepEntry st = new ProcessChainEntryRequest.StepEntry();
        st.setProcessOrder(3);
        st.setSampleRetainQuantity(1);

        ReflectionTestUtils.invokeMethod(service(), "writeYieldAuxReport",
                "F006", 501L, st, 7L, "PT-JI");

        verify(materializer, org.mockito.Mockito.never())
                .materialize(anyString(), any(), any(), any());
    }
}
