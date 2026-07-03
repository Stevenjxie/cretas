package com.cretas.aims.service.voucher;

import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.finance.VoucherTemplate;
import com.cretas.aims.service.VoucherTemplateService;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Generator 公共基类 — 把 entries 装配成完整 Voucher, 自动调用 validateBalanced().
 *
 * <p>子类实现:
 * <ul>
 *   <li>{@link #getType()}, {@link #supports(String)}, {@link #buildEntries(Object)}</li>
 *   <li>{@link #extractAmount(Object)} — 业务单金额 (用于 totalDebit/totalCredit)</li>
 *   <li>{@link #extractSourceBusinessId(Object)} — 业务单 ID</li>
 *   <li>{@link #extractSourceBusinessType()} — 业务类型字符串</li>
 *   <li>{@link #extractVoucherDate(Object)} — 业务日期</li>
 *   <li>{@link #extractDescription(Object)} — 凭证描述</li>
 * </ul>
 */
public abstract class AbstractVoucherGenerator<T> implements VoucherGenerator<T> {

    /**
     * Sprint 4 W2 Chat J C-VOUCHER-TPL-1: 模板服务. Optional 注入 (@Autowired required=false)
     * 让单元测试 / Sprint 3 E 老 generator 测试不强依赖 — null 时 fall back 到 buildEntries
     * hardcoded path, 保持完全 backward compat.
     */
    @Autowired(required = false)
    protected VoucherTemplateService voucherTemplateService;

    @Override
    public Voucher generate(String factoryId, T businessEntity) {
        // C-VOUCHER-TPL-1: template-first. 找 active template → 用 SpEL 渲染; 否则 fall back
        // 到 子类 buildEntries hardcoded 路径 (backward compat: Sprint 3 E 5 prod voucher 数据
        // 不受影响, 工厂未配置 template 时行为完全一致).
        List<VoucherEntry> entries;
        Optional<VoucherTemplate> tplOpt = voucherTemplateService != null
                ? voucherTemplateService.findActiveTemplate(factoryId, getType())
                : Optional.empty();
        if (tplOpt.isPresent()) {
            entries = voucherTemplateService.renderEntries(tplOpt.get(), businessEntity);
            // 🔴🔒 辅助核算修复 (#1207): template-first 路径 renderEntries 只从 TemplateEntry
            // (无 auxiliary 字段) 渲染, 故模板凭证的分录 auxiliaryType/auxiliaryEntityId 永远为 null
            // → 90/99 有模板的工厂 (含 F006) 金蝶导出的客户/供应商/职员维度全空。这里补一个 hook,
            // 让 generator (它知道源业务单) 把 aux 附到正确的模板分录上, 与 buildEntries 硬编码
            // 路径的 aux 归属一致。honest-null: 无源实体 / 匹配不到目标行 → 不附 (不造假)。
            applyAuxiliary(entries, businessEntity);
        } else {
            // SP11 settlement→subject mapping (#754): 子类可覆盖 factoryId-aware 重载
            // 以按结算方式/业态映射科目; 默认委托回无 factoryId 的 buildEntries (向后兼容).
            entries = buildEntries(factoryId, businessEntity);
        }
        BigDecimal totalDebit = entries.stream()
                .map(e -> Objects.requireNonNullElse(e.getDebit(), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = entries.stream()
                .map(e -> Objects.requireNonNullElse(e.getCredit(), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Voucher voucher = Voucher.builder()
                .factoryId(factoryId)
                .voucherType(getType())
                .voucherDate(extractVoucherDate(businessEntity))
                .sourceBusinessType(extractSourceBusinessType())
                .sourceBusinessId(extractSourceBusinessId(businessEntity))
                .totalDebit(totalDebit)
                .totalCredit(totalCredit)
                .status(VoucherStatus.DRAFT)
                .description(extractDescription(businessEntity))
                .entries(entries)
                .build();

        // back-ref voucher to entries (JPA cascade requires bidirectional)
        for (VoucherEntry e : entries) {
            e.setVoucher(voucher);
        }

        voucher.validateBalanced();
        return voucher;
    }

    /**
     * SP11 settlement→subject mapping (#754): factoryId-aware entries 构建钩子.
     *
     * <p>默认委托给无 factoryId 的 {@link #buildEntries(Object)} (向后兼容 — 现有
     * generator 行为完全不变). 需按结算方式/业态查 {@code voucher_subject_mappings}
     * 覆盖科目的 generator (如 {@link impl.PurchasePaymentVoucherGenerator}) 覆盖此方法.
     *
     * <p>无论是否覆盖, 实现都必须保证 sum(debit) == sum(credit) — generate() 末尾的
     * validateBalanced() 会强制校验.
     */
    protected List<VoucherEntry> buildEntries(String factoryId, T businessEntity) {
        return buildEntries(businessEntity);
    }

    protected abstract String extractSourceBusinessType();

    protected abstract String extractSourceBusinessId(T businessEntity);

    protected abstract LocalDate extractVoucherDate(T businessEntity);

    protected abstract String extractDescription(T businessEntity);

    /**
     * 工具: 构建借方分录 (debit 非零, credit=0).
     */
    protected VoucherEntry debitEntry(int lineNo, String code, String name, BigDecimal amount, String description) {
        return VoucherEntry.builder()
                .lineNo(lineNo)
                .subjectCode(code)
                .subjectName(name)
                .debit(amount)
                .credit(BigDecimal.ZERO)
                .description(description)
                .build();
    }

    /**
     * 工具: 构建贷方分录 (credit 非零, debit=0).
     */
    protected VoucherEntry creditEntry(int lineNo, String code, String name, BigDecimal amount, String description) {
        return VoucherEntry.builder()
                .lineNo(lineNo)
                .subjectCode(code)
                .subjectName(name)
                .debit(BigDecimal.ZERO)
                .credit(amount)
                .description(description)
                .build();
    }

    protected BigDecimal nullToZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    // ==================== 辅助核算 (auxiliary accounting) — template 路径 hook ====================

    /** 分录借贷方向 (辅助核算目标行匹配用). */
    protected enum EntrySide { DEBIT, CREDIT }

    /**
     * 🔴🔒 template-first 路径的辅助核算 hook (#1207). 默认 no-op —— 无辅助核算的 generator
     * 不需要覆盖 (honest-null: 不附即无维度)。
     *
     * <p>在 {@link #buildEntries(String, Object)} 硬编码路径里用 {@code debitEntryWithAuxiliary}/
     * {@code creditEntryWithAuxiliary} 附辅助核算的 generator, 必须覆盖此方法, 把<b>同样的</b>
     * 辅助核算维度附到 <b>template 渲染出的对应分录</b>上 (用 {@link #attachAuxiliary} 匹配)。
     *
     * <p>只在 template 路径调用 (硬编码路径的 aux 已在 buildEntries 内 inline 设置, 不重复)。
     *
     * @param entries        template 渲染出的分录 (可原地 mutate)
     * @param businessEntity  源业务单 (generator 从中取 customerId/supplierId/... )
     */
    protected void applyAuxiliary(List<VoucherEntry> entries, T businessEntity) {
        // 默认无辅助核算
    }

    /**
     * 把辅助核算维度附到 template 渲染分录中"承载该维度的那一行", 镜像 buildEntries 的归属逻辑。
     *
     * <p>匹配策略 (稳健, 容忍客户自定义科目):
     * <ol>
     *   <li>优先: {@code side} 侧且 subjectCode 以 {@code subjectCodePrefix} 开头的行
     *       (标准科目族, 如应收 "1122" / 应付 "2202")；</li>
     *   <li>回退: {@code side} 侧<b>唯一</b>的一行 (硬编码路径下往来科目本就是该侧唯一行,
     *       客户模板改了科目码也能命中)；</li>
     *   <li>再回退: 全部分录里 subjectCode 前缀匹配的行 (侧向歧义时的最后手段)；</li>
     *   <li>都匹配不到 → 不附 (honest-null, 不造假)。</li>
     * </ol>
     *
     * <p>{@code type}/{@code entityId} 任一为 null → 直接不附 (honest-null, 与硬编码路径的
     * {@code auxType != null ? ... : null} 三元一致)。
     */
    protected void attachAuxiliary(List<VoucherEntry> entries, EntrySide side,
            String subjectCodePrefix, AuxiliaryType type, String entityId) {
        if (type == null || entityId == null || entries == null || entries.isEmpty()) {
            return; // honest-null
        }
        VoucherEntry target = findAuxTarget(entries, side, subjectCodePrefix);
        if (target != null) {
            target.setAuxiliaryType(type);
            target.setAuxiliaryEntityId(entityId);
        }
    }

    private VoucherEntry findAuxTarget(List<VoucherEntry> entries, EntrySide side, String prefix) {
        List<VoucherEntry> onSide = new java.util.ArrayList<>();
        for (VoucherEntry e : entries) {
            boolean isDebit = e.getDebit() != null && e.getDebit().signum() > 0;
            boolean isCredit = e.getCredit() != null && e.getCredit().signum() > 0;
            if ((side == EntrySide.DEBIT && isDebit) || (side == EntrySide.CREDIT && isCredit)) {
                onSide.add(e);
            }
        }
        // 1) 优先: 该侧且科目前缀匹配
        if (prefix != null && !prefix.isBlank()) {
            for (VoucherEntry e : onSide) {
                if (e.getSubjectCode() != null && e.getSubjectCode().startsWith(prefix)) {
                    return e;
                }
            }
        }
        // 2) 回退: 该侧唯一一行
        if (onSide.size() == 1) {
            return onSide.get(0);
        }
        // 3) 再回退: 全分录里科目前缀匹配 (侧向歧义时)
        if (prefix != null && !prefix.isBlank()) {
            for (VoucherEntry e : entries) {
                if (e.getSubjectCode() != null && e.getSubjectCode().startsWith(prefix)) {
                    return e;
                }
            }
        }
        return null; // honest-null
    }

    /**
     * Sprint 5 F-2: 借方分录 + 辅助核算 (e.g. 应收账款按客户分账).
     */
    protected VoucherEntry debitEntryWithAuxiliary(int lineNo, String code, String name,
            BigDecimal amount, String description,
            AuxiliaryType auxiliaryType, String auxiliaryEntityId) {
        VoucherEntry entry = debitEntry(lineNo, code, name, amount, description);
        entry.setAuxiliaryType(auxiliaryType);
        entry.setAuxiliaryEntityId(auxiliaryEntityId);
        return entry;
    }

    /**
     * Sprint 5 F-2: 贷方分录 + 辅助核算 (e.g. 应付账款按供应商分账).
     */
    protected VoucherEntry creditEntryWithAuxiliary(int lineNo, String code, String name,
            BigDecimal amount, String description,
            AuxiliaryType auxiliaryType, String auxiliaryEntityId) {
        VoucherEntry entry = creditEntry(lineNo, code, name, amount, description);
        entry.setAuxiliaryType(auxiliaryType);
        entry.setAuxiliaryEntityId(auxiliaryEntityId);
        return entry;
    }
}
