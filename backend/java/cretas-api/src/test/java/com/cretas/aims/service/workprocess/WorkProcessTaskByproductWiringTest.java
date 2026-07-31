package com.cretas.aims.service.workprocess;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.bom.ByproductDeclarationResolver;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeService;
import com.cretas.aims.service.workprocess.impl.WorkProcessTaskServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 接线测试: 报工任务的「预期副产」确实经过 {@link ByproductDeclarationResolver}。
 *
 * <p>🔴 光测 resolver 本身不够 —— 谁把它接上才是关键。本仓 2026-07-31 刚数出三处
 * 「建好了没人调 / 声明了没人读」({@code ByproductCreditService} 零调用方、
 * {@code work_processes.expected_byproducts} RN 侧零引用、CI 制品脚本从没被打过桩),
 * 所以这里专门钉调用点: 摘掉 resolver 这条用例就会红。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkProcessTaskByproductWiringTest {

    @Mock private WorkProcessTaskRepository taskRepository;
    @Mock private ProductWorkProcessRepository productWorkProcessRepository;
    @Mock private WorkProcessRepository workProcessRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductProcessWorkflowRuntimeService runtimeService;
    @Mock private ByproductDeclarationResolver resolver;

    private WorkProcessTaskServiceImpl service() {
        WorkProcessTaskServiceImpl service = new WorkProcessTaskServiceImpl(
                taskRepository, productWorkProcessRepository, workProcessRepository,
                userRepository, productionBatchRepository, productTypeRepository, runtimeService);
        ReflectionTestUtils.setField(service, "byproductDeclarationResolver", resolver);
        return service;
    }

    @Test
    void taskDtoTakesExpectedByproductsFromTheResolverNotTheRawProcessField() {
        WorkProcess definition = new WorkProcess();
        definition.setProcessName("修油");
        definition.setExpectedByproducts(List.of(declaration("肥油(工序历史声明)")));
        WorkProcessTask task = new WorkProcessTask();
        task.setId(1L);
        task.setFactoryId("F006");
        task.setProductTypeId("PT-ZHUSHE");

        List<Map<String, Object>> resolved = List.of(declaration("肥油(BOM 权威)"));
        when(resolver.resolve("F006", "PT-ZHUSHE", definition.getExpectedByproducts()))
                .thenReturn(resolved);

        WorkProcessTaskDTO dto = ReflectionTestUtils.invokeMethod(
                service(), "toDTO", task, definition, null);

        assertThat(dto).isNotNull();
        verify(resolver).resolve("F006", "PT-ZHUSHE", definition.getExpectedByproducts());
        assertThat(dto.getExpectedByproducts())
                .as("必须用 resolver 的结果, 不能直接透传工序上的自由文本声明")
                .isSameAs(resolved);
    }

    /**
     * 哨兵任务(免工序报工)没有 WorkProcess 定义 —— 不该去问 resolver, 预期副产留 null。
     * 「诚实, 哨兵无标准」是既有约定, 加这条链路不能把它破坏掉。
     */
    @Test
    void sentinelTaskWithoutDefinitionDoesNotConsultResolver() {
        WorkProcessTask task = new WorkProcessTask();
        task.setId(2L);
        task.setFactoryId("F006");
        task.setProductTypeId("PT-ZHUSHE");

        WorkProcessTaskDTO dto = ReflectionTestUtils.invokeMethod(
                service(), "toDTO", task, null, null);

        assertThat(dto).isNotNull();
        assertThat(dto.getExpectedByproducts()).isNull();
        verifyNoInteractions(resolver);
    }

    private Map<String, Object> declaration(String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("unit", "kg");
        return row;
    }
}
