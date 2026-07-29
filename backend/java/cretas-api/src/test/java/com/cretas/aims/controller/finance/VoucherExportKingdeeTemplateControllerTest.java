package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.enums.VoucherExportFileFormat;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoucherExportKingdeeTemplateControllerTest {

    /**
     * Signature as of {@code 9b353aeb6f "feat(finance): 金蝶凭证导出全格式覆盖" (#1203)},
     * which inserted the {@code format} ({@link VoucherExportFileFormat}) parameter
     * after {@code targetSystem} so KIS/K3 can request .xls/.dbf. Resolved once so a
     * future signature drift fails one lookup instead of every test.
     */
    private static Method endpoint() throws Exception {
        return VoucherExportController.class.getMethod(
                "exportKingdeeImportTemplate",
                String.class,                  // 0 factoryId
                String.class,                  // 1 startDate
                String.class,                  // 2 endDate
                VoucherTargetSystem.class,     // 3 targetSystem
                VoucherExportFileFormat.class, // 4 format
                jakarta.servlet.http.HttpServletRequest.class,
                jakarta.servlet.http.HttpServletResponse.class);
    }

    @Test
    @DisplayName("Kingdee import template endpoint mirrors voucher export finance write gate")
    void kingdeeImportTemplateEndpoint_hasExpectedRouteAndFinanceGate() throws Exception {
        Method method = endpoint();

        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping, "endpoint must be a GET download");
        assertTrue(Arrays.asList(mapping.value()).contains("/voucher-import-template"));

        RequireModule module = method.getAnnotation(RequireModule.class);
        assertNotNull(module, "endpoint must require finance module");
        assertEquals("finance", module.value());

        RequirePermission methodGate = method.getAnnotation(RequirePermission.class);
        assertNotNull(methodGate, "endpoint must explicitly keep the finance write export gate");
        assertArrayEquals(new String[]{"finance:read_write"}, methodGate.value());
    }

    @Test
    @DisplayName("targetSystem param defaults to KINGDEE_YXSKY — backward-compat for existing callers")
    void kingdeeImportTemplateEndpoint_targetSystemDefaultsToYxsky() throws Exception {
        Parameter[] params = endpoint().getParameters();
        // index 3 = targetSystem (factoryId, startDate, endDate, targetSystem, format, request, response)
        RequestParam requestParam = params[3].getAnnotation(RequestParam.class);
        assertNotNull(requestParam, "targetSystem must be a @RequestParam so KIS/K3 can opt in via query string");
        assertEquals("KINGDEE_YXSKY", requestParam.defaultValue(),
                "default must stay KINGDEE_YXSKY so existing callers without targetSystem are unaffected");
    }

    @Test
    @DisplayName("format param defaults to XLSX — 云星空 callers unaffected by the KIS .xls/.dbf addition")
    void kingdeeImportTemplateEndpoint_formatDefaultsToXlsx() throws Exception {
        Parameter[] params = endpoint().getParameters();
        // index 4 = format, added by #1203 (金蝶凭证导出全格式覆盖)
        RequestParam requestParam = params[4].getAnnotation(RequestParam.class);
        assertNotNull(requestParam, "format must be a @RequestParam so KIS can opt into .xls/.dbf");
        assertEquals("XLSX", requestParam.defaultValue(),
                "default must stay XLSX so pre-#1203 callers keep getting the 云星空 workbook");
    }
}
