package com.cretas.aims.service.impl;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP4-A8: ProductType 税率换算单元测试.
 *
 * <p>验证三处换算:
 * <ol>
 *   <li>create — buildProductTypeEntity → applyTaxConversion (含税→未税)</li>
 *   <li>create — 传未税单价+税率 → 反算含税</li>
 *   <li>update — 传含税单价+新税率 → 重推导 unitPrice</li>
 * </ol>
 * <p>scale=4, HALF_UP 与 {@link TaxRate#preTaxPrice}/{@link TaxRate#withTaxPrice} 一致.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SP4-A8 ProductType 税率换算")
class ProductTypeTaxConversionTest {

    @Mock ProductTypeRepository productTypeRepository;
    @Mock CustomerRepository customerRepository;

    private ProductTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductTypeServiceImpl(productTypeRepository, new ObjectMapper(), customerRepository);
    }

    // ──────────────── TaxRate enum 换算验证 ────────────────

    @Test
    @DisplayName("TAX_9.preTaxPrice: 含税 109 → 未税 100.0000")
    void taxRate9_preTaxPrice() {
        BigDecimal result = TaxRate.TAX_9.preTaxPrice(new BigDecimal("109"));
        BigDecimal expected = new BigDecimal("100.0000");
        assertEquals(0, result.compareTo(expected), "preTaxPrice(109 @ 9%) should be 100.0000, got: " + result);
    }

    @Test
    @DisplayName("TAX_13.preTaxPrice: 含税 113 → 未税 100.0000")
    void taxRate13_preTaxPrice() {
        BigDecimal result = TaxRate.TAX_13.preTaxPrice(new BigDecimal("113"));
        BigDecimal expected = new BigDecimal("100.0000");
        assertEquals(0, result.compareTo(expected), "preTaxPrice(113 @ 13%) should be 100.0000, got: " + result);
    }

    @Test
    @DisplayName("TAX_9.withTaxPrice: 未税 100 → 含税 109.0000")
    void taxRate9_withTaxPrice() {
        BigDecimal result = TaxRate.TAX_9.withTaxPrice(new BigDecimal("100"));
        BigDecimal expected = new BigDecimal("109.0000");
        assertEquals(0, result.compareTo(expected), "withTaxPrice(100 @ 9%) should be 109.0000, got: " + result);
    }

    @Test
    @DisplayName("TAX_13.withTaxPrice: 未税 100 → 含税 113.0000")
    void taxRate13_withTaxPrice() {
        BigDecimal result = TaxRate.TAX_13.withTaxPrice(new BigDecimal("100"));
        BigDecimal expected = new BigDecimal("113.0000");
        assertEquals(0, result.compareTo(expected), "withTaxPrice(100 @ 13%) should be 113.0000, got: " + result);
    }

    @Test
    @DisplayName("preTaxPrice(null) → null (null 安全)")
    void taxRate_preTaxPrice_null() {
        assertNull(TaxRate.TAX_9.preTaxPrice(null));
        assertNull(TaxRate.TAX_13.preTaxPrice(null));
    }

    @Test
    @DisplayName("withTaxPrice(null) → null (null 安全)")
    void taxRate_withTaxPrice_null() {
        assertNull(TaxRate.TAX_9.withTaxPrice(null));
        assertNull(TaxRate.TAX_13.withTaxPrice(null));
    }

    // ──────────────── create: 含税→未税 ────────────────

    @Test
    @DisplayName("create: taxRate=TAX_9 + taxIncludedUnitPrice=109 → unitPrice=100.0000")
    void create_taxIncluded_to_preTax_9pct() {
        when(productTypeRepository.existsByFactoryIdAndCode(any(), any())).thenReturn(false);
        when(productTypeRepository.save(any(ProductType.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductTypeDTO dto = ProductTypeDTO.builder()
                .name("猪蹄")
                .unit("盒")
                .code("PT001")
                .taxRate(TaxRate.TAX_9)
                .taxIncludedUnitPrice(new BigDecimal("109"))
                .createdBy(1L)
                .build();

        ProductTypeDTO result = service.createProductType("F006", dto);

        assertNotNull(result);
        assertEquals(TaxRate.TAX_9, result.getTaxRate(), "taxRate should be persisted");
        assertEquals(0, new BigDecimal("109").compareTo(result.getTaxIncludedUnitPrice()),
                "taxIncludedUnitPrice should be 109");
        assertNotNull(result.getUnitPrice(), "unitPrice should be auto-derived");
        assertEquals(0, new BigDecimal("100.0000").compareTo(result.getUnitPrice()),
                "unitPrice should be 100.0000 (109/1.09), got: " + result.getUnitPrice());
    }

    @Test
    @DisplayName("create: taxRate=TAX_13 + taxIncludedUnitPrice=113 → unitPrice=100.0000")
    void create_taxIncluded_to_preTax_13pct() {
        when(productTypeRepository.existsByFactoryIdAndCode(any(), any())).thenReturn(false);
        when(productTypeRepository.save(any(ProductType.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductTypeDTO dto = ProductTypeDTO.builder()
                .name("猪舌")
                .unit("kg")
                .code("PT002")
                .taxRate(TaxRate.TAX_13)
                .taxIncludedUnitPrice(new BigDecimal("113"))
                .createdBy(1L)
                .build();

        ProductTypeDTO result = service.createProductType("F006", dto);

        assertNotNull(result.getUnitPrice());
        assertEquals(0, new BigDecimal("100.0000").compareTo(result.getUnitPrice()),
                "unitPrice should be 100.0000 (113/1.13), got: " + result.getUnitPrice());
    }

    @Test
    @DisplayName("create: taxRate=TAX_9 + unitPrice=100 (无含税) → 反算 taxIncludedUnitPrice=109.0000")
    void create_preTax_to_withTax_9pct() {
        when(productTypeRepository.existsByFactoryIdAndCode(any(), any())).thenReturn(false);
        when(productTypeRepository.save(any(ProductType.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductTypeDTO dto = ProductTypeDTO.builder()
                .name("卤猪手")
                .unit("kg")
                .code("PT003")
                .taxRate(TaxRate.TAX_9)
                .unitPrice(new BigDecimal("100"))
                .createdBy(1L)
                .build();

        ProductTypeDTO result = service.createProductType("F006", dto);

        assertNotNull(result.getTaxIncludedUnitPrice(), "taxIncludedUnitPrice should be auto-derived");
        assertEquals(0, new BigDecimal("109.0000").compareTo(result.getTaxIncludedUnitPrice()),
                "taxIncludedUnitPrice should be 109.0000 (100 * 1.09), got: " + result.getTaxIncludedUnitPrice());
    }

    @Test
    @DisplayName("create: taxRate=null + taxIncludedUnitPrice=200 → unitPrice 不改变 (无税率不推导)")
    void create_noTaxRate_noConversion() {
        when(productTypeRepository.existsByFactoryIdAndCode(any(), any())).thenReturn(false);
        when(productTypeRepository.save(any(ProductType.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductTypeDTO dto = ProductTypeDTO.builder()
                .name("调味品")
                .unit("包")
                .code("PT004")
                .taxRate(null)
                .taxIncludedUnitPrice(new BigDecimal("200"))
                .unitPrice(new BigDecimal("180"))
                .createdBy(1L)
                .build();

        ProductTypeDTO result = service.createProductType("F006", dto);

        // 无税率 → unitPrice 保持传入值 (180), 不被含税价覆盖
        assertEquals(0, new BigDecimal("180").compareTo(result.getUnitPrice()),
                "unitPrice should remain 180 (no tax rate, no conversion), got: " + result.getUnitPrice());
    }

    @Test
    @DisplayName("create: taxRate + taxIncludedUnitPrice=0 → unitPrice=0.0000 (零值不跳过)")
    void create_zeroTaxIncludedPrice_convertsCorrectly() {
        when(productTypeRepository.existsByFactoryIdAndCode(any(), any())).thenReturn(false);
        when(productTypeRepository.save(any(ProductType.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductTypeDTO dto = ProductTypeDTO.builder()
                .name("样品")
                .unit("个")
                .code("PT005")
                .taxRate(TaxRate.TAX_9)
                .taxIncludedUnitPrice(BigDecimal.ZERO)
                .createdBy(1L)
                .build();

        ProductTypeDTO result = service.createProductType("F006", dto);

        // BigDecimal.ZERO != null → 应换算 (0 / 1.09 = 0.0000)
        assertNotNull(result.getUnitPrice());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getUnitPrice()),
                "unitPrice(0 含税) should be 0, got: " + result.getUnitPrice());
    }

    // ──────────────── update: 新税率重推导 ────────────────

    @Test
    @DisplayName("update: 传新 taxRate + taxIncludedUnitPrice → 重推导 unitPrice")
    void update_newTaxRate_rederivesUnitPrice() {
        ProductType existing = new ProductType();
        existing.setId("pt-update-001");
        existing.setFactoryId("F006");
        existing.setCode("PT-U01");
        existing.setName("猪蹄旧");
        existing.setUnit("盒");
        existing.setCreatedBy(1L);
        existing.setTaxRate(TaxRate.TAX_9);
        existing.setUnitPrice(new BigDecimal("100.0000"));
        existing.setTaxIncludedUnitPrice(new BigDecimal("109.0000"));

        when(productTypeRepository.findById("pt-update-001")).thenReturn(Optional.of(existing));
        when(productTypeRepository.save(any(ProductType.class))).thenAnswer(inv -> inv.getArgument(0));

        // 更新为 13% + 含税 226
        ProductTypeDTO dto = ProductTypeDTO.builder()
                .taxRate(TaxRate.TAX_13)
                .taxIncludedUnitPrice(new BigDecimal("226"))
                .build();

        ProductTypeDTO result = service.updateProductType("F006", "pt-update-001", dto);

        assertNotNull(result.getUnitPrice(), "unitPrice should be re-derived after rate change");
        // 226 / 1.13 = 200.0000
        assertEquals(0, new BigDecimal("200.0000").compareTo(result.getUnitPrice()),
                "unitPrice(226/1.13) should be 200.0000, got: " + result.getUnitPrice());
    }
}
