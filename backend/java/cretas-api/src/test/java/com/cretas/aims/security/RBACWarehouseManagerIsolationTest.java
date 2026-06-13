package com.cretas.aims.security;

import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C-RBAC-1 Day 10-11 — Warehouse manager isolation 静态审计.
 *
 * <p>客户原话 (六扇门第三次 May7 part2 行 188-189):
 * <blockquote>"其他的话就尽量少让那个仓管员去参与什么什么价格类的不要让他们去参与"
 *  "做仓管的他年纪都比较大文化素质很低的, 你不能太伪赖他们"</blockquote>
 *
 * <p>本测试是 PR #423 RBAC 框架 (PriceFieldResponseAdvice +
 * PriceSensitive 注解) 的 invariant test — 验证已知所有价格字段都被
 * 正确标注. 防止新加字段时漏注解 (常见 regression).
 *
 * <p>测试策略: 反射枚举关键 entity / DTO 字段, 凡名字含 price / amount /
 * cost / total / payable / receivable / discount / tax 关键词的, 必须
 * 标注 {@link PriceSensitive}, 否则 fail.
 *
 * @author Cretas Team — Track C
 * @since 2026-05-15
 */
@DisplayName("C-RBAC-1 — Warehouse manager 价格字段隔离静态审计")
class RBACWarehouseManagerIsolationTest {

    /**
     * 字段名包含以下任一 substring (lowercase) → 视为价格字段, 必须 @PriceSensitive.
     * 单价 / 总价 / 应付 / 应收 / 折扣 / 税 等英文常见命名.
     */
    private static final List<String> PRICE_FIELD_KEYWORDS = List.of(
            "price",        // unitPrice / standardPrice / movingAvgPrice
            "amount",       // totalAmount / discountAmount / taxAmount
            "cost",         // unitCost / materialCost / laborCost
            "payable",      // payableAmount
            "receivable",   // receivableAmount
            "discount",     // discountRate (NOT a price; opt-in: see ALLOWLIST below)
            "subtotal"      // 行小计
    );

    /**
     * **白名单** — 字段名匹配关键词但语义不是"价格" (例如布尔标志 / ratio).
     * 单测时显式跳过, 避免误报. 添加须有 inline 注释说明业务理由.
     */
    private static final Set<String> ALLOWLIST = Set.of(
            // 折扣率 (百分比, 不是钱) — 不需要 mask
            "discountRate",
            // 税率 (百分比, 不是钱)
            "taxRate",
            // 是否价格敏感 entity-level boolean 标志, PR #423 之前/之后都不是钱
            "priceSensitive",
            // Sales-order warning messages only surface rule text; numeric price fields stay separately masked.
            "priceWarnings"
    );

    /**
     * 待审计的 entity / DTO 类清单. 添加新业务实体时在此追加.
     * 静态白名单 — 比 classpath 自动扫描更可控, 避免引入无关的 LessonLearned 类.
     */
    private static final List<Class<?>> CLASSES_UNDER_AUDIT = List.of(
            MaterialBatch.class,
            MaterialBatchDTO.class,
            PurchaseOrder.class,
            PurchaseOrderItem.class,
            PurchaseReceiveRecord.class,
            PurchaseReceiveItem.class,
            SalesOrder.class,
            SalesOrderItem.class
    );

    @Nested
    @DisplayName("已知 entity/DTO 价格字段必须 @PriceSensitive")
    class FieldAuditTests {

        @Test
        @DisplayName("✅ 所有 PRICE_FIELD_KEYWORDS 命中字段 (除白名单) 都 @PriceSensitive")
        void allKnownPriceFieldsAreAnnotated() {
            List<String> violations = new ArrayList<>();

            for (Class<?> cls : CLASSES_UNDER_AUDIT) {
                for (Field f : getAllFieldsIncludingInherited(cls)) {
                    String name = f.getName();
                    String lower = name.toLowerCase();
                    if (ALLOWLIST.contains(name)) continue;
                    boolean keywordMatch = PRICE_FIELD_KEYWORDS.stream().anyMatch(lower::contains);
                    if (!keywordMatch) continue;

                    if (!f.isAnnotationPresent(PriceSensitive.class)) {
                        violations.add(String.format("%s.%s — 命中价格关键词但缺 @PriceSensitive",
                                cls.getSimpleName(), name));
                    }
                }
            }

            if (!violations.isEmpty()) {
                fail("Day 10-11 RBAC 审计发现 " + violations.size() + " 个未注解的价格字段:\n  - "
                        + String.join("\n  - ", violations) + "\n"
                        + "解决方案: 在字段上加 @PriceSensitive, 或加入 ALLOWLIST (并注释业务理由).");
            }
        }

        @Test
        @DisplayName("✅ 至少 1 个字段被审计 — 防 CLASSES_UNDER_AUDIT 配置错")
        void auditScopeNonEmpty() {
            int totalFields = CLASSES_UNDER_AUDIT.stream()
                    .mapToInt(c -> getAllFieldsIncludingInherited(c).size())
                    .sum();
            assertTrue(totalFields > 50,
                    "审计范围太窄 (只 " + totalFields + " 字段) — 检查 CLASSES_UNDER_AUDIT 是否有效引用");
        }

        @Test
        @DisplayName("✅ @Transient computed getter (如 getPayableAmount) 含 @PriceSensitive (P0 fix 2026-05-12 verify)")
        void transientPriceGettersAreAnnotated() {
            // 历史 bug (PR #449): computed getters (e.g. PurchaseOrder.getPayableAmount())
            // 是 @Transient 不是 field, PriceFieldResponseAdvice field-walk 不会过滤 →
            // 必须加方法级 @PriceSensitive. 本测试是 invariant 兜底.
            List<String> violations = new ArrayList<>();
            for (Class<?> cls : CLASSES_UNDER_AUDIT) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (!m.getName().startsWith("get")) continue;
                    String prop = m.getName().substring(3);
                    if (prop.isEmpty()) continue;
                    String propLower = Character.toLowerCase(prop.charAt(0)) + prop.substring(1);
                    if (ALLOWLIST.contains(propLower)) continue;
                    boolean keywordMatch = PRICE_FIELD_KEYWORDS.stream()
                            .anyMatch(k -> propLower.toLowerCase().contains(k));
                    if (!keywordMatch) continue;
                    boolean isTransient = false;
                    try {
                        isTransient = m.isAnnotationPresent(jakarta.persistence.Transient.class);
                    } catch (Throwable ignored) { }
                    if (!isTransient) continue;  // 仅 @Transient computed 才有此风险
                    if (!m.isAnnotationPresent(PriceSensitive.class)) {
                        violations.add(String.format("%s.%s() — @Transient computed price-getter 缺 @PriceSensitive (P0 风险, JSON 直泄)",
                                cls.getSimpleName(), m.getName()));
                    }
                }
            }
            if (!violations.isEmpty()) {
                fail(violations.size() + " 个 @Transient 价格 getter 漏注解:\n  - "
                        + String.join("\n  - ", violations));
            }
        }
    }

    @Nested
    @DisplayName("PRICE_VIEW_PERMISSION 常量字符串稳定性 (跨服务约定)")
    class PermissionConstantTests {

        @Test
        @DisplayName("✅ PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION = 'procurement:price:view' 永不更名")
        void permissionStringIsStable() {
            // 这个常量被 PriceMaskResolver / RBACAuditTool / 前端 canViewPrice / SQL seed
            // 多处依赖. 若改名需协调全栈. 本测试 anchor 防意外改名.
            assertEquals("procurement:price:view",
                    PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION,
                    "PRICE_VIEW_PERMISSION 改名会破前端 + SQL seed + RBACAuditTool");
        }
    }

    // ==================== Helper ====================

    private List<Field> getAllFieldsIncludingInherited(Class<?> cls) {
        List<Field> result = new ArrayList<>();
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            Collections.addAll(result, current.getDeclaredFields());
            current = current.getSuperclass();
        }
        return result.stream()
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .filter(f -> !f.isSynthetic())
                .collect(Collectors.toList());
    }
}
