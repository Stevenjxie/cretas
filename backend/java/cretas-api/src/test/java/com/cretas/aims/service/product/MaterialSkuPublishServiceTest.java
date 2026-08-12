package com.cretas.aims.service.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.ProductTypeService;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.annotation.Transactional;

/**
 * 物料发布为可售 SKU。
 *
 * <p>2026-08-12 Steve 拍板(六膳门张权:「有啥不能卖的 给钱 我都能卖」)。
 */
@DisplayName("物料发布为可售 SKU")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialSkuPublishServiceTest {

    private static final String FACTORY = "F006";
    private static final Long USER_ID = 42L;

    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductTypeService productTypeService;

    @InjectMocks private MaterialSkuPublishService service;

    private RawMaterialType material(String id, String code, String name, String category) {
        RawMaterialType m = new RawMaterialType();
        m.setId(id);
        m.setFactoryId(FACTORY);
        m.setCode(code);
        m.setName(name);
        m.setCategory(category);
        m.setUnit("kg");
        m.setUnitPrice(new BigDecimal("12.50"));
        when(rawMaterialTypeRepository.findByIdAndFactoryId(id, FACTORY)).thenReturn(Optional.of(m));
        return m;
    }

    @BeforeEach
    void setUp() {
        when(productTypeRepository.existsByFactoryIdAndCode(eq(FACTORY), any())).thenReturn(false);
    }

    /**
     * ⛔ 这条是本文件里最重要的一条。
     *
     * <p>批量发布要「一条失败不牵连其他」。一旦有人给本类(或本方法)加上
     * {@code @Transactional}, 循环里 {@code createProductType} 抛出的异常就会把
     * <b>整个</b>事务标成 rollback-only —— catch 只吞异常, <b>阻止不了回滚</b>,
     * 结果是「日志显示成功 N 条失败 1 条, 实际一条都没落库」。
     *
     * <p>这个坏法<b>不报错、不变红</b>, 只在真机上表现为「点了发布, 什么也没发生」。
     * 2026-08-12 同仓另两处已实测踩过这个形状, 所以这里用反射把它钉死。
     */
    @Test
    @DisplayName("⛔ 本类不能有 @Transactional —— 有了就会「失败一条回滚全部」")
    void mustNotBeTransactional() throws NoSuchMethodException {
        assertThat(MaterialSkuPublishService.class.getAnnotation(Transactional.class))
                .as("类上不能有 @Transactional")
                .isNull();

        Method publish = MaterialSkuPublishService.class.getMethod(
                "publish", String.class, java.util.Collection.class, Long.class);
        assertThat(publish.getAnnotation(Transactional.class))
                .as("publish 方法上不能有 @Transactional")
                .isNull();
    }

    @Test
    @DisplayName("发布出来的是 RAW_MATERIAL 类别 + M- 前缀编号, 且带上建档人")
    void publishesAsRawMaterialSku() {
        material("RMT_1", "BC001", "吸塑盒2014-3.5", "包材");

        MaterialSkuPublishService.Result result = service.publish(FACTORY, List.of("RMT_1"), USER_ID);

        ArgumentCaptor<ProductTypeDTO> dto = ArgumentCaptor.forClass(ProductTypeDTO.class);
        verify(productTypeService).createProductType(eq(FACTORY), dto.capture());

        assertThat(dto.getValue().getCode()).isEqualTo("M-BC001");
        // 生产侧的 findVisible… 就是靠这个值把它排除在生产下拉之外的
        assertThat(dto.getValue().getProductCategory()).isEqualTo(ProductCategory.RAW_MATERIAL);
        assertThat(dto.getValue().getCategory()).isEqualTo("包材");
        assertThat(dto.getValue().getName()).isEqualTo("吸塑盒2014-3.5");
        assertThat(dto.getValue().getUnit()).isEqualTo("kg");
        assertThat(dto.getValue().getIsActive()).isTrue();
        // ⚠️ product_types.created_by 是 NOT NULL, 而 createProductType 不会自己填 ——
        // 漏掉它这条路径会在 insert 时炸约束(本轮写代码时实际漏过一次)。
        assertThat(dto.getValue().getCreatedBy()).isEqualTo(USER_ID);

        assertThat(result.created()).containsExactly("M-BC001");
        assertThat(result.failed()).isEmpty();
    }

    @Test
    @DisplayName("已发布过的不重复建 —— 幂等, 走 alreadyPublished")
    void isIdempotent() {
        material("RMT_1", "BC001", "吸塑盒2014-3.5", "包材");
        when(productTypeRepository.existsByFactoryIdAndCode(FACTORY, "M-BC001")).thenReturn(true);

        MaterialSkuPublishService.Result result = service.publish(FACTORY, List.of("RMT_1"), USER_ID);

        verify(productTypeService, never()).createProductType(any(), any());
        assertThat(result.alreadyPublished()).containsExactly("M-BC001");
        assertThat(result.created()).isEmpty();
    }

    @Test
    @DisplayName("一条失败不影响其余 —— 重名那条记进 failed, 其他照常新建")
    void oneFailureDoesNotStopTheRest() {
        material("RMT_1", "A001", "鸡胸肉", "原料");
        material("RMT_2", "A002", "鸡胸肉", "原料");   // 物料字典允许同名, 产品名不允许
        material("RMT_3", "A003", "猪蹄", "原料");

        when(productTypeService.createProductType(eq(FACTORY), any()))
                .thenReturn(new ProductTypeDTO())
                .thenThrow(new RuntimeException("产品名称已存在: 鸡胸肉"))
                .thenReturn(new ProductTypeDTO());

        MaterialSkuPublishService.Result result =
                service.publish(FACTORY, List.of("RMT_1", "RMT_2", "RMT_3"), USER_ID);

        assertThat(result.created()).containsExactly("M-A001", "M-A003");
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).materialCode()).isEqualTo("A002");
        assertThat(result.total()).isEqualTo(3);
    }

    @Test
    @DisplayName("工厂隔离: 不属于本厂的 id 记 failed, 绝不建档")
    void rejectsForeignMaterial() {
        when(rawMaterialTypeRepository.findByIdAndFactoryId("RMT_OTHER", FACTORY))
                .thenReturn(Optional.empty());

        MaterialSkuPublishService.Result result = service.publish(FACTORY, List.of("RMT_OTHER"), USER_ID);

        verify(productTypeService, never()).createProductType(any(), any());
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).reason()).contains("不属于本工厂");
    }

    /**
     * 「什么都没传」和「要全部」是两件事 —— 猜错的代价是凭空造出几百个 SKU。
     */
    @Test
    @DisplayName("空入参什么都不做 —— 绝不默认全量")
    void emptyInputPublishesNothing() {
        assertThat(service.publish(FACTORY, List.of(), USER_ID).total()).isZero();
        assertThat(service.publish(FACTORY, null, USER_ID).total()).isZero();
        verify(productTypeService, never()).createProductType(any(), any());
    }
}
