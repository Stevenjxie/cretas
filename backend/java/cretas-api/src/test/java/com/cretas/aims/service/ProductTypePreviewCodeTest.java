package com.cretas.aims.service;

import com.cretas.aims.entity.Customer;
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

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * T147 Fix2: 验证 ProductTypeServiceImpl.previewGeneratedCode 复用与 createProductType
 * 一致的编号生成逻辑 (前缀 + 客户首字母 + 4位序号), 且为只读 (无持久化副作用).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductType — T147 previewGeneratedCode 编号预览")
class ProductTypePreviewCodeTest {

    @Mock
    private ProductTypeRepository productTypeRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ProductTypeServiceImpl service;

    @Test
    @DisplayName("成品 + customerId 解析客户名 → CP + 客户首字母 + 序号 (CPDD0013)")
    void preview_finishedProduct_withCustomerId() {
        when(productTypeRepository.findCodesByFactoryIdAndGeneratedPrefix("F001", "CPDD"))
                .thenReturn(List.of("CPDD0012"));
        Customer c = new Customer();
        c.setId("cust-1");
        c.setName("大东"); // D D
        when(customerRepository.findByIdAndFactoryId("cust-1", "F001")).thenReturn(Optional.of(c));

        String code = service.previewGeneratedCode("F001", "FINISHED_PRODUCT", "cust-1", null);

        assertThat(code).isEqualTo("CPDD0013");
    }

    @Test
    @DisplayName("customerId 解析不到 → 回退到传入的 relatedCustomer 名")
    void preview_customerIdNotFound_fallbackToName() {
        when(productTypeRepository.findCodesByFactoryIdAndGeneratedPrefix("F001", "CPDD"))
                .thenReturn(List.of());
        when(customerRepository.findByIdAndFactoryId("missing", "F001")).thenReturn(Optional.empty());

        String code = service.previewGeneratedCode("F001", "FINISHED_PRODUCT", "missing", "大东");

        assertThat(code).isEqualTo("CPDD0001");
    }

    @Test
    @DisplayName("仅传 relatedCustomer 名 (无 customerId) → 直接用名取首字母")
    void preview_relatedCustomerOnly() {
        when(productTypeRepository.findCodesByFactoryIdAndGeneratedPrefix("F001", "CPDD"))
                .thenReturn(List.of("CPDD0004"));

        String code = service.previewGeneratedCode("F001", "FINISHED_PRODUCT", null, "大东");

        assertThat(code).isEqualTo("CPDD0005");
    }

    @Test
    @DisplayName("无客户 → 用 factoryId 作首字母段后备 (与 createProductType 一致)")
    void preview_noCustomer_usesFactoryIdFallback() {
        when(productTypeRepository.findCodesByFactoryIdAndGeneratedPrefix("F001", "CPF001"))
                .thenReturn(List.of());

        String code = service.previewGeneratedCode("F001", "FINISHED_PRODUCT", null, null);

        // getCustomerInitials(null, "F001") → "F001"
        assertThat(code).isEqualTo("CPF0010001");
    }

    @Test
    @DisplayName("不同大类映射不同前缀 — 原料 YL / 包辅材 BF / 调味品 TW / 客户自带 KH")
    void preview_categoryPrefixMapping() {
        lenient().when(productTypeRepository.findCodesByFactoryIdAndGeneratedPrefix(
                org.mockito.ArgumentMatchers.eq("F001"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of());
        Customer c = new Customer();
        c.setId("cust-1");
        c.setName("大东");
        lenient().when(customerRepository.findByIdAndFactoryId("cust-1", "F001")).thenReturn(Optional.of(c));

        assertThat(service.previewGeneratedCode("F001", "RAW_MATERIAL", "cust-1", null)).isEqualTo("YLDD0001");
        assertThat(service.previewGeneratedCode("F001", "PACKAGING", "cust-1", null)).isEqualTo("BFDD0001");
        assertThat(service.previewGeneratedCode("F001", "SEASONING", "cust-1", null)).isEqualTo("TWDD0001");
        assertThat(service.previewGeneratedCode("F001", "CUSTOMER_MATERIAL", "cust-1", null)).isEqualTo("KHDD0001");
    }

    @Test
    @DisplayName("未知/空大类 → 前缀 PT (与 getCategoryPrefix 默认一致)")
    void preview_unknownCategory_defaultPrefix() {
        when(productTypeRepository.findCodesByFactoryIdAndGeneratedPrefix("F001", "PTDD"))
                .thenReturn(List.of());

        String code = service.previewGeneratedCode("F001", null, null, "大东");

        assertThat(code).isEqualTo("PTDD0001");
    }

    @Test
    @DisplayName("手工保存 CPF0060149 后，同前缀预览返回 CPF0060150")
    void preview_usesMaxNumericSuffixWithinCurrentPrefix() {
        when(productTypeRepository.findCodesByFactoryIdAndGeneratedPrefix("F006", "CPF006"))
                .thenReturn(List.of("CPF0060002", "CPF0060149", "CPF006LEGACY"));

        String code = service.previewGeneratedCode("F006", "FINISHED_PRODUCT", null, null);

        assertThat(code).isEqualTo("CPF0060150");
    }
}
