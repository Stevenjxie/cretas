package com.cretas.aims.controller.finance;

import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * VoucherController#findByBusiness — GET /by-business/{type}/{id} 路由测试。
 *
 * <p>回归测试: 修复 {@code Map.of("success", true, "data", null, "message", "未生成凭证")}
 * 在"未找到"分支必现 NPE (java.util.Map.of 对 null value 直接抛异常) 的 bug —
 * 该分支是幂等检查的常态路径 (前端每次打开详情页都会 first-check), 之前必 500。
 *
 * <p>策略: standaloneSetup 绕过 @RequirePermission 拦截器 (与 ProcessSheetControllerTest 一致)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VoucherController#findByBusiness — by-business 查询路由测试")
class VoucherControllerTest {

    @Mock
    private VoucherService voucherService;

    @Mock
    private VoucherRepository voucherRepo;

    @Mock
    private VoucherEntryRepository voucherEntryRepo;

    private MockMvc mockMvc;

    private static final String FACTORY_ID = "F006";
    private static final String OTHER_FACTORY_ID = "F999";
    private static final String BUSINESS_TYPE = "SALES_ORDER";
    private static final String BUSINESS_ID = "SO-0001";

    private static String url(String factoryId) {
        return "/api/mobile/" + factoryId + "/finance/vouchers/by-business/" + BUSINESS_TYPE + "/" + BUSINESS_ID;
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new VoucherController(voucherService, voucherRepo, voucherEntryRepo))
                .build();
    }

    @Test
    @DisplayName("凭证不存在 → 200 success:true data:null (不是 500 NPE)")
    void findByBusiness_notFound_returns200NotNullData_not500() throws Exception {
        when(voucherService.findBySourceBusiness(BUSINESS_TYPE, BUSINESS_ID))
                .thenReturn(Optional.empty());

        mockMvc.perform(get(url(FACTORY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("未生成凭证"));
    }

    @Test
    @DisplayName("凭证存在但属于别厂 (跨租户) → 视作未生成, 不泄漏别厂凭证存在性, 200 不 500")
    void findByBusiness_crossTenant_treatedAsNotFound_returns200() throws Exception {
        Voucher other = new Voucher();
        other.setId("V-1");
        other.setFactoryId(OTHER_FACTORY_ID);
        other.setVoucherNumber("V-2026-0001");
        other.setVoucherType(VoucherType.SALES_RECEIPT);
        other.setVoucherDate(LocalDate.now());
        other.setStatus(VoucherStatus.DRAFT);
        other.setTotalDebit(BigDecimal.TEN);
        other.setTotalCredit(BigDecimal.TEN);

        when(voucherService.findBySourceBusiness(BUSINESS_TYPE, BUSINESS_ID))
                .thenReturn(Optional.of(other));

        mockMvc.perform(get(url(FACTORY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("未生成凭证"));
    }

    @Test
    @DisplayName("凭证已存在且属于当前工厂 → 200 返回凭证数据")
    void findByBusiness_found_returnsVoucher() throws Exception {
        Voucher v = new Voucher();
        v.setId("V-1");
        v.setFactoryId(FACTORY_ID);
        v.setVoucherNumber("V-2026-0001");
        v.setVoucherType(VoucherType.SALES_RECEIPT);
        v.setVoucherDate(LocalDate.now());
        v.setStatus(VoucherStatus.DRAFT);
        v.setTotalDebit(BigDecimal.TEN);
        v.setTotalCredit(BigDecimal.TEN);

        when(voucherService.findBySourceBusiness(BUSINESS_TYPE, BUSINESS_ID))
                .thenReturn(Optional.of(v));

        mockMvc.perform(get(url(FACTORY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("V-1"))
                .andExpect(jsonPath("$.data.voucherNumber").value("V-2026-0001"));
    }
}
