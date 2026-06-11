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
