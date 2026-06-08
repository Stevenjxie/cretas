package com.cretas.aims.service;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.dto.producttype.ProductTypeSuggestionDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.impl.ProductTypeServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T149: SKU 智能防呆填充 + 编号去重健壮性.
 *
 * <p>覆盖:
 * <ul>
 *   <li>Part A — 自动生成编号撞唯一约束 → 重新生成重试; 手输重复编号 → 友好 409 不重试;</li>
 *   <li>Part B — suggestDefaults 名称匹配返回历史产品属性 / 无匹配→关键词大类+其余 null / 完全无把握→全 null.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductType — T149 智能填充 + 编号去重健壮性")
class ProductTypeSmartFillTest {

    @Mock
    private ProductTypeRepository productTypeRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ProductTypeServiceImpl service;

    // ==================== Part A: 编号去重健壮性 ====================

    @Test
    @DisplayName("A1: 自动生成编号撞唯一约束 → 重新生成下一序号重试成功")
    void create_generatedCodeCollision_retriesAndSucceeds() {
        ProductTypeDTO dto = baseDto();
        dto.setCode(null); // 自动生成
        dto.setProductCategory("FINISHED_PRODUCT");

        // 第一次 count=12 → CP...0013, 撞约束; 第二次 count=13 → CP...0014, 成功
        when(productTypeRepository.countByFactoryId("F001")).thenReturn(12L, 13L);
        when(productTypeRepository.save(any(ProductType.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate code"))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductTypeDTO result = service.createProductType("F001", dto);

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo("CPF0010014"); // 重试后的新序号
        verify(productTypeRepository, times(2)).save(any(ProductType.class));
    }

    @Test
    @DisplayName("A2: 自动生成编号一直撞约束 → 重试上限后抛 409 (不裸 500)")
    void create_generatedCodeAlwaysCollides_throws409AfterRetries() {
        ProductTypeDTO dto = baseDto();
        dto.setCode(null);
        dto.setProductCategory("FINISHED_PRODUCT");

        when(productTypeRepository.countByFactoryId("F001")).thenReturn(0L);
        when(productTypeRepository.save(any(ProductType.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate code"));

        assertThatThrownBy(() -> service.createProductType("F001", dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
    }

    @Test
    @DisplayName("A3: 手输重复编号 → 提前检查 → 友好 409, 不调用 save (不重试)")
    void create_manualDuplicateCode_friendly409NoSave() {
        ProductTypeDTO dto = baseDto();
        dto.setCode("CP-MANUAL-001"); // 用户手输

        when(productTypeRepository.existsByFactoryIdAndCode("F001", "CP-MANUAL-001")).thenReturn(true);

        assertThatThrownBy(() -> service.createProductType("F001", dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("产品编号已存在");
                });
        verify(productTypeRepository, times(0)).save(any(ProductType.class));
    }

    @Test
    @DisplayName("A4: 手输编号 pre-check 通过但 insert 撞并发约束 → 翻译为友好 409 (不重试)")
    void create_manualCode_raceOnInsert_translatesTo409() {
        ProductTypeDTO dto = baseDto();
        dto.setCode("CP-MANUAL-002");

        when(productTypeRepository.existsByFactoryIdAndCode("F001", "CP-MANUAL-002")).thenReturn(false);
        when(productTypeRepository.save(any(ProductType.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate code"));

        assertThatThrownBy(() -> service.createProductType("F001", dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
        // 手输不重试: 只 save 一次
        verify(productTypeRepository, times(1)).save(any(ProductType.class));
    }

    // ==================== Part B: suggestDefaults 历史记忆建议 ====================

    @Test
    @DisplayName("B1: 名称相似匹配历史产品 → 返回其 大类/单位/一级单位/装箱系数 + matchedFrom")
    void suggest_nameMatch_returnsMatchedProductAttrs() {
        ProductType existing = new ProductType();
        existing.setName("叮咚好食光卤猪蹄 200g");
        existing.setBaseProductName("好食光卤猪蹄");
        existing.setProductCategory("FINISHED_PRODUCT");
        existing.setUnit("盒");
        existing.setLevel1Unit("筐");
        existing.setBoxConversionCoefficient(new BigDecimal("20"));

        when(productTypeRepository.findByFactoryId("F001")).thenReturn(List.of(existing));

        ProductTypeSuggestionDTO s = service.suggestDefaults("F001", "好食光卤猪蹄 500g", null);

        assertThat(s.getProductCategory()).isEqualTo("FINISHED_PRODUCT");
        assertThat(s.getUnit()).isEqualTo("盒");
        assertThat(s.getLevel1Unit()).isEqualTo("筐");
        assertThat(s.getBoxConversionCoefficient()).isEqualByComparingTo("20");
        assertThat(s.getMatchedFrom()).isEqualTo("叮咚好食光卤猪蹄 200g");
    }

    @Test
    @DisplayName("B2: 名称无历史匹配 → 仅按关键词推断大类, 单位等留 null (禁假数据)")
    void suggest_noNameMatch_keywordCategoryOnly() {
        // 库里有一个完全不相干的产品 → 不会匹配
        ProductType unrelated = new ProductType();
        unrelated.setName("吸塑包装盒");
        unrelated.setProductCategory("PACKAGING");
        when(productTypeRepository.findByFactoryId("F001")).thenReturn(List.of(unrelated));

        // "卤掌中宝" 关键词 → 成品, 但库里无相似产品名
        ProductTypeSuggestionDTO s = service.suggestDefaults("F001", "卤掌中宝", null);

        assertThat(s.getProductCategory()).isEqualTo("FINISHED_PRODUCT"); // 关键词推断
        assertThat(s.getUnit()).isNull();                                 // 无名称匹配 → null
        assertThat(s.getLevel1Unit()).isNull();
        assertThat(s.getBoxConversionCoefficient()).isNull();
        assertThat(s.getMatchedFrom()).isNull();                          // 非名称匹配 → null
    }

    @Test
    @DisplayName("B3: 名称无匹配且关键词无把握 → 全 null (不臆造)")
    void suggest_noMatchNoKeyword_allNull() {
        when(productTypeRepository.findByFactoryId("F001")).thenReturn(List.of());

        ProductTypeSuggestionDTO s = service.suggestDefaults("F001", "某种不知名的东西XYZ", null);

        assertThat(s.getProductCategory()).isNull();
        assertThat(s.getUnit()).isNull();
        assertThat(s.getLevel1Unit()).isNull();
        assertThat(s.getBoxConversionCoefficient()).isNull();
        assertThat(s.getMatchedFrom()).isNull();
    }

    @Test
    @DisplayName("B4: 空名称 → 全 null, 不查库")
    void suggest_emptyName_allNull() {
        ProductTypeSuggestionDTO s = service.suggestDefaults("F001", "  ", null);

        assertThat(s.getProductCategory()).isNull();
        assertThat(s.getMatchedFrom()).isNull();
        verify(productTypeRepository, times(0)).findByFactoryId(any());
    }

    @Test
    @DisplayName("B5: 关键词包辅材优先于成品 (吸塑包装盒 → PACKAGING 非误判成品)")
    void suggest_packagingKeyword_wins() {
        when(productTypeRepository.findByFactoryId("F001")).thenReturn(List.of());

        ProductTypeSuggestionDTO s = service.suggestDefaults("F001", "吸塑包装盒", null);

        assertThat(s.getProductCategory()).isEqualTo("PACKAGING");
        assertThat(s.getMatchedFrom()).isNull();
    }

    // ==================== helpers ====================

    private ProductTypeDTO baseDto() {
        ProductTypeDTO dto = ProductTypeDTO.builder()
                .name("测试产品")
                .unit("盒")
                .build();
        // lenient: 不是每个 Part A 用例都解析客户名
        lenient().when(customerRepository.findByIdAndFactoryId(any(), any()))
                .thenReturn(java.util.Optional.empty());
        return dto;
    }
}
