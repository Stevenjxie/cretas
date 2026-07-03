package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
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

    @Test
    @DisplayName("Kingdee import template endpoint mirrors voucher export finance write gate")
    void kingdeeImportTemplateEndpoint_hasExpectedRouteAndFinanceGate() throws Exception {
        Method method = VoucherExportController.class.getMethod(
                "exportKingdeeImportTemplate",
                String.class,
                String.class,
                String.class,
                VoucherTargetSystem.class,
                jakarta.servlet.http.HttpServletRequest.class,
                jakarta.servlet.http.HttpServletResponse.class);

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
        Method method = VoucherExportController.class.getMethod(
                "exportKingdeeImportTemplate",
                String.class,
                String.class,
                String.class,
                VoucherTargetSystem.class,
                jakarta.servlet.http.HttpServletRequest.class,
                jakarta.servlet.http.HttpServletResponse.class);

        Parameter[] params = method.getParameters();
        // index 3 = targetSystem (factoryId, startDate, endDate, targetSystem, request, response)
        RequestParam requestParam = params[3].getAnnotation(RequestParam.class);
        assertNotNull(requestParam, "targetSystem must be a @RequestParam so KIS/K3 can opt in via query string");
        assertEquals("KINGDEE_YXSKY", requestParam.defaultValue(),
                "default must stay KINGDEE_YXSKY so existing callers without targetSystem are unaffected");
    }
}
