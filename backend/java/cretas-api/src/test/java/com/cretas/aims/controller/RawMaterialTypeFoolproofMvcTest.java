package com.cretas.aims.controller;

import com.cretas.aims.config.EnumCoercionJacksonConfig;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.RawMaterialTypeService;
import com.cretas.aims.service.SupplierService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 MVC 链路护栏 — 验证 #941 (枚举空串归一) + #938 (自动编码字段无 @NotBlank)
 * 在 <b>真实的 {@code @RequestBody} → Jackson 反序列化 → {@code @Valid} Bean Validation
 * → {@link GlobalExceptionHandler}</b> 全栈链路上真正关闭，而非仅在手搓 ObjectMapper 上。
 *
 * <p><b>为什么需要这个，而手搓单元测试不够</b>：
 * {@link com.cretas.aims.config.EnumCoercionJacksonConfigTest} 用
 * {@code Jackson2ObjectMapperBuilder} 复刻 customizer 后直接 {@code readValue}，
 * 只覆盖 Jackson 一层。它无法察觉这些回归：
 * <ul>
 *   <li>有人改了 WebMvcConfig 注册了一个<b>覆盖</b> coercion customizer 的 ObjectMapper；</li>
 *   <li>有人调整了 {@link GlobalExceptionHandler#handleHttpMessageNotReadableException} 的
 *       分支，让空枚举重新落到晦涩 fallback "请求格式不正确，请检查JSON格式" (~line 928)；</li>
 *   <li>有人给自动生成的 {@code code} 字段加回 {@code @NotBlank}，让建档在 {@code @Valid}
 *       阶段被提前 400。</li>
 * </ul>
 * 这些 bug 会让手搓单测照样绿、prod 照样炸。本测试过真实转换器链 + 真实异常处理器，
 * 才能钉死契约。
 *
 * <p><b>隔离策略</b>：{@code standaloneSetup} <b>不</b>挂 {@code PermissionInterceptor}，
 * 所以绕开了 {@code @RequirePermission} 鉴权（鉴权不是本测试的契约）；但保留真实
 * {@code @RequestBody} 绑定 + {@code @Valid} + 自定义异常处理器。Service 用 Mockito mock，
 * 创建路径不真正落库。
 *
 * @since 2026-06-16
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RawMaterialTypeController 防呆契约 — 真实 MVC 链路 (#941 空枚举 / #938 自动编码)")
class RawMaterialTypeFoolproofMvcTest {

    @Mock
    private RawMaterialTypeService materialTypeService;
    @Mock
    private MobileService mobileService;
    @Mock
    private SupplierService supplierService;

    private MockMvc mockMvc;

    private static final String CREATE_URL = "/api/mobile/F006/raw-material-types";

    @BeforeEach
    void setUp() {
        RawMaterialTypeController controller =
                new RawMaterialTypeController(materialTypeService, mobileService, supplierService);

        // 复刻 prod 的 @RequestBody ObjectMapper: 应用全局枚举空串→null coercion customizer
        // 到 Jackson2ObjectMapperBuilder 后 build。这是 MVC 真正用来反序列化请求体的那个 mapper。
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new EnumCoercionJacksonConfig().enumEmptyStringAsNullCustomizer().customize(builder);
        ObjectMapper prodObjectMapper = builder.build();
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(prodObjectMapper);

        // 真实 jakarta Bean Validation (@Valid 生效) — 必须 afterPropertiesSet 初始化底层
        // hibernate-validator ValidatorFactory，否则 standaloneSetup 下 @Valid 静默不跑。
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())   // 真实异常处理器
                .setMessageConverters(converter)                     // 真实 @RequestBody 转换器 (含 coercion)
                .setValidator(validator)                             // 真实 @Valid
                .build();
    }

    /**
     * 断言 1 (#941 核心): 空枚举 {@code "taxRate":""} 必须过反序列化，<b>不</b>落到
     * 晦涩 fallback "请求格式不正确"。此处省略必填 name/unit，所以期望 400，但 message
     * 应是字段级 "xxx不能为空" — 证明请求体成功反序列化进 DTO 后才在 @Valid 阶段被拒。
     */
    @Test
    @DisplayName("#941: 空枚举 taxRate=\"\" 过反序列化 → 400 是字段级校验, 不是晦涩 JSON fallback")
    void emptyEnum_passesDeserialization_fieldLevelValidationNotJsonFallback() throws Exception {
        // 仅 taxRate 空串, 故意省略 name/unit (它们带 @NotBlank)
        mockMvc.perform(post(CREATE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taxRate\":\"\"}"))
                .andExpect(status().isBadRequest())
                // 关键: 空枚举没把整个请求体打成 "JSON 格式不对"
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("请求格式不正确"))))
                // 应是字段级校验 — name/unit 缺失 (@NotBlank 触发 MethodArgumentNotValidException)
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.anyOf(
                                org.hamcrest.Matchers.containsString("不能为空"),
                                org.hamcrest.Matchers.containsString("name"),
                                org.hamcrest.Matchers.containsString("unit"))));

        // service 不应被调用 (@Valid 在 controller 方法体之前就拒了)
        verify(materialTypeService, never()).createMaterialType(anyString(), any());
    }

    /**
     * 断言 2 (#938 核心): 合法 body 但<b>省略 code</b> — name/unit/category/storageType 齐全，
     * 无 code。code 上若还挂 @NotBlank 会在 @Valid 阶段被拒 400 ("原材料编码不能为空")。
     * 期望: 不被拒，请求到达 service (即 createMaterialType 被调用)。
     */
    @Test
    @DisplayName("#938: 合法 body 省略 code → 不被 @NotBlank 拦截, 到达 service 自动生成")
    void omittedCode_reachesService_notBlockedByNotBlank() throws Exception {
        RawMaterialTypeDTO returned = RawMaterialTypeDTO.builder()
                .id("RMT-F006-001")
                .code("YL001")          // service 自动生成
                .name("猪舌")
                .unit("kg")
                .build();
        when(materialTypeService.createMaterialType(anyString(), any())).thenReturn(returned);

        mockMvc.perform(post(CREATE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        // 齐全但无 code, 也无 taxRate (可选)
                        .content("{\"name\":\"猪舌\",\"unit\":\"kg\",\"category\":\"肉类\",\"storageType\":\"frozen\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.code").value("YL001"));

        // 证明请求确实穿透 @Valid 到达 service (code 缺失没被 @NotBlank 提前 400)
        verify(materialTypeService).createMaterialType(anyString(), any());
    }

    /**
     * 断言 3 (不掩盖真错误): 非法枚举值 {@code "taxRate":"NONSENSE"} 仍然清晰 400。
     * coercion 只把<b>空串</b>→null, 真正非法的枚举值必须照样拒绝, 且 message 要可读
     * (含字段/类型线索), 不能退回晦涩 fallback。
     */
    @Test
    @DisplayName("不掩盖真错误: 非法枚举 taxRate=\"NONSENSE\" → 400 清晰类型错误, 不是晦涩 fallback")
    void invalidEnum_stillRejectedClearly() throws Exception {
        // 带齐 name/unit, 隔离出 taxRate 才是被拒原因
        mockMvc.perform(post(CREATE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"猪舌\",\"unit\":\"kg\",\"taxRate\":\"NONSENSE\"}"))
                .andExpect(status().isBadRequest())
                // 必须可读: 含 "TaxRate" 类型名 或 "类型不正确" — 不退回晦涩 fallback
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.anyOf(
                                org.hamcrest.Matchers.containsString("TaxRate"),
                                org.hamcrest.Matchers.containsString("类型不正确"))))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("请求格式不正确"))));

        verify(materialTypeService, never()).createMaterialType(anyString(), any());
    }

    /**
     * 断言 4 (shape mismatch 仍清晰): 把对象字段发成空串 (这里用 {@code minStock:"abc"} —
     * BigDecimal 收到非数字字符串) → 应是清晰 "字段类型不正确" 而非晦涩 fallback。
     * 证明 GlobalExceptionHandler 的 "Cannot deserialize value of type" 分支真的接管了
     * 这类反序列化失败 (~line 893)，给字段级提示。
     */
    @Test
    @DisplayName("shape mismatch: minStock=\"abc\" (非数字) → 清晰字段类型错误, 不是晦涩 fallback")
    void shapeMismatch_clearFieldTypeError_notJsonFallback() throws Exception {
        mockMvc.perform(post(CREATE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"猪舌\",\"unit\":\"kg\",\"minStock\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("请求格式不正确"))))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.anyOf(
                                org.hamcrest.Matchers.containsString("类型不正确"),
                                org.hamcrest.Matchers.containsString("minStock"))));

        verify(materialTypeService, never()).createMaterialType(anyString(), any());
    }
}
