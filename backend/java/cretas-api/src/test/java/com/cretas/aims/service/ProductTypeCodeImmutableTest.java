package com.cretas.aims.service;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.impl.ProductTypeServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-24: 产品编码（productCode）建立后不可变 — 缺口修复测试.
 *
 * <p>覆盖以下 4 条行为断言：
 * <ol>
 *   <li>update 请求携带<b>不同</b>编码 → 409 BusinessException，拒绝修改，不写库。</li>
 *   <li>update 请求携带<b>相同</b>编码（值不变）→ 放行，不报错，save 被调用。</li>
 *   <li>update 请求<b>不携带</b>编码（dto.code == null）→ 放行，不报错，save 被调用。</li>
 *   <li>create 自动生成编码路径 → 正常生成并保存（不误伤 create/regeneration 路径）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("A-24 — 产品编码不可变性")
class ProductTypeCodeImmutableTest {

    @Mock
    private ProductTypeRepository productTypeRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ProductTypeServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_ID = "prod-001";
    private static final String EXISTING_CODE = "CPDD0001";

    /** 模拟数据库中已存在的 ProductType 实体（编码 CPDD0001）。 */
    private ProductType existingEntity() {
        ProductType pt = new ProductType();
        pt.setId(PRODUCT_ID);
        pt.setFactoryId(FACTORY_ID);
        pt.setCode(EXISTING_CODE);
        pt.setName("猪舌卤味");
        pt.setIsActive(true);
        return pt;
    }

    @BeforeEach
    void stubSave() {
        // lenient: 409 路径不调 save，避免 UnnecessaryStubbingException
        lenient().when(productTypeRepository.save(any(ProductType.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // -----------------------------------------------------------------------
    // 场景 1: update 带不同编码 → 409 拒绝
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("update 携带不同编码 → 409 BusinessException（编码不可变）")
    void update_withDifferentCode_throws409() {
        when(productTypeRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(existingEntity()));

        ProductTypeDTO dto = new ProductTypeDTO();
        dto.setCode("CPDD9999"); // 与现值 CPDD0001 不同
        dto.setName("猪舌新名称");

        assertThatThrownBy(() -> service.updateProductType(FACTORY_ID, PRODUCT_ID, dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getMessage()).contains("不可修改");
                    assertThat(be.getMessage()).contains(EXISTING_CODE);
                });

        // 不应写库
        verify(productTypeRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // 场景 2: update 携带相同编码（无变更）→ 放行
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("update 携带相同编码（值不变）→ 放行，save 被调用")
    void update_withSameCode_passesThrough() {
        when(productTypeRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(existingEntity()));

        ProductTypeDTO dto = new ProductTypeDTO();
        dto.setCode(EXISTING_CODE); // 与现值相同
        dto.setName("猪舌卤味（更新备注）");

        // 不应抛异常
        service.updateProductType(FACTORY_ID, PRODUCT_ID, dto);

        verify(productTypeRepository).save(any(ProductType.class));
    }

    // -----------------------------------------------------------------------
    // 场景 3: update 不携带编码（dto.code == null）→ 放行
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("update 不携带编码（code == null）→ 放行，save 被调用")
    void update_withNullCode_passesThrough() {
        when(productTypeRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(existingEntity()));

        ProductTypeDTO dto = new ProductTypeDTO();
        dto.setCode(null); // 前端省略 code 字段
        dto.setName("猪舌卤味（新备注）");

        // 不应抛异常
        service.updateProductType(FACTORY_ID, PRODUCT_ID, dto);

        verify(productTypeRepository).save(any(ProductType.class));
    }

    // -----------------------------------------------------------------------
    // 场景 4: create 自动生成编码路径 → 正常生成，不误伤
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("create 自动生成编码路径 → 正常生成保存，不被不可变检查误伤")
    void create_autoGeneratedCode_notAffected() {
        // 自动生成需要 countByFactoryId；save 已由 @BeforeEach lenient 桩覆盖
        // existsByFactoryIdAndCode 仅在手输编号时调用，自动生成路径不触达
        when(productTypeRepository.countByFactoryId(FACTORY_ID)).thenReturn(0L);

        ProductTypeDTO dto = new ProductTypeDTO();
        dto.setCode(null); // 触发自动生成
        dto.setName("掌中宝");
        dto.setCategory("FINISHED_PRODUCT");

        // 不应抛异常
        ProductTypeDTO result = service.createProductType(FACTORY_ID, dto);

        assertThat(result.getCode()).isNotNull().isNotBlank();
        verify(productTypeRepository).save(any(ProductType.class));
    }
}
