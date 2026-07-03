package com.cretas.aims.controller;

import com.cretas.aims.service.factory.FactoryStocktakeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 路由歧义护栏 — 验证新增的 {@code GET /stocktakes/initiate-constraint} 不会被
 * Spring 路由匹配到既有的 {@code GET /stocktakes/{stocktakeId}} 通配段, 即不会被
 * 误当成 stocktakeId="initiate-constraint" 去查详情 (会 404/500, 且是错误的语义)。
 *
 * <p>Bug 4 (fool-proof Rule 1): 前端"发起盘点"弹窗需要这个端点在打开时立刻拿到月底约束
 * 展示态。若路由被 {@code /{stocktakeId}} 吃掉, 弹窗会静默拿不到约束展示 (fallback 无 banner),
 * 属于隐蔽的功能性 regression, 值得独立护栏。
 *
 * <p>{@code standaloneSetup}: 不挂鉴权 interceptor (鉴权不是本测试契约), service 用 Mockito mock。
 *
 * @since 2026-07-03 (warehouse/wastage-reports headed-audit bugfix batch, Bug 4)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StocktakeController /initiate-constraint 路由歧义护栏")
class StocktakeControllerInitiateConstraintRouteTest {

    @Mock
    private FactoryStocktakeService stocktakeService;

    private MockMvc mockMvc;

    private static final String FACTORY_ID = "F006";

    @BeforeEach
    void setUp() {
        StocktakeController controller = new StocktakeController(stocktakeService);

        // 复刻 prod Jackson 配置 (application-pg.properties:
        // spring.jackson.serialization.write-dates-as-timestamps=false) — 否则 standaloneSetup
        // 默认 ObjectMapper 把 LocalDate 序列化成 [2026,7,29] 数组而非 "2026-07-29" 字符串,
        // 跟真实 prod 响应形状不一致。
        ObjectMapper objectMapper = new Jackson2ObjectMapperBuilder()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(converter)
                .build();
    }

    @Test
    @DisplayName("GET /initiate-constraint 命中专用 handler, 不落到 getDetail({stocktakeId})")
    void initiateConstraintRoute_hitsDedicatedHandler_notDetailFallthrough() throws Exception {
        Map<String, Object> constraint = new LinkedHashMap<>();
        constraint.put("monthEndThreshold", 29);
        constraint.put("canInitiateToday", false);
        constraint.put("today", LocalDate.of(2026, 7, 3));
        constraint.put("nextAllowedDate", LocalDate.of(2026, 7, 29));
        when(stocktakeService.getInitiateConstraint()).thenReturn(constraint);

        mockMvc.perform(get("/api/mobile/{factoryId}/stocktakes/initiate-constraint", FACTORY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.monthEndThreshold").value(29))
                .andExpect(jsonPath("$.data.canInitiateToday").value(false))
                .andExpect(jsonPath("$.data.nextAllowedDate").value("2026-07-29"));

        // 必须调用专属方法, 绝不能落到 getDetail(stocktakeId="initiate-constraint")
        verify(stocktakeService, never()).getDetail(anyString(), anyString());
    }

    @Test
    @DisplayName("GET /{stocktakeId} (真实 UUID) 仍正常命中 getDetail, 新路由未破坏既有路由")
    void realStocktakeIdRoute_stillHitsGetDetail() throws Exception {
        String stocktakeId = "5f2b1c3a-1111-4444-8888-abcdefabcdef";
        var dto = new com.cretas.aims.dto.factory.StocktakeDTO();
        when(stocktakeService.getDetail(stocktakeId, FACTORY_ID)).thenReturn(dto);

        mockMvc.perform(get("/api/mobile/{factoryId}/stocktakes/{stocktakeId}", FACTORY_ID, stocktakeId))
                .andExpect(status().isOk());

        verify(stocktakeService).getDetail(stocktakeId, FACTORY_ID);
        verify(stocktakeService, never()).getInitiateConstraint();
    }
}
