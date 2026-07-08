package com.cretas.aims.service.processentry.impl;

import com.cretas.aims.exception.BusinessException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * G2/F3 共享: 逐工序自定义字段 (波美度/添加剂量/备注等) key 白名单校验。
 *
 * <p>sheet 路径 ({@link ProcessSheetServiceImpl#saveRow}) 与 chain 路径
 * ({@link ClerkProcessEntryServiceImpl#recordChain}) 复用同一判据, 避免 chain 路径
 * 校验旁路 (F3: 任意 key 静默接受, 违背诚实-400 契约)。
 */
final class ProcessCustomFieldValidation {

    private ProcessCustomFieldValidation() {
    }

    /**
     * 校验 {@code submittedKeys} 全部在该工序 {@code schema} 声明的 key 集合内。
     *
     * <ul>
     *   <li>schema == null → 该工序未开启自定义字段 → 放行 (不限制任何 key, 向后兼容)。</li>
     *   <li>submittedKeys 为空 → 无可校验, 直接返回 (最常见路径)。</li>
     *   <li>任一 key 不在 schema → 明确 400 (禁止降级, honest error), 指出具体 key + 工序名。</li>
     * </ul>
     *
     * <p><b>F2(a) 关键</b>: 判据是「key 是否在 schema 里」, <b>不再是</b>「key 是否 enabled」。
     * 字段被 admin 禁用 (enabled=false) 后仍在 schema 里, 因此该行历史存的禁用键再次提交时
     * 不会被误挡成 400 —— 只挡真正未知 (从未在 schema 声明过) 的 key。
     */
    static void checkKeys(List<Map<String, Object>> schema, Set<String> submittedKeys, String processName) {
        if (schema == null || submittedKeys == null || submittedKeys.isEmpty()) {
            return;
        }
        Set<String> knownKeys = schema.stream()
                .map(f -> String.valueOf(f.get("key")))
                .collect(Collectors.toSet());
        for (String key : submittedKeys) {
            if (!knownKeys.contains(key)) {
                throw new BusinessException(400,
                        String.format("工序「%s」未配置自定义字段「%s」，请先在工序设置中添加该字段",
                                processName, key))
                        .withCode("PROCESS_SHEET_CUSTOM_FIELD_UNKNOWN")
                        .withHintTarget(key);
            }
        }
    }
}
