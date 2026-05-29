package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.dto.ProductWorkProcessDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.ProductWorkProcessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归测试 — 修审计 P0-A: WorkProcessConfigUpdateTool.doExecute 的 REPLACE 语义
 * 不能静默清零用户先前配的 unitOverride / estimatedMinutesOverride (违反 fool-proof Rule 1).
 *
 * <p>对应实现计划 §9#4 DOD: 先有带 override 的工序绑定 → AI 重配工序(含同一工序) →
 * 断言该工序的 override 被 carry-over 保留, 新增工序的 override 为 null。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkProcessConfigUpdateTool — P0-A override carry-over")
class WorkProcessConfigUpdateToolCarryOverTest {

    @Mock
    private ProductWorkProcessRepository productWorkProcessRepository;
    @Mock
    private ProductWorkProcessService productWorkProcessService;
    @Mock
    private WorkProcessRepository workProcessRepository;

    @InjectMocks
    private WorkProcessConfigUpdateTool tool;

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_TYPE_ID = "pt-zhutui";

    private WorkProcess wp(String id, String name) {
        return WorkProcess.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .processName(name)
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("重配工序时, 保留工序的 unitOverride/estimatedMinutesOverride 被 carry-over, 新增工序为 null")
    void doExecute_carriesOverOverridesForRetainedProcess() {
        // 工序定义: 拆包(wp-cb) + 卤制(wp-lz)
        when(workProcessRepository.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(wp("wp-cb", "拆包"), wp("wp-lz", "卤制")));

        // 现有绑定: 拆包 已被用户配了 override (单位=件, 工时=30 分)
        ProductWorkProcess existingChaiBao = ProductWorkProcess.builder()
                .id(1L)
                .factoryId(FACTORY_ID)
                .productTypeId(PRODUCT_TYPE_ID)
                .workProcessId("wp-cb")
                .processOrder(0)
                .unitOverride("件")
                .estimatedMinutesOverride(30)
                .isActive(true)
                .build();
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(existingChaiBao));

        // create 回显传入的 dto
        when(productWorkProcessService.create(eq(FACTORY_ID), any(ProductWorkProcessDTO.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        // AI 重配: 拆包(保留) + 卤制(新增)
        Map<String, Object> params = new HashMap<>();
        params.put("productTypeId", PRODUCT_TYPE_ID);
        params.put("workProcessNames", List.of("拆包", "卤制"));

        tool.doExecute(FACTORY_ID, params, new HashMap<>());

        // REPLACE: 旧绑定被删
        verify(productWorkProcessService, times(1)).delete(FACTORY_ID, 1L);

        // 捕获两次 create
        ArgumentCaptor<ProductWorkProcessDTO> captor = ArgumentCaptor.forClass(ProductWorkProcessDTO.class);
        verify(productWorkProcessService, times(2)).create(eq(FACTORY_ID), captor.capture());
        List<ProductWorkProcessDTO> created = captor.getAllValues();

        // 第 0 道 = 拆包: override 被 carry-over 保留 (P0-A 修复点)
        ProductWorkProcessDTO chaiBao = created.get(0);
        assertThat(chaiBao.getWorkProcessId()).isEqualTo("wp-cb");
        assertThat(chaiBao.getProcessOrder()).isEqualTo(0);
        assertThat(chaiBao.getUnitOverride()).isEqualTo("件");
        assertThat(chaiBao.getEstimatedMinutesOverride()).isEqualTo(30);

        // 第 1 道 = 卤制: 新增工序, 无旧 override → null
        ProductWorkProcessDTO luZhi = created.get(1);
        assertThat(luZhi.getWorkProcessId()).isEqualTo("wp-lz");
        assertThat(luZhi.getProcessOrder()).isEqualTo(1);
        assertThat(luZhi.getUnitOverride()).isNull();
        assertThat(luZhi.getEstimatedMinutesOverride()).isNull();
    }
}
