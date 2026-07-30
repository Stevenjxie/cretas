package com.cretas.aims.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单据追踪端点的权限必须与**锚点单据自己的详情 GET** 逐字相同。
 *
 * <p>追踪抽屉就开在那张详情页里, 所以「能打开这张单」必须等价于「能看这张单的追踪」:</p>
 * <ul>
 *   <li>比详情**窄** → 页面打得开、抽屉一开就 403 (只对部分角色发生, 极难被发现);</li>
 *   <li>比详情**宽** → 看不到这张单的人反而能通过追踪读到它的单号与关联链, 是越权。</li>
 * </ul>
 *
 * <p>🔴 这条测试是因为真踩到才加的: 采购追踪端点最初只给了
 * {@code {"procurement:read_write","procurement:read"}} —— 抄自 {@code PurchaseController#listOrders}
 * (行 113 的**列表**接口), 而真正的详情 {@code #getOrder} 还带 {@code warehouse:*}
 * (仓库员选中待收货采购单后, RN 的 WHReceiptCreateScreen 用它拉明细预填收货行)。
 * 结果就是仓库角色打得开采购单详情、却看不了页内的追踪抽屉。</p>
 *
 * <p>权限本身是 DB 驱动的 ({@code PermissionService}), 代码里没有可对照的静态矩阵, 所以这里
 * 只能拿"注解 ↔ 注解"作契约 —— 但这恰好就是我们要守的不变量。</p>
 */
class BusinessDocumentTracePermissionContractTest {

    private static final Path JAVA_ROOT = Paths.get("src/main/java/com/cretas/aims/controller");

    /** {@code @RequirePermission({...})} 里的权限字符串。 */
    private static final Pattern PERMISSIONS = Pattern.compile("@RequirePermission\\s*\\(\\s*\\{([^}]*)\\}");
    private static final Pattern LITERAL = Pattern.compile("\"([^\"]+)\"");

    private record Case(String label, String traceMethod, String anchorFile, String anchorMethod) {
    }

    private static final List<Case> CASES = List.of(
            new Case("销售订单", "traceSalesOrder", "inventory/SalesController.java", "getOrder"),
            new Case("采购订单", "tracePurchaseOrder", "inventory/PurchaseController.java", "getOrder"),
            new Case("调拨单", "traceInternalTransfer", "inventory/TransferController.java", "getTransfer"));

    @Test
    void traceEndpointsRequireExactlyWhatTheirAnchorDetailEndpointRequires() throws IOException {
        String traceSource = read("BusinessDocumentTraceController.java");

        for (Case c : CASES) {
            Set<String> trace = permissionsBefore(traceSource, c.traceMethod());
            Set<String> anchor = permissionsBefore(read(c.anchorFile()), c.anchorMethod());

            assertTrue(trace.size() >= 2,
                    c.label() + ": 追踪端点的权限没解析出来 (拿到 " + trace + ") —— "
                            + "多半是本测试的解析器坏了, 空集会让断言假绿");
            assertTrue(anchor.size() >= 2,
                    c.label() + ": 锚点详情 " + c.anchorFile() + "#" + c.anchorMethod()
                            + " 的权限没解析出来 (拿到 " + anchor + ")");

            assertEquals(anchor, trace, () -> String.format(
                    "%s 的追踪端点权限与详情端点不一致。%n"
                            + "  详情 %s#%s : %s%n"
                            + "  追踪端点        : %s%n"
                            + "  → 窄了会「页面打得开、抽屉一开就 403」, 宽了是越权。"
                            + "改注解请以**详情**接口为准, 不要抄同一个 Controller 里的列表接口。",
                    c.label(), c.anchorFile(), c.anchorMethod(), anchor, trace));
        }
    }

    @Test
    void purchaseTraceKeepsWarehouseAccessThatTheDetailEndpointGrants() throws IOException {
        Set<String> trace = permissionsBefore(read("BusinessDocumentTraceController.java"), "tracePurchaseOrder");
        assertTrue(trace.contains("warehouse:read") && trace.contains("warehouse:read_write"),
                "采购追踪缺 warehouse:* —— 仓库员打得开采购单详情 (收货流程必经), "
                        + "抽屉却会 403。这正是本测试要挡的那次真实缺陷。");
    }

    /** 取 {@code methodName} 之前最近的一处 {@code @RequirePermission} 的权限集合。 */
    private static Set<String> permissionsBefore(String source, String methodName) {
        int method = source.indexOf(" " + methodName + "(");
        assertTrue(method > 0, "找不到方法 " + methodName + " —— 方法被改名了, 请同步本测试");

        Matcher m = PERMISSIONS.matcher(source);
        String last = null;
        while (m.find()) {
            if (m.start() > method) {
                break;
            }
            last = m.group(1);
        }
        Set<String> out = new LinkedHashSet<>();
        if (last != null) {
            Matcher lit = LITERAL.matcher(last);
            while (lit.find()) {
                out.add(lit.group(1));
            }
        }
        return out;
    }

    private static String read(String relative) throws IOException {
        Path path = JAVA_ROOT.resolve(relative);
        assertTrue(Files.exists(path), "找不到 " + path + " —— controller 被移动或改名了, 请同步本测试的路径");
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
